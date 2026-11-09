package com.testtube.app.player.engine;

import androidx.annotation.NonNull;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.cache.Cache;
import androidx.media3.datasource.cache.CacheEvictor;
import androidx.media3.datasource.cache.CacheSpan;

import java.util.TreeSet;

/**
 * Keeps the player cache under a size limit, dropping least recently used, like media3's
 * LeastRecentlyUsedCacheEvictor but the limit can change at runtime (Video cache setting).
 * Lowering it frees space right away.
 */
@UnstableApi
public final class VideoCacheEvictor implements CacheEvictor {
	private final TreeSet<CacheSpan> leastRecentlyUsed = new TreeSet<>((a, b) -> {
		long diff = a.lastTouchTimestamp - b.lastTouchTimestamp;
		return diff == 0 ? a.compareTo(b) : diff < 0 ? -1 : 1;
	});
	private volatile long maxBytes;
	private long currentSize;

	public VideoCacheEvictor(long maxBytes) {
		this.maxBytes = maxBytes;
	}

	/** New limit; if lower, drops the oldest until it fits. File work, off the main thread. */
	public void setMaxBytes(@NonNull Cache cache, long maxBytes) {
		this.maxBytes = maxBytes;
		synchronized (cache) {
			evict(cache, 0);
		}
	}

	@Override
	public boolean requiresCacheSpanTouches() {
		return true;
	}

	@Override
	public void onCacheInitialized() {
	}

	@Override
	public void onStartFile(@NonNull Cache cache, @NonNull String key, long position, long length) {
		if (length != C.LENGTH_UNSET) evict(cache, length);
	}

	@Override
	public void onSpanAdded(@NonNull Cache cache, @NonNull CacheSpan span) {
		leastRecentlyUsed.add(span);
		currentSize += span.length;
		evict(cache, 0);
	}

	@Override
	public void onSpanRemoved(@NonNull Cache cache, @NonNull CacheSpan span) {
		leastRecentlyUsed.remove(span);
		currentSize -= span.length;
	}

	@Override
	public void onSpanTouched(@NonNull Cache cache, @NonNull CacheSpan oldSpan, @NonNull CacheSpan newSpan) {
		onSpanRemoved(cache, oldSpan);
		onSpanAdded(cache, newSpan);
	}

	private void evict(@NonNull Cache cache, long requiredSpace) {
		while (currentSize + requiredSpace > maxBytes && !leastRecentlyUsed.isEmpty()) {
			cache.removeSpan(leastRecentlyUsed.first());
		}
	}
}
