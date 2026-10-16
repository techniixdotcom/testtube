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
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Downloads the start of the next video into the player cache while the current one plays, so the
 * next one starts without buffering. Playback reads the cache with the same keys.
 */
@UnstableApi
final class StreamPreloader {
	private static final String TAG = "StreamPreloader";
	/** Seconds of the next video to have ready. */
	private static final long PRELOAD_SECONDS = 30L;
	private static final long MAX_VIDEO_BYTES = 12L * 1024 * 1024;
	private static final long MAX_AUDIO_BYTES = 2L * 1024 * 1024;
	/** For streams without a known bitrate. */
	private static final long DEFAULT_VIDEO_BYTES = 4L * 1024 * 1024;
	private static final long DEFAULT_AUDIO_BYTES = 512L * 1024;

	@Nullable
	private final CacheDataSource.Factory cache;
	@NonNull
	private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "testtube-preload");
		thread.setDaemon(true);
		thread.setPriority(Thread.MIN_PRIORITY);
		return thread;
	});
	private final Object lock = new Object();
	/** Bumped by every preload and cancel, so writers of an older request stop. */
	private long generation;
	@Nullable
	private String videoId;
	@Nullable
	private CacheWriter writer;

	/** @param cache the player cache, or null when there is none (nothing is preloaded then) */
	StreamPreloader(@Nullable CacheDataSource.Factory cache) {
		this.cache = cache;
	}

	/** Starts caching the streams the plan of {@code details} plays. Any earlier preload stops. */
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
		executor.execute(() -> {
			for (DataSpec spec : specs) {
				if (!write(factory, spec, token, id)) return;
			}
		});
	}

	/** Stops the running preload; what was cached so far stays. */
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

	/** @return false when this preload was replaced or cancelled */
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
			// The player loads it the usual way then
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
		if (writer != null) {
			writer.cancel();
			writer = null;
		}
	}

	@NonNull
	private static List<DataSpec> specsFor(@Nullable PlaybackPlan plan) {
		List<DataSpec> specs = new ArrayList<>();
		if (plan == null || plan.getMode() == null) return specs;
		switch (plan.getMode()) {
			case ADAPTIVE -> {
				addSpec(specs, videoStream(plan.getVideoCandidate()), DEFAULT_VIDEO_BYTES, MAX_VIDEO_BYTES);
				addSpec(specs, audioStream(plan.getAudioCandidate()), DEFAULT_AUDIO_BYTES, MAX_AUDIO_BYTES);
			}
			case MUXED -> addSpec(specs, videoStream(plan.getMuxedCandidate()), DEFAULT_VIDEO_BYTES, MAX_VIDEO_BYTES);
			case AUDIO_ONLY -> addSpec(specs, audioStream(plan.getAudioCandidate()), DEFAULT_AUDIO_BYTES, MAX_AUDIO_BYTES);
			// Live streams have no start to cache
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

	private static void addSpec(@NonNull List<DataSpec> specs, @Nullable Stream stream,
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

	/** The first {@link #PRELOAD_SECONDS} at the stream's bitrate, always with its index. */
	static long preloadBytes(@Nullable ItagItem itag, long defaultBytes, long maxBytes) {
		if (itag == null) return defaultBytes;
		long bytes = itag.getBitrate() > 0 ? itag.getBitrate() / 8L * PRELOAD_SECONDS : defaultBytes;
		bytes = Math.min(bytes, maxBytes);
		// The DASH index sits at the start; playback can't begin without it
		if (itag.getIndexEnd() > 0) bytes = Math.max(bytes, itag.getIndexEnd() + 1L);
		long length = itag.getContentLength();
		if (length > 0) bytes = Math.min(bytes, length);
		return Math.max(1L, bytes);
	}
}
