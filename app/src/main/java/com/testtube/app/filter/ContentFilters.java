package com.testtube.app.filter;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.tencent.mmkv.MMKV;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Watch progress per video (to grey out watched ones), the blocked channels and, when switched on,
 * the channels on the AI channel list.
 */
public final class ContentFilters {
	private static final String WATCH_STORE_ID = "testtube_watch_history";
	private static final String KEY_BLOCKED_CHANNELS = "blocked_channels";
	private static final String KEY_WATCHED_THRESHOLD = "watched_threshold_percent";
	public static final int DEFAULT_WATCHED_THRESHOLD = 75;
	public static final int MIN_WATCHED_THRESHOLD = 50;
	public static final int MAX_WATCHED_THRESHOLD = 100;
	private static final int MAX_WATCH_ENTRIES = 20_000;
	private static final int PRUNE_TO_ENTRIES = 18_000;
	private static final int PERCENT_BITS = 7;
	private static final long PERCENT_MASK = (1L << PERCENT_BITS) - 1L;
	private static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{11}");
	/** Separators YouTube puts between a channel name and extra text ("Name • 1M views"). */
	private static final Pattern NAME_SEPARATOR = Pattern.compile("[•·|]");
	private static final Pattern WHITESPACE = Pattern.compile("\\s+");

	@NonNull
	private final MMKV watchStore;
	@NonNull
	private final MMKV mmkv;
	@NonNull
	private final Gson gson;
	@NonNull
	private final AiChannelList aiChannels;
	/** Read once from storage; -1 until then. Every list row asks for it. */
	private volatile int threshold = -1;
	/** Blocked channels prepared for lookups; rebuilt after the list changes. Every list row asks. */
	@Nullable
	private volatile BlockedIndex blockedIndex;

	public ContentFilters(@NonNull MMKV mmkv, @NonNull Gson gson, @NonNull AiChannelList aiChannels) {
		this.mmkv = mmkv;
		this.gson = gson;
		this.aiChannels = aiChannels;
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

	/**
	 * Records playback progress. Only ever raises the stored percentage.
	 */
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

	/** How much of a video has to be played for it to count as watched, in percent. */
	public int watchedThreshold() {
		int percent = threshold;
		if (percent < 0) {
			percent = clampThreshold(mmkv.decodeInt(KEY_WATCHED_THRESHOLD, DEFAULT_WATCHED_THRESHOLD));
			threshold = percent;
		}
		return percent;
	}

	public void setWatchedThreshold(int percent) {
		int clamped = clampThreshold(percent);
		threshold = clamped;
		mmkv.encode(KEY_WATCHED_THRESHOLD, clamped);
	}

	private static int clampThreshold(int percent) {
		return Math.max(MIN_WATCHED_THRESHOLD, Math.min(MAX_WATCHED_THRESHOLD, percent));
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

	/**
	 * Normalises a channel link to its path, e.g. "/@handle" or "/channel/UCxxxx".
	 */
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
		String first = NAME_SEPARATOR.split(name, 2)[0];
		return WHITESPACE.matcher(first.trim()).replaceAll(" ").toLowerCase(Locale.ROOT);
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
		blockedIndex = null;
	}

	/**
	 * Blocks a channel. Returns false when the name is unusable.
	 */
	public synchronized boolean blockChannel(@Nullable String name, @Nullable String url, @Nullable String altUrl) {
		String display = name == null ? "" : NAME_SEPARATOR.split(name, 2)[0].trim();
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
		if (aiChannels.matches(path)) return true;
		BlockedIndex index = blockedIndex();
		if (path != null && index.paths().contains(path)) return true;
		String normalized = normalizeName(name);
		return !normalized.isEmpty() && index.names().contains(normalized);
	}

	@NonNull
	private BlockedIndex blockedIndex() {
		BlockedIndex index = blockedIndex;
		if (index != null) return index;
		synchronized (this) {
			if (blockedIndex != null) return blockedIndex;
			Set<String> paths = new HashSet<>();
			Set<String> names = new HashSet<>();
			for (BlockedChannel item : blockedChannels()) {
				if (item.path() != null) paths.add(item.path());
				if (item.altPath() != null) paths.add(item.altPath());
				String normalized = normalizeName(item.name());
				if (!normalized.isEmpty()) names.add(normalized);
			}
			index = new BlockedIndex(Set.copyOf(paths), Set.copyOf(names));
			blockedIndex = index;
			return index;
		}
	}

	private record BlockedIndex(@NonNull Set<String> paths, @NonNull Set<String> names) {
	}
}
