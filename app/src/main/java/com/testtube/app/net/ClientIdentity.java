package com.testtube.app.net;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.testtube.app.Constant;

import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.localization.Localization;

import java.util.Locale;

/**
 * The browser our requests pretend to be, matching what's inside them: WEB client and
 * www.youtube.com are desktop Chrome, m.youtube.com mobile Chrome, both with the real WebView
 * version, language follows content language. Mixing them is an easy tell.
 */
public final class ClientIdentity {
	private static final String MOBILE_HOST = Constant.YOUTUBE_MOBILE_HOST;
	private static final String GOOGLEAPIS_HOST = "youtubei.googleapis.com";

	private ClientIdentity() {
	}

	/** YouTube's sites and API, paced and with the browser identity. */
	public static boolean isYoutubeApiOrSite(@Nullable String host) {
		return YoutubeCookies.isYoutubeHost(host) || GOOGLEAPIS_HOST.equalsIgnoreCase(host);
	}

	@NonNull
	public static String userAgentFor(@Nullable String host) {
		if (host != null && host.equalsIgnoreCase(MOBILE_HOST)) return Constant.USER_AGENT;
		if (isYoutubeApiOrSite(host)) return Constant.DESKTOP_USER_AGENT;
		return Constant.USER_AGENT;
	}

	/** Content language with English fallback, like a browser. */
	@NonNull
	public static String acceptLanguage() {
		Localization localization = NewPipe.getPreferredLocalization();
		String language = localization.getLanguageCode();
		String country = localization.getCountryCode();
		String tag = country == null || country.isEmpty() ? language : language + "-" + country;
		if (language.toLowerCase(Locale.ROOT).startsWith("en")) {
			return tag.equals("en") ? "en-US,en;q=0.9" : tag + ",en;q=0.9";
		}
		return tag.equals(language)
						? language + ",en;q=0.8"
						: tag + "," + language + ";q=0.9,en;q=0.8";
	}
}
