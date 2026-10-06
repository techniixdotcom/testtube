package com.testtube.app.filter;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.tencent.mmkv.MMKV;
import com.testtube.app.extension.Constant;
import com.testtube.app.extension.ExtensionManager;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;


/**
 * Stores how much of every video has been watched and which channels are blocked, and exposes
 * both to the pages so watched videos can be greyed out and blocked channels hidden.
 */
public final class ContentFilters {
	private static final String WATCH_STORE_ID = "testtube_watch_history";
	private static final String KEY_BLOCKED_CHANNELS = "blocked_channels";
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
	@NonNull
	private final ExtensionManager extensionManager;
	@Nullable
	private String cachedScriptData;
	private long cachedVersion = -1L;
	private int cachedWatchCount = -1;

	public ContentFilters(@NonNull MMKV mmkv,
	                      @NonNull Gson gson,
	                      @NonNull ExtensionManager extensionManager) {
		this.mmkv = mmkv;
		this.gson = gson;
		this.extensionManager = extensionManager;
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
			int threshold = watchedThreshold();
			if ((stored < 0L ? 0 : percentOf(stored)) < threshold && percent >= threshold) {
				watchDirty = true;
			}
			watchStore.encode(videoId, pack(percent, System.currentTimeMillis() / 1000L));
			pruneIfNeeded();
		}
	}

	public synchronized void markWatched(@Nullable String videoId) {
		if (!isVideoId(videoId)) return;
		watchStore.encode(videoId, pack(100, System.currentTimeMillis() / 1000L));
		pruneIfNeeded();
		extensionManager.notifyChanged();
	}

	public synchronized void markUnwatched(@Nullable String videoId) {
		if (!isVideoId(videoId)) return;
		watchStore.removeValueForKey(videoId);
		extensionManager.notifyChanged();
	}

	public synchronized int watchedPercent(@Nullable String videoId) {
		if (!isVideoId(videoId)) return 0;
		long stored = watchStore.decodeLong(videoId, -1L);
		return stored < 0L ? 0 : percentOf(stored);
	}

	public int watchedThreshold() {
		return Constant.WATCHED_THRESHOLD_PERCENT;
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

	@NonNull
	private synchronized List<String> watchedIds(int threshold) {
		List<String> out = new ArrayList<>();
		String[] keys = watchStore.allKeys();
		if (keys == null) return out;
		for (String key : keys) {
			if (percentOf(watchStore.decodeLong(key, 0L)) >= threshold) {
				out.add(key);
			}
		}
		return out;
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
		extensionManager.notifyChanged();
	}

	/**
	 * Blocks a channel. Returns false when the name is unusable.
	 */
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

	/**
	 * Data consumed by the content filter script in every page.
	 */
	@NonNull
	public synchronized String scriptData() {
		// Settings and blocked channels bump the extension version; the watch list only matters
		// when an entry crosses the threshold, which also changes how many ids qualify.
		long version = extensionManager.version();
		int watchCount = (int) watchStore.count();
		if (cachedScriptData != null && version == cachedVersion && watchCount == cachedWatchCount
						&& !watchDirty) {
			return cachedScriptData;
		}
		watchDirty = false;
		cachedVersion = version;
		cachedWatchCount = watchCount;
		cachedScriptData = buildScriptData();
		return cachedScriptData;
	}

	private boolean watchDirty = true;

	@NonNull
	private String buildScriptData() {
		JsonObject root = new JsonObject();
		root.addProperty("greyWatched", true);
		JsonArray watched = new JsonArray();
		for (String id : watchedIds(watchedThreshold())) {
			watched.add(id);
		}
		root.add("watched", watched);
		JsonArray blocked = new JsonArray();
		for (BlockedChannel item : blockedChannels()) {
			JsonObject entry = new JsonObject();
			entry.addProperty("name", normalizeName(item.name()));
			JsonArray paths = new JsonArray();
			if (item.path() != null) paths.add(item.path());
			if (item.altPath() != null) paths.add(item.altPath());
			entry.add("paths", paths);
			blocked.add(entry);
		}
		root.add("blocked", blocked);
		return gson.toJson(root);
	}
}
