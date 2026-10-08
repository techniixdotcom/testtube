package com.testtube.app.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One browser identity everywhere: pages, requests made for them, the player and the extractor
 * all use the WebView's Chrome version, in Chrome's reduced UA format (no device model).
 */
public final class UserAgents {
	private static final Pattern CHROME = Pattern.compile("Chrome/(\\d+)");
	private static final String FALLBACK_MAJOR = "140";

	private UserAgents() {
	}

	@NonNull
	public static String chromeMajor(@Nullable String webViewUserAgent) {
		if (webViewUserAgent == null) return FALLBACK_MAJOR;
		Matcher matcher = CHROME.matcher(webViewUserAgent);
		return matcher.find() ? matcher.group(1) : FALLBACK_MAJOR;
	}

	@NonNull
	public static String mobile(@NonNull String chromeMajor) {
		return "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/"
						+ chromeMajor + ".0.0.0 Mobile Safari/537.36";
	}

	@NonNull
	public static String desktop(@NonNull String chromeMajor) {
		return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/"
						+ chromeMajor + ".0.0.0 Safari/537.36";
	}
}
