package com.testtube.app.extractor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.tencent.mmkv.MMKV;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class InfoCache {
	private static final String STORE_ID = "testtube_extractor_cache";
	private static final String STREAM_KEY = "extractor:stream:";
	private static final String RELATED_KEY = "extractor:related2:";
	private static final String UNTIL_PREFIX = "u:";
	private static final String LEGACY_PREFIX = "extractor:";
	private static final int PRUNE_EVERY_WRITES = 50;

	// Own MMKV store so expired entries can be swept and the file trimmed without touching settings.
	// Expiry is kept under separate keys so sweeping doesn't have to parse payloads.
	@NonNull
	private final MMKV store;
	@NonNull
	private final Gson gson;
	private final AtomicInteger writes = new AtomicInteger();

	public InfoCache(@NonNull MMKV kv,
	                 @NonNull Gson gson) {
		this.gson = gson;
		this.store = MMKV.mmkvWithID(STORE_ID);
		Thread cleanup = new Thread(() -> {
			removeLegacyEntries(kv);
			pruneExpired();
		}, "testtube-cache-cleanup");
		cleanup.setDaemon(true);
		cleanup.start();
	}

	@Nullable
	public PlaybackDetails getPlaybackDetails(@NonNull String videoId) {
		return read(STREAM_KEY + videoId, PlaybackDetails.class);
	}

	public void putPlaybackDetails(@NonNull String videoId,
	                               @NonNull PlaybackDetails details) {
		// Stream URLs are good for hours. If one did expire, playback recovers by re-extracting,
		// so reopening a recent video can skip extraction completely.
		write(STREAM_KEY + videoId, details, TimeUnit.MINUTES.toMillis(30));
	}

	public void removePlaybackDetails(@NonNull String videoId) {
		remove(STREAM_KEY + videoId);
	}

	@Nullable
	public List<RelatedVideo> getRelatedVideos(@NonNull String videoId) {
		RelatedVideo[] items = read(RELATED_KEY + videoId, RelatedVideo[].class);
		return items == null ? null : new ArrayList<>(Arrays.asList(items));
	}

	public void putRelatedVideos(@NonNull String videoId,
	                             @NonNull List<RelatedVideo> items) {
		write(RELATED_KEY + videoId, items.toArray(new RelatedVideo[0]), TimeUnit.HOURS.toMillis(6));
	}

	@Nullable
	private <T> T read(@NonNull String key,
	                   @NonNull Class<T> type) {
		long until = store.decodeLong(UNTIL_PREFIX + key, 0L);
		if (until <= System.currentTimeMillis()) {
			if (until != 0L) remove(key);
			return null;
		}
		String raw = store.decodeString(key, null);
		if (raw == null || raw.isBlank()) return null;
		try {
			return gson.fromJson(raw, type);
		} catch (RuntimeException ignored) {
			remove(key);
			return null;
		}
	}

	private void write(@NonNull String key,
	                   @NonNull Object value,
	                   final long ttlMs) {
		// expiry first, so a concurrent sweep never sees a value without one
		store.encode(UNTIL_PREFIX + key, System.currentTimeMillis() + ttlMs);
		store.encode(key, gson.toJson(value));
		if (writes.incrementAndGet() % PRUNE_EVERY_WRITES == 0) {
			Thread prune = new Thread(this::pruneExpired, "testtube-cache-prune");
			prune.setDaemon(true);
			prune.start();
		}
	}

	private void remove(@NonNull String key) {
		store.removeValueForKey(key);
		store.removeValueForKey(UNTIL_PREFIX + key);
	}

	private synchronized void pruneExpired() {
		String[] keys = store.allKeys();
		if (keys == null) return;
		long now = System.currentTimeMillis();
		boolean removed = false;
		for (String key : keys) {
			if (!key.startsWith(UNTIL_PREFIX)) {
				if (!store.containsKey(UNTIL_PREFIX + key)) {
					store.removeValueForKey(key);
					removed = true;
				}
				continue;
			}
			if (store.decodeLong(key, 0L) <= now) {
				remove(key.substring(UNTIL_PREFIX.length()));
				removed = true;
			}
		}
		if (removed) store.trim();
	}

	// Old versions kept this cache in the main settings and never cleaned it. Removed once.
	private static void removeLegacyEntries(@NonNull MMKV kv) {
		String[] keys = kv.allKeys();
		if (keys == null) return;
		boolean removed = false;
		for (String key : keys) {
			if (key.startsWith(LEGACY_PREFIX)) {
				kv.removeValueForKey(key);
				removed = true;
			}
		}
		if (removed) kv.trim();
	}
}
