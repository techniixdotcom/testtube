package com.testtube.app.player.engine.datasource;

import android.content.Context;
import android.net.http.HttpEngine;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresExtension;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.HttpEngineDataSource;

import java.io.File;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Android's built-in Chromium stack (HttpEngine), shared by all players. Speaks HTTP/3 over
 * QUIC to googlevideo like YouTube's app and falls back to HTTP/2 or 1.1 by itself. Remembers QUIC
 * hosts across launches, so even the first request after a cold start skips TCP and TLS. It's
 * part of the system, nothing bundled; older phones keep using HttpURLConnection.
 */
@UnstableApi
@RequiresExtension(extension = Build.VERSION_CODES.S, version = 7)
final class HttpEngines {
	private static final String TAG = "TestTubePlayback";
	private static final String STORAGE_DIR = "http_engine";
	private static final long SERVER_INFO_BYTES = 1024L * 1024L;

	/** Callback thread. They only hand buffers over, one is plenty. */
	@NonNull
	private static final Executor CALLBACKS = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "testtube-http-engine");
		thread.setDaemon(true);
		return thread;
	});

	@Nullable
	private static HttpEngine engine;
	private static boolean failed;

	private HttpEngines() {
	}

	/**
	 * Shared engine, built on first ask, or null if this phone can't (never retried, falling back
	 * beats failing twice).
	 */
	@Nullable
	static synchronized HttpEngine get(@NonNull Context context) {
		if (engine != null || failed) return engine;
		try {
			Context app = context.getApplicationContext();
			HttpEngine.Builder builder = new HttpEngine.Builder(app)
							.setEnableQuic(true)
							.setEnableHttp2(true)
							.setEnableBrotli(true);
			File storage = new File(app.getNoBackupFilesDir(), STORAGE_DIR);
			if (storage.isDirectory() || storage.mkdirs()) {
				// remembers QUIC hosts but no responses, the player has its own cache
				builder.setStoragePath(storage.getAbsolutePath())
								.setEnableHttpCache(HttpEngine.Builder.HTTP_CACHE_DISK_NO_HTTP, SERVER_INFO_BYTES);
			} else {
				// still works, just forgets QUIC hosts
				builder.setEnableHttpCache(HttpEngine.Builder.HTTP_CACHE_DISABLED, 0);
			}
			engine = builder.build();
		} catch (RuntimeException | LinkageError e) {
			// a broken network module shouldn't take playback down
			failed = true;
			Log.w(TAG, "HttpEngine unavailable, using HttpURLConnection", e);
		}
		return engine;
	}

	/** YouTube stream URLs, quirks and all. */
	@Nullable
	static HttpDataSource.Factory youtube(@NonNull Context context,
	                                      @NonNull String userAgent,
	                                      int timeoutMs,
	                                      boolean rangeParameterEnabled,
	                                      boolean rnParameterEnabled) {
		HttpEngine shared = get(context);
		if (shared == null) return null;
		HttpEngineDataSource.Factory upstream = new HttpEngineDataSource.Factory(shared, CALLBACKS)
						.setConnectionTimeoutMs(timeoutMs)
						.setReadTimeoutMs(timeoutMs);
		return new YoutubeHttpEngineDataSource.Factory(upstream, userAgent, rangeParameterEnabled, rnParameterEnabled);
	}

	/** Plain GETs (live manifests and segments). */
	@Nullable
	static HttpDataSource.Factory plain(@NonNull Context context,
	                                    @NonNull String userAgent,
	                                    int connectTimeoutMs,
	                                    int readTimeoutMs) {
		HttpEngine shared = get(context);
		if (shared == null) return null;
		return new HttpEngineDataSource.Factory(shared, CALLBACKS)
						.setUserAgent(userAgent)
						.setConnectionTimeoutMs(connectTimeoutMs)
						.setReadTimeoutMs(readTimeoutMs);
	}
}
