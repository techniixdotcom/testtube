package com.testtube.app.player.engine.datasource;

import static org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getVisionOsUserAgent;
import static org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.isVisionOsStreamingUrl;
import static org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.isWebStreamingUrl;

import android.net.Uri;
import android.webkit.CookieManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;

import com.google.common.net.HttpHeaders;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * How a request for a YouTube stream has to look, whichever HTTP stack sends it. googlevideo is
 * picky as hell: playback URLs are POSTed with a two-byte body, ranges go in the query, and the headers
 * have to match the client that asked for the player response, or it answers with a 403.
 */
final class YoutubeRequests {
	static final byte[] POST_BODY = new byte[]{0x78, 0};
	static final String POST_CONTENT_TYPE = "application/x-www-form-urlencoded";

	private YoutubeRequests() {
	}

	static boolean isVideoPlayback(@Nullable String path) {
		return path != null && path.startsWith("/videoplayback");
	}

	/**
	 * Adds the request number and, when ranges go in the query, the byte range to a playback URL.
	 *
	 * @param requestNumber the next request number, or null to leave it out
	 */
	@NonNull
	static String requestUrl(@NonNull String url,
	                         boolean playback,
	                         boolean rangeParameter,
	                         long position,
	                         long length,
	                         @Nullable LongSupplier requestNumber) {
		if (!playback) return url;
		String requestUrl = url;
		if (requestNumber != null && !requestUrl.contains("&rn=")) {
			requestUrl += "&rn=" + requestNumber.getAsLong();
		}
		if (rangeParameter && (position != 0 || length != C.LENGTH_UNSET)) {
			requestUrl += "&range=" + position + (length != C.LENGTH_UNSET ? "-" + (position + length - 1) : "-");
		}
		return requestUrl;
	}

	/**
	 * The headers that go on top of whatever the player asked for: cookies, the browser-ish
	 * headers web streams expect, and the user agent of the client the URL came from.
	 */
	@NonNull
	static Map<String, String> headers(@NonNull String requestUrl, @NonNull String userAgent) {
		Map<String, String> headers = new LinkedHashMap<>();

		// Cookies only go to YouTube's own video and site hosts.
		String host = Uri.parse(requestUrl).getHost();
		if (host != null && (host.endsWith(".googlevideo.com") || host.equals("youtube.com") || host.endsWith(".youtube.com"))) {
			String cookies = CookieManager.getInstance().getCookie(requestUrl);
			if (cookies != null && !cookies.isEmpty()) headers.put(HttpHeaders.COOKIE, cookies);
		}

		if (isWebStreamingUrl(requestUrl)) {
			headers.put(HttpHeaders.ORIGIN, "https://www.youtube.com");
			headers.put(HttpHeaders.REFERER, "https://www.youtube.com");
			headers.put(HttpHeaders.SEC_FETCH_DEST, "empty");
			headers.put(HttpHeaders.SEC_FETCH_MODE, "cors");
			headers.put(HttpHeaders.SEC_FETCH_SITE, "cross-site");
		}

		headers.put(HttpHeaders.ACCEPT, "*/*");

		// googlevideo checks that stream requests come from the same client that requested the
		// player response, so the matching client user agent must be sent.
		headers.put(HttpHeaders.USER_AGENT, isVisionOsStreamingUrl(requestUrl) ? getVisionOsUserAgent(null) : userAgent);
		return headers;
	}
}
