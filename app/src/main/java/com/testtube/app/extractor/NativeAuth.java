package com.testtube.app.extractor;

import android.util.Log;
import android.webkit.CookieManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tencent.mmkv.MMKV;
import com.testtube.app.Constant;

import okhttp3.CacheControl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Signed-in request context from the Google cookies, so InnerTube works without a YouTube page.
 * Page config (account id, visitor data) is fetched once and cached.
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

	/** Kept between launches: the config (so a signed-in start skips fetching m.youtube.com) and
	 * whether the last check found an account (so the first screen can pick its saved list without
	 * starting WebView). Only YouTube's ids and a hash of the SAPISID, never a cookie. */
	private static final String STORE_ID = "testtube_auth";
	private static final String KEY_SIGNED_IN = "signed_in";
	private static final String KEY_SAPISID_HASH = "config_sapisid_sha256";
	private static final String KEY_DATASYNC_ID = "config_datasync_id";
	private static final String KEY_VISITOR_DATA = "config_visitor_data";
	private static final String KEY_SESSION_INDEX = "config_session_index";
	private static final String KEY_CLIENT_VERSION = "config_client_version";
	private static final String KEY_FETCHED_AT = "config_fetched_at";

	@NonNull
	private final OkHttpClient http;
	@Nullable
	private volatile Config config;
	@Nullable
	private volatile Boolean signedIn;

	public NativeAuth(@NonNull OkHttpClient http) {
		this.http = http;
	}

	/** @param allowNetwork false on the main thread (cached config only) */
	@NonNull
	public AuthContext current(boolean allowNetwork) {
		String cookies = cookies();
		String sapisid = cookie(cookies, "SAPISID");
		if (sapisid == null) sapisid = cookie(cookies, "__Secure-3PAPISID");
		boolean loggedIn = sapisid != null;
		remember(loggedIn);
		Config cfg = loggedIn ? config(sapisid, cookies, allowNetwork) : null;
		return new AuthContext(
						cookies,
						cfg != null ? cfg.visitorData : null,
						cfg != null ? cfg.dataSyncId : null,
						cfg != null ? cfg.clientVersion : null,
						cfg != null ? cfg.sessionIndex : null,
						loggedIn);
	}

	public boolean isSignedIn() {
		String cookies = cookies();
		boolean loggedIn = cookie(cookies, "SAPISID") != null || cookie(cookies, "__Secure-3PAPISID") != null;
		remember(loggedIn);
		return loggedIn;
	}

	/**
	 * Whether the last check (this launch or an earlier one) found an account. Doesn't touch
	 * WebView, so it's free on the main thread at startup; {@link #isSignedIn()} is the real answer.
	 */
	public boolean wasSignedIn() {
		Boolean known = signedIn;
		if (known != null) return known;
		try {
			return store().decodeBool(KEY_SIGNED_IN, false);
		} catch (RuntimeException e) {
			return false;
		}
	}

	private void remember(boolean loggedIn) {
		Boolean known = signedIn;
		if (known != null && known == loggedIn) return;
		signedIn = loggedIn;
		try {
			store().encode(KEY_SIGNED_IN, loggedIn);
		} catch (RuntimeException ignored) {
			// only a startup hint
		}
	}

	@NonNull
	private static MMKV store() {
		return MMKV.mmkvWithID(STORE_ID);
	}

	@Nullable
	private static String cookies() {
		try {
			String cookies = CookieManager.getInstance().getCookie(COOKIE_URL);
			return cookies == null || cookies.isBlank() ? null : cookies;
		} catch (RuntimeException e) {
			// WebView can be missing or mid-update
			return null;
		}
	}

	@Nullable
	private Config config(@NonNull String sapisid, @NonNull String cookies, boolean allowNetwork) {
		Config cached = config;
		if (cached == null || !cached.sapisid.equals(sapisid)) {
			// first use this launch: the one kept from the last launch, if it's for this account
			Config stored = loadStored(sapisid);
			if (stored != null) config = cached = stored;
		}
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
			if (fresh != null) {
				config = fresh;
				saveStored(fresh);
			}
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
	private static Config loadStored(@NonNull String sapisid) {
		try {
			MMKV store = store();
			String hash = sha256(sapisid);
			if (hash == null || !hash.equals(store.decodeString(KEY_SAPISID_HASH, null))) return null;
			long fetchedAt = store.decodeLong(KEY_FETCHED_AT, 0L);
			if (fetchedAt <= 0L) return null;
			return new Config(sapisid,
							store.decodeString(KEY_DATASYNC_ID, null),
							store.decodeString(KEY_VISITOR_DATA, null),
							store.decodeString(KEY_SESSION_INDEX, null),
							store.decodeString(KEY_CLIENT_VERSION, null),
							fetchedAt);
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static void saveStored(@NonNull Config config) {
		try {
			String hash = sha256(config.sapisid);
			if (hash == null) return;
			MMKV store = store();
			store.encode(KEY_SAPISID_HASH, hash);
			putOrRemove(store, KEY_DATASYNC_ID, config.dataSyncId);
			putOrRemove(store, KEY_VISITOR_DATA, config.visitorData);
			putOrRemove(store, KEY_SESSION_INDEX, config.sessionIndex);
			putOrRemove(store, KEY_CLIENT_VERSION, config.clientVersion);
			store.encode(KEY_FETCHED_AT, config.fetchedAt);
		} catch (RuntimeException ignored) {
			// fetched again next launch
		}
	}

	private static void putOrRemove(@NonNull MMKV store, @NonNull String key, @Nullable String value) {
		if (value == null) store.removeValueForKey(key);
		else store.encode(key, value);
	}

	@Nullable
	private static String sha256(@NonNull String value) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
			StringBuilder hex = new StringBuilder(digest.length * 2);
			for (byte b : digest) hex.append(String.format("%02x", b));
			return hex.toString();
		} catch (NoSuchAlgorithmException e) {
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
	/** One cookie from a Cookie header, or null if missing or empty. */
	static String cookie(@Nullable String cookies, @NonNull String name) {
		if (cookies == null) return null;
		String prefix = name + "=";
		for (String part : cookies.split(";")) {
			String trimmed = part.trim();
			if (trimmed.startsWith(prefix)) {
				String value = trimmed.substring(prefix.length());
				return value.isBlank() ? null : value;
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
