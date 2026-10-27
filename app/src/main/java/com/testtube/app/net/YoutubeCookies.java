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
 * YouTube's cookies, kept in the same store as the WebViews (sign-in, live chat), so the app's own
 * requests and its web pages are one visitor, like a single browser. Cookies YouTube sets on the
 * app's requests are kept too, as a browser would; without them every request looked like a new
 * visitor and refreshed sign-in cookies were dropped.
 */
public final class YoutubeCookies {
	private static final String TAG = "YoutubeCookies";
	private static final String COOKIE_URL = "https://www.youtube.com";
	/**
	 * Never taken from responses: the app sends its own PREF (restricted mode), which must not be
	 * replaced by YouTube's.
	 */
	private static final Set<String> NOT_STORED = Set.of("PREF");
	/** The cookies that identify a signed-out visitor; dropped to start over as a new one. */
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

	/** The stored cookies for a YouTube URL, or null for other sites or when there are none. */
	@Nullable
	public static String forUrl(@NonNull String url) {
		if (!isYoutubeHost(hostOf(url))) return null;
		try {
			String cookies = CookieManager.getInstance().getCookie(url);
			return cookies == null || cookies.isBlank() ? null : cookies;
		} catch (RuntimeException e) {
			// The WebView provider can be missing or updating
			return null;
		}
	}

	/** Keeps the cookies a YouTube response set. */
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
	 * Forgets the signed-out visitor, so the next requests start as a new one. Used after YouTube's
	 * bot check, which is tied to the visitor as well as the network (took way too long to figure
	 * that one out). Signed-in cookies are never touched.
	 */
	public static void startNewVisitor() {
		String current = forUrl(COOKIE_URL);
		if (current != null && (current.contains("SAPISID=") || current.contains("__Secure-3PAPISID="))) return;
		try {
			CookieManager manager = CookieManager.getInstance();
			for (String name : VISITOR_COOKIES) {
				// Both the domain cookie and a host-only one, whichever YouTube set
				manager.setCookie(COOKIE_URL, name + "=; Domain=.youtube.com; Path=/; Max-Age=0");
				manager.setCookie(COOKIE_URL, name + "=; Path=/; Max-Age=0");
			}
			manager.flush();
		} catch (RuntimeException e) {
			Log.w(TAG, "could not reset the visitor", e);
		}
	}
}
