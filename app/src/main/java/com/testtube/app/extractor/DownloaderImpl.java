package com.testtube.app.extractor;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.testtube.app.net.ClientIdentity;
import com.testtube.app.net.RequestPacer;
import com.testtube.app.net.YoutubeCookies;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public final class DownloaderImpl extends Downloader {
	private static final String YOUTUBE_RESTRICTED_MODE_COOKIE = "PREF=f2=8000000";

	private final OkHttpClient client;
	private final ExtractionSessionScope scope;
	/** Sees every response body, e.g. to learn channel ids for the AI channel list. */
	@Nullable
	private volatile Consumer<String> responseListener;

	public DownloaderImpl(OkHttpClient client,
	                      ExtractionSessionScope scope) {
		this.client = client.newBuilder()
						.callTimeout(0L, TimeUnit.MILLISECONDS)
						.connectTimeout(20L, TimeUnit.SECONDS)
						.writeTimeout(30L, TimeUnit.SECONDS)
						.readTimeout(45L, TimeUnit.SECONDS)
						.build();
		this.scope = scope;
	}

	public void setResponseListener(@Nullable Consumer<String> listener) {
		this.responseListener = listener;
	}

	@NonNull
	<T> T withExtractionSession(@NonNull StreamInfoSupplier<T> supplier,
	                            @Nullable ExtractionSession session) throws ExtractionException, IOException {
		ExtractionSession previous = scope.get();
		scope.set(session);
		try {
			return supplier.get();
		} finally {
			scope.set(previous);
		}
	}

	@Override
	public org.schabi.newpipe.extractor.downloader.Response execute(@NonNull org.schabi.newpipe.extractor.downloader.Request request) throws IOException, ReCaptchaException {
		String httpMethod = request.httpMethod() != null ? request.httpMethod() : "GET";
		String url = request.url();
		final Map<String, List<String>> headers = request.headers();
		byte[] dataToSend = request.dataToSend();
		String host = YoutubeCookies.hostOf(url);
		boolean youtube = ClientIdentity.isYoutubeApiOrSite(host);
		// The app clients (VISIONOS) bring their own identity: no browser cookies, no browser agent
		boolean appClient = isAppClientRequest(headers);

		RequestBody requestBody = null;
		if (dataToSend != null) requestBody = RequestBody.create(dataToSend);

		final Request.Builder builder = new Request.Builder().url(url).method(httpMethod, requestBody)
						.header("User-Agent", ClientIdentity.userAgentFor(host));
		if (youtube && !appClient && !hasHeader(headers, "Accept-Language")) {
			builder.header("Accept-Language", ClientIdentity.acceptLanguage());
		}
		ExtractionSession session = scope.get();
		// VISIONOS is an anonymous client. With account cookies or authorization YouTube
		// rejects or binds the stream URLs.
		AuthContext auth = session != null && !appClient ? session.getAuth() : null;
		String storedCookies = auth != null && auth.cookies() != null ? auth.cookies()
						: appClient ? null : YoutubeCookies.forUrl(url);
		String mergedCookies = mergeCookies(isYoutubeHost(host) ? storedCookies : null, null);

		// Override with headers from request
		if (headers != null) {
			for (final Map.Entry<String, List<String>> entry : headers.entrySet()) {
				String headerName = entry.getKey();
				if ("cookie".equalsIgnoreCase(headerName)) {
					// The request's own cookies (e.g. consent) win over stored ones of the same name
					mergedCookies = mergeCookies(mergedCookies, String.join("; ", entry.getValue()));
					continue;
				}
				builder.removeHeader(headerName);
				for (String value : entry.getValue()) {
					builder.addHeader(headerName, value);
				}
			}
		}
		String restricted = getRestrictedModeCookie(url, mergedCookies);
		if (restricted != null) mergedCookies = mergeCookies(mergedCookies, restricted);
		YoutubeAuth.Result authHeaders = YoutubeAuth.headers(url, auth, System.currentTimeMillis());
		for (Map.Entry<String, String> entry : authHeaders.headers().entrySet()) {
			if (hasHeader(headers, entry.getKey())) {
				continue;
			}
			builder.header(entry.getKey(), entry.getValue());
		}
		if (!mergedCookies.isEmpty()) {
			builder.header("Cookie", mergedCookies);
		}

		BooleanSupplier cancelled = session != null ? session::isCancelled : null;
		RequestPacer.Slot slot = youtube
						? RequestPacer.acquire(cancelled, session != null && session.isBackground())
						: null;
		Call call = client.newCall(builder.build());
		if (session != null) {
			session.register(call::cancel);
		}

		try (Response response = call.execute()) {
			if (youtube && !appClient) YoutubeCookies.store(url, response.headers("Set-Cookie"));
			if (response.code() == 429) {
				RequestPacer.onSlowDown(retryAfterMs(response.header("Retry-After")));
				throw new ReCaptchaException("ReCaptcha Challenge requested", url);
			}

			int responseCode = response.code();
			String responseMessage = response.message();
			final Map<String, List<String>> responseHeaders = response.headers().toMultimap();
			ResponseBody responseBody = response.body();
			String responseBodyString = responseBody.string();
			Consumer<String> listener = responseListener;
			if (listener != null) {
				try {
					listener.accept(responseBodyString);
				} catch (RuntimeException e) {
					// A listener must never break extraction
					Log.w("DownloaderImpl", "response listener failed", e);
				}
			}

			return new org.schabi.newpipe.extractor.downloader.Response(responseCode, responseMessage, responseHeaders, responseBodyString, url);
		} catch (IOException e) {
			if (session != null && session.isCancelled()) {
				InterruptedIOException interrupted = new InterruptedIOException("Extraction canceled");
				interrupted.initCause(e);
				throw interrupted;
			}
			throw e;
		} finally {
			if (slot != null) slot.close();
		}
	}

	/** Retry-After in seconds (the form YouTube uses), as milliseconds; 0 when absent. */
	private static long retryAfterMs(@Nullable String retryAfter) {
		if (retryAfter == null) return 0L;
		try {
			return Math.min(10L * 60L, Math.max(0L, Long.parseLong(retryAfter.trim()))) * 1000L;
		} catch (NumberFormatException e) {
			return 0L;
		}
	}

	/**
	 * Joins two cookie headers. A cookie in {@code overrides} replaces one of the same name in
	 * {@code base}; the order is kept otherwise.
	 */
	@NonNull
	static String mergeCookies(@Nullable String base, @Nullable String overrides) {
		Map<String, String> byName = new LinkedHashMap<>();
		for (String header : new String[]{base, overrides}) {
			if (header == null) continue;
			for (String part : header.split(";")) {
				String cookie = part.trim();
				if (cookie.isEmpty()) continue;
				int equals = cookie.indexOf('=');
				String name = equals < 0 ? cookie : cookie.substring(0, equals).trim();
				byName.remove(name);
				byName.put(name, cookie);
			}
		}
		return String.join("; ", byName.values());
	}

	@Nullable
	private String getRestrictedModeCookie(@NonNull String url,
	                                       @Nullable String cookies) {
		if (!isYoutubeHost(YoutubeCookies.hostOf(url))) return null;
		if (cookies != null && cookies.contains("PREF=")) return null;
		return YOUTUBE_RESTRICTED_MODE_COOKIE;
	}

	private static boolean isYoutubeHost(@Nullable String host) {
		return YoutubeCookies.isYoutubeHost(host);
	}

	private boolean isAppClientRequest(@Nullable Map<String, List<String>> headers) {
		if (headers == null) {
			return false;
		}
		for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
			if (!"user-agent".equalsIgnoreCase(entry.getKey()) || entry.getValue() == null) {
				continue;
			}
			for (String value : entry.getValue()) {
				if (value != null && value.startsWith("com.google.")) {
					return true;
				}
			}
		}
		return false;
	}

	private boolean hasHeader(@Nullable Map<String, List<String>> headers,
	                          @NonNull String name) {
		if (headers == null) {
			return false;
		}
		for (String key : headers.keySet()) {
			if (name.equalsIgnoreCase(key)) {
				return true;
			}
		}
		return false;
	}

	@FunctionalInterface
	interface StreamInfoSupplier<T> {
		T get() throws ExtractionException, IOException;
	}
}
