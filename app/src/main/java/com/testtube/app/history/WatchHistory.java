package com.testtube.app.history;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.tencent.mmkv.MMKV;
import com.testtube.app.filter.ContentFilters;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;


/**
 * Local list of played videos, newest first. It is only a record: nothing else in the app reads
 * it to make decisions.
 */
public final class WatchHistory {
	private static final String STORE_ID = "testtube_watch_log";
	private static final String KEY_ENTRIES = "entries";
	private static final int MAX_ENTRIES = 1_000;
	private static final int MAX_TEXT = 300;

	@NonNull
	private final MMKV store;
	@NonNull
	private final Gson gson;
	@Nullable
	private List<Entry> cache;

	public WatchHistory(@NonNull Gson gson) {
		this.gson = gson;
		this.store = MMKV.mmkvWithID(STORE_ID);
	}

	/**
	 * Puts a video at the top of the history, moving it there if it was already listed.
	 */
	public synchronized void record(@Nullable String videoId,
	                                @Nullable String title,
	                                @Nullable String author,
	                                @Nullable String thumbnailUrl) {
		if (!ContentFilters.isVideoId(videoId)) return;
		List<Entry> entries = load();
		entries.removeIf(entry -> videoId.equals(entry.videoId()));
		entries.add(0, new Entry(videoId, clip(title), clip(author), safeThumbnail(thumbnailUrl),
						System.currentTimeMillis()));
		while (entries.size() > MAX_ENTRIES) {
			entries.remove(entries.size() - 1);
		}
		save(entries);
	}

	/**
	 * Adds entries from a backup that are not listed yet, keeping newest first.
	 *
	 * @return number of entries added
	 */
	public synchronized int merge(@NonNull List<Entry> incoming) {
		List<Entry> entries = load();
		int added = 0;
		for (Entry entry : incoming) {
			if (entry == null || !ContentFilters.isVideoId(entry.videoId())) continue;
			boolean known = false;
			for (Entry existing : entries) {
				if (existing.videoId().equals(entry.videoId())) {
					known = true;
					break;
				}
			}
			if (known) continue;
			entries.add(new Entry(entry.videoId(), clip(entry.title()), clip(entry.author()),
							safeThumbnail(entry.thumbnailUrl()), Math.max(0L, entry.watchedAt())));
			added++;
		}
		if (added > 0) {
			entries.sort((a, b) -> Long.compare(b.watchedAt(), a.watchedAt()));
			while (entries.size() > MAX_ENTRIES) entries.remove(entries.size() - 1);
			save(entries);
		}
		return added;
	}

	@NonNull
	public synchronized List<Entry> entries() {
		return new ArrayList<>(load());
	}

	public synchronized void remove(@NonNull String videoId) {
		List<Entry> entries = load();
		if (entries.removeIf(entry -> videoId.equals(entry.videoId()))) save(entries);
	}

	public synchronized void clear() {
		cache = new ArrayList<>();
		store.removeValueForKey(KEY_ENTRIES);
	}

	@NonNull
	private List<Entry> load() {
		if (cache != null) return cache;
		List<Entry> entries = new ArrayList<>();
		String json = store.decodeString(KEY_ENTRIES, null);
		if (json != null && !json.isBlank()) {
			try {
				Entry[] stored = gson.fromJson(json, Entry[].class);
				if (stored != null) {
					for (Entry entry : Arrays.asList(stored)) {
						if (entry != null && ContentFilters.isVideoId(entry.videoId())) entries.add(entry);
					}
				}
			} catch (RuntimeException e) {
				store.removeValueForKey(KEY_ENTRIES);
			}
		}
		cache = entries;
		return entries;
	}

	private void save(@NonNull List<Entry> entries) {
		cache = entries;
		store.encode(KEY_ENTRIES, gson.toJson(entries.toArray(new Entry[0])));
	}

	@Nullable
	private static String clip(@Nullable String value) {
		if (value == null) return null;
		String trimmed = value.trim();
		return trimmed.length() > MAX_TEXT ? trimmed.substring(0, MAX_TEXT) : trimmed;
	}

	@Nullable
	private static String safeThumbnail(@Nullable String url) {
		return url != null && url.startsWith("https://") && url.length() <= 1_000 ? url : null;
	}

	public record Entry(@NonNull String videoId,
	                    @Nullable String title,
	                    @Nullable String author,
	                    @Nullable String thumbnailUrl,
	                    long watchedAt) {
	}
}
