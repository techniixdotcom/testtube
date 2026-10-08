package com.testtube.app.filter;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.tencent.mmkv.MMKV;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Watch progress per video (for greying out) and the list of blocked channels. */
public final class ContentFilters {
	private static final String WATCH_STORE_ID = "testtube_watch_history";
	private static final String KEY_BLOCKED_CHANNELS = "blocked_channels";
	private static final int WATCHED_THRESHOLD_PERCENT = 75;
	private static final int MAX_WATCH_ENTRIES = 20_000;
	private static final int PRUNE_TO_ENTRIES = 18_000;
	private static final int PERCENT_BITS = 7;
	private static final long PERCENT_MASK = (1L << PERCENT_BITS) - 1L;
	private static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{11}");

	@NonNull
	private final MMKV watchStore;
	@NonNull
	private final MMKV mmkv;
	@NonNull
	private final Gson gson;

	public ContentFilters(@NonNull MMKV mmkv, @NonNull Gson gson) {
		this.mmkv = mmkv;
		this.gson = gson;
		this.watchStore = MMKV.mmkvWithID(WATCH_STORE_ID);
	}

	public static boolean isVideoId(@Nullable String videoId) {
		return videoId != null && VIDEO_ID.matcher(videoId).matches();
	}

	private static long pack(int percent, long epochSeconds) {
		return (epochSeconds << PERCENT_BITS) | (percent & PERCENT_MASK);
	}

	private static int percentOf(long packed) {
		return (int) (packed & PERCENT_MASK);
	}

	private static long timeOf(long packed) {
		return packed >>> PERCENT_BITS;
	}

	/** Saves watch progress. Never lowers the stored percentage. */
	public void recordProgress(@Nullable String videoId, long positionMs, long durationMs) {
		if (!isVideoId(videoId) || durationMs <= 0L || positionMs < 0L) return;
		int percent = (int) Math.min(100L, Math.max(0L, positionMs * 100L / durationMs));
		synchronized (this) {
			long stored = watchStore.decodeLong(videoId, -1L);
			if (stored >= 0L && percentOf(stored) >= percent) return;
			watchStore.encode(videoId, pack(percent, System.currentTimeMillis() / 1000L));
			pruneIfNeeded();
		}
	}

	public synchronized void markWatched(@Nullable String videoId) {
		if (!isVideoId(videoId)) return;
		watchStore.encode(videoId, pack(100, System.currentTimeMillis() / 1000L));
		pruneIfNeeded();
	}

	public synchronized void markUnwatched(@Nullable String videoId) {
		if (!isVideoId(videoId)) return;
		watchStore.removeValueForKey(videoId);
	}

	public synchronized int watchedPercent(@Nullable String videoId) {
		if (!isVideoId(videoId)) return 0;
		long stored = watchStore.decodeLong(videoId, -1L);
		return stored < 0L ? 0 : percentOf(stored);
	}

	public int watchedThreshold() {
		return WATCHED_THRESHOLD_PERCENT;
	}

	public boolean isWatched(@Nullable String videoId) {
		return watchedPercent(videoId) >= watchedThreshold();
	}

	private void pruneIfNeeded() {
		if (watchStore.count() <= MAX_WATCH_ENTRIES) return;
		String[] keys = watchStore.allKeys();
		if (keys == null) return;
		List<String> ordered = new ArrayList<>(Arrays.asList(keys));
		ordered.sort(Comparator.comparingLong(key -> timeOf(watchStore.decodeLong(key, 0L))));
		int remove = ordered.size() - PRUNE_TO_ENTRIES;
		for (int i = 0; i < remove; i++) {
			watchStore.removeValueForKey(ordered.get(i));
		}
	}

	/** Normalizes a channel link to its path, e.g. "/@handle" or "/channel/UCxxxx". */
	@Nullable
	public static String channelPath(@Nullable String url) {
		if (url == null || url.isBlank()) return null;
		String path;
		try {
			URI uri = URI.create(url.trim());
			path = uri.getRawPath();
			if (path == null || path.isBlank()) {
				path = url.trim();
			}
		} catch (IllegalArgumentException e) {
			path = url.trim();
		}
		String[] parts = path.split("/");
		for (int i = 0; i < parts.length; i++) {
			String part = parts[i];
			if (part.startsWith("@") && part.length() > 1) {
				return "/" + part.toLowerCase(Locale.ROOT);
			}
			if (("channel".equals(part) || "c".equals(part) || "user".equals(part))
							&& i + 1 < parts.length && !parts[i + 1].isBlank()) {
				return "/" + part + "/" + parts[i + 1].toLowerCase(Locale.ROOT);
			}
		}
		return null;
	}

	@NonNull
	public static String normalizeName(@Nullable String name) {
		if (name == null) return "";
		String first = name.split("[•·|]", 2)[0];
		return first.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
	}

	@NonNull
	public synchronized List<BlockedChannel> blockedChannels() {
		String json = mmkv.decodeString(KEY_BLOCKED_CHANNELS, null);
		List<BlockedChannel> out = new ArrayList<>();
		if (json == null || json.isBlank()) return out;
		try {
			BlockedChannel[] items = gson.fromJson(json, BlockedChannel[].class);
			if (items == null) return out;
			for (BlockedChannel item : items) {
				if (item != null && item.name() != null && !item.name().isBlank()) {
					out.add(item);
				}
			}
		} catch (RuntimeException e) {
			mmkv.removeValueForKey(KEY_BLOCKED_CHANNELS);
		}
		return out;
	}

	private void writeBlocked(@NonNull List<BlockedChannel> items) {
		mmkv.encode(KEY_BLOCKED_CHANNELS, gson.toJson(items.toArray(new BlockedChannel[0])));
	}

	/** Returns false if the name is unusable. */
	public synchronized boolean blockChannel(@Nullable String name, @Nullable String url, @Nullable String altUrl) {
		String display = name == null ? "" : name.split("[•·|]", 2)[0].trim();
		if (display.isEmpty()) return false;
		String path = channelPath(url);
		String altPath = channelPath(altUrl);
		if (path == null) {
			path = altPath;
			altPath = null;
		} else if (path.equals(altPath)) {
			altPath = null;
		}
		String finalPath = path;
		String finalAltPath = altPath;
		List<BlockedChannel> items = blockedChannels();
		items.removeIf(item -> matches(item, display, finalPath) || matches(item, display, finalAltPath));
		items.add(new BlockedChannel(display, path, altPath, System.currentTimeMillis()));
		writeBlocked(items);
		return true;
	}

	private static boolean matches(@NonNull BlockedChannel item,
	                               @Nullable String name,
	                               @Nullable String path) {
		if (path != null && (path.equals(item.path()) || path.equals(item.altPath()))) return true;
		String normalized = normalizeName(name);
		return !normalized.isEmpty() && normalized.equals(normalizeName(item.name()));
	}

	public boolean isChannelBlocked(@Nullable String name, @Nullable String url) {
		String path = channelPath(url);
		for (BlockedChannel item : blockedChannels()) {
			if (matches(item, name, path)) return true;
		}
		return false;
	}
}
