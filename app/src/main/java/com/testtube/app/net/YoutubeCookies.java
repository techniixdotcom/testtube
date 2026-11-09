package com.testtube.app.net;

import android.util.Log;
import android.webkit.CookieManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.testtube.app.Constant;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * YouTube cookies in the same store as the WebViews (sign-in, live chat), so our requests and
 * the pages are one visitor like a real browser. Cookies YouTube sets on our requests are kept
 * too; without them every request was a new visitor and refreshed sign-in cookies got lost.
 */
public final class YoutubeCookies {
	private static final String TAG = "YoutubeCookies";
	private static final String COOKIE_URL = "https://www.youtube.com";
	/** Never taken from responses: we send our own PREF (restricted mode). */
	private static final Set<String> NOT_STORED = Set.of("PREF");
	/** Signed-out visitor cookies; dropped to start over as someone new. */
	private static final List<String> VISITOR_COOKIES = List.of(
					"VISITOR_INFO1_LIVE", "VISITOR_PRIVACY_METADATA", "YSC", "__Secure-ROLLOUT_TOKEN",
					"GPS");

	private YoutubeCookies() {
	}

	public static boolean isYoutubeHost(@Nullable String host) {
		if (host == null) return false;
		String lower = host.toLowerCase(Locale.ROOT);
		return lower.equals("youtu.be")
						|| lower.equals(Constant.YOUTUBE_DOMAIN)
						|| lower.endsWith("." + Constant.YOUTUBE_DOMAIN);
	}

	@Nullable
	public static String hostOf(@NonNull String url) {
		try {
			return URI.create(url).getHost();
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	/** Stored cookies for a YouTube URL, or null. */
	@Nullable
	public static String forUrl(@NonNull String url) {
		if (!isYoutubeHost(hostOf(url))) return null;
		try {
			String cookies = CookieManager.getInstance().getCookie(url);
			return cookies == null || cookies.isBlank() ? null : cookies;
		} catch (RuntimeException e) {
			// WebView can be missing or mid-update
			return null;
		}
	}

	/** Keeps cookies a YouTube response set. */
	public static void store(@NonNull String url, @Nullable List<String> setCookieHeaders) {
		if (setCookieHeaders == null || setCookieHeaders.isEmpty() || !isYoutubeHost(hostOf(url))) return;
		try {
			CookieManager manager = CookieManager.getInstance();
			for (String header : setCookieHeaders) {
				if (header == null) continue;
				int equals = header.indexOf('=');
				if (equals <= 0 || NOT_STORED.contains(header.substring(0, equals).trim())) continue;
				manager.setCookie(url, header);
			}
		} catch (RuntimeException e) {
			Log.w(TAG, "could not keep cookies", e);
		}
	}

	/**
	 * Forgets the signed-out visitor so the next requests are someone new. Used after the bot
	 * check, which is tied to the visitor as well as the network (took way too long to figure that
	 * one out). Signed-in cookies are left alone.
	 */
	public static void startNewVisitor() {
		String current = forUrl(COOKIE_URL);
		if (current != null && (current.contains("SAPISID=") || current.contains("__Secure-3PAPISID="))) return;
		try {
			CookieManager manager = CookieManager.getInstance();
			for (String name : VISITOR_COOKIES) {
				// both the domain cookie and a host-only one, whichever YouTube set
				manager.setCookie(COOKIE_URL, name + "=; Domain=.youtube.com; Path=/; Max-Age=0");
				manager.setCookie(COOKIE_URL, name + "=; Path=/; Max-Age=0");
			}
			manager.flush();
		} catch (RuntimeException e) {
			Log.w(TAG, "could not reset the visitor", e);
		}
	}
}
