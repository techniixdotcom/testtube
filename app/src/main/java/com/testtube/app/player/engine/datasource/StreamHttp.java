package com.testtube.app.player.engine.datasource;

import android.content.Context;
import android.os.Build;
import android.os.ext.SdkExtensions;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.HttpDataSource;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Where the players get their HTTP from: HttpEngine (HTTP/3) when the phone has it, otherwise
 * HttpURLConnection. The engine is only built on first use, off the main thread, so creating a
 * player stays instant; {@link #warmUp(Context)} builds it ahead of the first video.
 */
@UnstableApi
public final class StreamHttp {
	private StreamHttp() {
	}

	/**
	 * Builds the engine now, so the first video doesn't wait for it. HttpEngine ships as a mainline
	 * module: Android 14, or Android 11+ with system updates. The same check is spelled out at
	 * every call site because that's the only form lint recognises.
	 */
	@WorkerThread
	public static void warmUp(@NonNull Context context) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
						&& SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7) {
			HttpEngines.get(context);
		}
	}

	/** YouTube's stream URLs (DASH and progressive). */
	@NonNull
	public static HttpDataSource.Factory youtube(@NonNull Context context,
	                                             @NonNull String userAgent,
	                                             int timeoutMs,
	                                             boolean rangeParameterEnabled,
	                                             boolean rnParameterEnabled) {
		Context app = context.getApplicationContext();
		HttpDataSource.Factory fallback = new YoutubeHttpDataSource.Factory(userAgent)
						.setConnectTimeoutMs(timeoutMs)
						.setReadTimeoutMs(timeoutMs)
						.setRangeParameterEnabled(rangeParameterEnabled)
						.setRnParameterEnabled(rnParameterEnabled);
		return new Lazy(fallback, () -> {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
							&& SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7) {
				return HttpEngines.youtube(app, userAgent, timeoutMs, rangeParameterEnabled, rnParameterEnabled);
			}
			return null;
		});
	}

	/** Plain GETs: live HLS and DASH. */
	@NonNull
	public static HttpDataSource.Factory plain(@NonNull Context context,
	                                           @NonNull String userAgent,
	                                           int connectTimeoutMs,
	                                           int readTimeoutMs) {
		Context app = context.getApplicationContext();
		HttpDataSource.Factory fallback = new DefaultHttpDataSource.Factory()
						.setUserAgent(userAgent)
						.setConnectTimeoutMs(connectTimeoutMs)
						.setReadTimeoutMs(readTimeoutMs);
		return new Lazy(fallback, () -> {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
							&& SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7) {
				return HttpEngines.plain(app, userAgent, connectTimeoutMs, readTimeoutMs);
			}
			return null;
		});
	}

	/**
	 * Picks the engine the first time a data source is needed (that's on a loader thread), and
	 * sticks with the answer.
	 */
	private static final class Lazy implements HttpDataSource.Factory {
		@NonNull
		private final HttpDataSource.Factory fallback;
		@NonNull
		private final Supplier<HttpDataSource.Factory> engine;
		@Nullable
		private HttpDataSource.Factory chosen;
		@Nullable
		private Map<String, String> defaultRequestProperties;

		Lazy(@NonNull HttpDataSource.Factory fallback, @NonNull Supplier<HttpDataSource.Factory> engine) {
			this.fallback = fallback;
			this.engine = engine;
		}

		@NonNull
		@Override
		public synchronized HttpDataSource.Factory setDefaultRequestProperties(@NonNull Map<String, String> properties) {
			defaultRequestProperties = properties;
			fallback.setDefaultRequestProperties(properties);
			if (chosen != null) chosen.setDefaultRequestProperties(properties);
			return this;
		}

		@NonNull
		@Override
		public HttpDataSource createDataSource() {
			return choose().createDataSource();
		}

		@NonNull
		private synchronized HttpDataSource.Factory choose() {
			if (chosen == null) {
				HttpDataSource.Factory engineFactory = engine.get();
				if (engineFactory != null && defaultRequestProperties != null) {
					engineFactory.setDefaultRequestProperties(defaultRequestProperties);
				}
				chosen = engineFactory != null ? engineFactory : fallback;
			}
			return chosen;
		}
	}
}
