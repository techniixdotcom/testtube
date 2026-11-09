package com.testtube.app.player.engine;

import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.CacheWriter;

import com.testtube.app.extractor.PlaybackDetails;
import com.testtube.app.extractor.PlaybackPlan;
import com.testtube.app.extractor.StreamCandidate;

import org.schabi.newpipe.extractor.services.youtube.ItagItem;
import org.schabi.newpipe.extractor.stream.Stream;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Downloads the start of the next video into the player cache while this one plays, so it starts
 * without buffering. Same keys as playback. Shorts use {@link #enqueue} to have several ready.
 */
@UnstableApi
final class StreamPreloader {
	private static final String TAG = "StreamPreloader";
	/** Seconds of the next video to have ready, by default. */
	private static final long PRELOAD_SECONDS = 30L;
	/** Caps and fallbacks for {@link #PRELOAD_SECONDS}; shorter preloads get their share. */
	private static final long MAX_VIDEO_BYTES = 12L * 1024 * 1024;
	private static final long MAX_AUDIO_BYTES = 2L * 1024 * 1024;
	/** For streams without a known bitrate. */
	private static final long DEFAULT_VIDEO_BYTES = 4L * 1024 * 1024;
	private static final long DEFAULT_AUDIO_BYTES = 512L * 1024;
	/** Queued videos, so nothing downloads twice. */
	private static final int MAX_QUEUED = 16;

	@Nullable
	private final CacheDataSource.Factory cache;
	private final long seconds;
	@NonNull
	private final Set<String> queued = new LinkedHashSet<>();
	@NonNull
	private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "testtube-preload");
		thread.setDaemon(true);
		thread.setPriority(Thread.MIN_PRIORITY);
		return thread;
	});
	private final Object lock = new Object();
	/** Bumped by every preload and cancel so writers of an older request stop. */
	private long generation;
	@Nullable
	private String videoId;
	@Nullable
	private CacheWriter writer;

	/** @param cache player cache, or null (no preloading then) */
	StreamPreloader(@Nullable CacheDataSource.Factory cache) {
		this(cache, PRELOAD_SECONDS);
	}

	/**
	 * @param cache   player cache, or null (no preloading then)
	 * @param seconds how much of each video's start to cache
	 */
	StreamPreloader(@Nullable CacheDataSource.Factory cache, long seconds) {
		this.cache = cache;
		this.seconds = seconds;
	}

	/** Caches the streams {@code details}' plan plays. Stops any earlier preload. */
	void preload(@NonNull PlaybackDetails details) {
		CacheDataSource.Factory factory = cache;
		String id = details.video().getId();
		if (factory == null || id == null) return;
		List<DataSpec> specs = specsFor(details.plan());
		if (specs.isEmpty()) return;
		long token;
		synchronized (lock) {
			if (id.equals(videoId)) return;
			stopLocked();
			videoId = id;
			token = generation;
		}
		run(factory, specs, token, id);
	}

	/**
	 * Queues a video's start behind what's loading, stops nothing. Each video once.
	 * {@link #preload} and {@link #cancel} drop the queue.
	 * @param plan the streams playback picks, so the keys match
	 */
	void enqueue(@NonNull String id, @NonNull PlaybackPlan plan) {
		CacheDataSource.Factory factory = cache;
		if (factory == null) return;
		List<DataSpec> specs = specsFor(plan);
		if (specs.isEmpty()) return;
		long token;
		synchronized (lock) {
			if (!queued.add(id)) return;
			if (queued.size() > MAX_QUEUED) {
				Iterator<String> oldest = queued.iterator();
				oldest.next();
				oldest.remove();
			}
			token = generation;
		}
		run(factory, specs, token, id);
	}

	/** Stops the running preload, what's cached stays. */
	void cancel() {
		synchronized (lock) {
			stopLocked();
			videoId = null;
		}
	}

	void release() {
		cancel();
		executor.shutdownNow();
	}

	private void run(@NonNull CacheDataSource.Factory factory, @NonNull List<DataSpec> specs, long token,
	                 @NonNull String id) {
		try {
			executor.execute(() -> {
				for (DataSpec spec : specs) {
					if (!write(factory, spec, token, id)) return;
				}
			});
		} catch (RejectedExecutionException released) {
			// late call after release(), no player left
		}
	}

	/** @return false if this preload was replaced or cancelled */
	private boolean write(@NonNull CacheDataSource.Factory factory, @NonNull DataSpec spec, long token,
	                      @NonNull String id) {
		CacheWriter current = new CacheWriter(factory.createDataSource(), spec, null, null);
		synchronized (lock) {
			if (token != generation) return false;
			writer = current;
		}
		try {
			current.cache();
		} catch (InterruptedIOException e) {
			return false;
		} catch (IOException | RuntimeException e) {
			// the player loads it normally then
			Log.w(TAG, "preload failed videoId=" + id, e);
		} finally {
			synchronized (lock) {
				if (writer == current) writer = null;
			}
		}
		return true;
	}

	private void stopLocked() {
		generation++;
		queued.clear();
		if (writer != null) {
			writer.cancel();
			writer = null;
		}
	}

	@NonNull
	private List<DataSpec> specsFor(@Nullable PlaybackPlan plan) {
		List<DataSpec> specs = new ArrayList<>();
		if (plan == null || plan.getMode() == null) return specs;
		switch (plan.getMode()) {
			case ADAPTIVE -> {
				addSpec(specs, videoStream(plan.getVideoCandidate()), DEFAULT_VIDEO_BYTES, MAX_VIDEO_BYTES);
				addSpec(specs, audioStream(plan.getAudioCandidate()), DEFAULT_AUDIO_BYTES, MAX_AUDIO_BYTES);
			}
			case MUXED -> addSpec(specs, videoStream(plan.getMuxedCandidate()), DEFAULT_VIDEO_BYTES, MAX_VIDEO_BYTES);
			case AUDIO_ONLY -> addSpec(specs, audioStream(plan.getAudioCandidate()), DEFAULT_AUDIO_BYTES, MAX_AUDIO_BYTES);
			// live has no start to cache
			default -> {
			}
		}
		return specs;
	}

	@Nullable
	private static Stream videoStream(@Nullable StreamCandidate candidate) {
		return candidate != null ? candidate.getVideoStream() : null;
	}

	@Nullable
	private static Stream audioStream(@Nullable StreamCandidate candidate) {
		return candidate != null ? candidate.getAudioStream() : null;
	}

	private void addSpec(@NonNull List<DataSpec> specs, @Nullable Stream stream,
	                     long defaultBytes, long maxBytes) {
		if (stream == null || stream.getContent() == null || stream.getContent().isBlank()) return;
		Uri uri = Uri.parse(stream.getContent().trim());
		String scheme = uri.getScheme();
		if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) return;
		specs.add(new DataSpec.Builder()
						.setUri(uri)
						.setPosition(0L)
						.setLength(preloadBytes(stream.getItagItem(), defaultBytes, maxBytes))
						.build());
	}

	/** First {@link #seconds} at the stream's bitrate, always with its index. */
	private long preloadBytes(@Nullable ItagItem itag, long defaultBytes, long maxBytes) {
		long fallback = Math.max(1L, defaultBytes * seconds / PRELOAD_SECONDS);
		if (itag == null) return fallback;
		long bytes = itag.getBitrate() > 0 ? itag.getBitrate() / 8L * seconds : fallback;
		bytes = Math.min(bytes, Math.max(1L, maxBytes * seconds / PRELOAD_SECONDS));
		// DASH index is at the start, playback can't begin without it
		if (itag.getIndexEnd() > 0) bytes = Math.max(bytes, itag.getIndexEnd() + 1L);
		long length = itag.getContentLength();
		if (length > 0) bytes = Math.min(bytes, length);
		return Math.max(1L, bytes);
	}
}
