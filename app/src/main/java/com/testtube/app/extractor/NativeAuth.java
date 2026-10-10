package com.testtube.app.extractor;

import android.util.Log;
import android.webkit.CookieManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.testtube.app.Constant;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.CacheControl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Builds the signed-in request context from the cookies of the one-time Google sign-in, so the
 * app can call YouTube directly without a YouTube page open. The page configuration (account
 * id, visitor data) is read once from YouTube and cached.
 */
public final class NativeAuth {
	private static final String TAG = "NativeAuth";
	private static final String COOKIE_URL = "https://www.youtube.com";
	private static final String CONFIG_URL = "https://m.youtube.com/";
	private static final long CONFIG_TTL_MS = 6L * 60L * 60L * 1000L;
	private static final Pattern DATASYNC_ID = Pattern.compile("\"DATASYNC_ID\":\"([^\"]*)\"");
	private static final Pattern VISITOR_DATA = Pattern.compile("\"VISITOR_DATA\":\"([^\"]*)\"");
	private static final Pattern SESSION_INDEX = Pattern.compile("\"SESSION_INDEX\":\"?(\\d+)");
	private static final Pattern CLIENT_VERSION = Pattern.compile("\"INNERTUBE_CLIENT_VERSION\":\"([^\"]*)\"");

	@NonNull
	private final OkHttpClient http;
	@Nullable
	private volatile Config config;

	public NativeAuth(@NonNull OkHttpClient http) {
		this.http = http;
	}

	/**
	 * @param allowNetwork false on the main thread: only the cached configuration is used then
	 */
	@NonNull
	public AuthContext current(boolean allowNetwork) {
		String cookies = cookies();
		String sapisid = cookie(cookies, "SAPISID");
		if (sapisid == null) sapisid = cookie(cookies, "__Secure-3PAPISID");
		boolean loggedIn = sapisid != null;
		Config cfg = loggedIn ? config(sapisid, cookies, allowNetwork) : null;
		return new AuthContext(
						"native",
						cookies,
						cfg != null ? cfg.visitorData : null,
						cfg != null ? cfg.dataSyncId : null,
						cfg != null ? cfg.clientVersion : null,
						cfg != null ? cfg.sessionIndex : null,
						loggedIn,
						false,
						System.currentTimeMillis());
	}

	public boolean isSignedIn() {
		String cookies = cookies();
		return cookie(cookies, "SAPISID") != null || cookie(cookies, "__Secure-3PAPISID") != null;
	}

	@Nullable
	private static String cookies() {
		try {
			String cookies = CookieManager.getInstance().getCookie(COOKIE_URL);
			return cookies == null || cookies.isBlank() ? null : cookies;
		} catch (RuntimeException e) {
			// The WebView provider can be missing or updating.
			return null;
		}
	}

	@Nullable
	private Config config(@NonNull String sapisid, @NonNull String cookies, boolean allowNetwork) {
		Config cached = config;
		long now = System.currentTimeMillis();
		if (cached != null && cached.sapisid.equals(sapisid) && now - cached.fetchedAt < CONFIG_TTL_MS) {
			return cached;
		}
		if (!allowNetwork) {
			return cached != null && cached.sapisid.equals(sapisid) ? cached : null;
		}
		synchronized (this) {
			cached = config;
			if (cached != null && cached.sapisid.equals(sapisid) && now - cached.fetchedAt < CONFIG_TTL_MS) {
				return cached;
			}
			Config fresh = fetch(sapisid, cookies);
			if (fresh != null) config = fresh;
			return fresh != null ? fresh : cached;
		}
	}

	@Nullable
	private Config fetch(@NonNull String sapisid, @NonNull String cookies) {
		Request request = new Request.Builder()
						.url(CONFIG_URL)
						.header("User-Agent", Constant.USER_AGENT)
						.header("Cookie", cookies)
						.cacheControl(CacheControl.FORCE_NETWORK)
						.build();
		try (Response response = http.newCall(request).execute()) {
			ResponseBody body = response.body();
			if (!response.isSuccessful()) return null;
			String html = body.string();
			return new Config(sapisid,
							find(DATASYNC_ID, html),
							find(VISITOR_DATA, html),
							find(SESSION_INDEX, html),
							find(CLIENT_VERSION, html),
							System.currentTimeMillis());
		} catch (Exception e) {
			Log.w(TAG, "Unable to read YouTube configuration", e);
			return null;
		}
	}

	@Nullable
	private static String find(@NonNull Pattern pattern, @NonNull String html) {
		Matcher matcher = pattern.matcher(html);
		if (!matcher.find()) return null;
		String value = matcher.group(1);
		return value == null || value.isEmpty() ? null : value;
	}

	@Nullable
	private static String cookie(@Nullable String cookies, @NonNull String name) {
		if (cookies == null) return null;
		String prefix = name + "=";
		for (String part : cookies.split(";")) {
			String trimmed = part.trim();
			if (trimmed.startsWith(prefix) && trimmed.length() > prefix.length()) {
				return trimmed.substring(prefix.length());
			}
		}
		return null;
	}

	private record Config(@NonNull String sapisid,
	                      @Nullable String dataSyncId,
	                      @Nullable String visitorData,
	                      @Nullable String sessionIndex,
	                      @Nullable String clientVersion,
	                      long fetchedAt) {
	}
}
