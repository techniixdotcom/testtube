package com.testtube.app.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.testtube.app.Constant;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class UrlUtils {
	private static final Locale NORMAL_LOCALE = Locale.ROOT;

	/** https links to youtube.com (incl. subdomains) or youtu.be */
	public static boolean isYoutubeLink(@Nullable String url) {
		if (url == null || url.isEmpty()) return false;
		try {
			URI uri = URI.create(url);
			String host = uri.getHost();
			if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null) return false;
			String lowerHost = host.toLowerCase(NORMAL_LOCALE);
			return isYoutubeHost(lowerHost) || lowerHost.equals("youtu.be");
		} catch (IllegalArgumentException ignored) {
			return false;
		}
	}

	public static boolean isPlaylistFirstItemUrl(@Nullable String url) {
		if (url == null || url.isEmpty()) return false;
		String listId = getQueryParameter(url, "list");
		if (listId == null || listId.isBlank()) return false;
		String index = getQueryParameter(url, "index");
		return index == null || index.isBlank() || "1".equals(index);
	}

	@Nullable
	public static String getQueryParameter(@NonNull String url, @NonNull String key) {
		String query;
		try {
			query = URI.create(url).getRawQuery();
		} catch (IllegalArgumentException ignored) {
			return null;
		}
		if (query == null || query.isBlank()) return null;
		for (String pair : query.split("&")) {
			int sep = pair.indexOf('=');
			String name = sep >= 0 ? pair.substring(0, sep) : pair;
			if (!key.equals(name)) continue;
			return sep >= 0 ? pair.substring(sep + 1) : "";
		}
		return null;
	}

	private static boolean isYoutubeHost(@NonNull String lowerHost) {
		return lowerHost.equals(Constant.YOUTUBE_DOMAIN)
						|| lowerHost.endsWith("." + Constant.YOUTUBE_DOMAIN);
	}

	@NonNull
	public static String getPageClass(@Nullable String url) {
		if (url == null || url.isEmpty()) return "unknown";

		try {
			URI uri = URI.create(url);
			String host = uri.getHost();
			if (host == null) return "unknown";
			String path = uri.getPath();
			List<String> segments = path == null || path.isEmpty()
							? List.of()
							: Arrays.stream(path.split("/"))
							.filter(segment -> !segment.isEmpty())
							.toList();
			return getPageClassFromHost(host, segments);
		} catch (IllegalArgumentException ignored) {
			return "unknown";
		}
	}

	public static boolean isNativeChannelUrl(@Nullable String url) {
		List<String> segments = youtubePath(url);
		if (segments == null || segments.isEmpty()) return false;
		String first = segments.get(0).toLowerCase(NORMAL_LOCALE);
		int base;
		if (first.startsWith("@") && first.length() > 1) {
			base = 1;
		} else if ((first.equals("channel") || first.equals("c") || first.equals("user")) && segments.size() >= 2) {
			base = 2;
		} else {
			return false;
		}
		// every channel tab (videos, shorts, playlists, about...) opens the native channel page
		return segments.size() >= base;
	}

	public static boolean isNativePlaylistUrl(@Nullable String url) {
		List<String> segments = youtubePath(url);
		if (segments == null || segments.size() != 1 || !"playlist".equalsIgnoreCase(segments.get(0))) return false;
		String list = getQueryParameter(url, "list");
		return list != null && !list.isBlank();
	}

	@Nullable
	private static List<String> youtubePath(@Nullable String url) {
		if (url == null || url.isEmpty()) return null;
		try {
			URI uri = URI.create(url);
			String host = uri.getHost();
			if (host == null) return null;
			String lowerHost = host.toLowerCase(NORMAL_LOCALE);
			if (!lowerHost.equals(Constant.YOUTUBE_MOBILE_HOST) && !lowerHost.equals(Constant.YOUTUBE_DOMAIN)
							&& !lowerHost.equals("www." + Constant.YOUTUBE_DOMAIN)) {
				return null;
			}
			String path = uri.getPath();
			if (path == null || path.isEmpty()) return List.of();
			return Arrays.stream(path.split("/")).filter(segment -> !segment.isEmpty()).toList();
		} catch (IllegalArgumentException ignored) {
			return null;
		}
	}

	@NonNull
	static String getPageClassFromHost(@NonNull String host, @NonNull List<String> segments) {
		String lowerHost = host.toLowerCase(NORMAL_LOCALE);
		if (lowerHost.equals("youtu.be")) {
			return segments.isEmpty() ? "unknown" : Constant.PAGE_WATCH;
		}
		if (!lowerHost.equals(Constant.YOUTUBE_MOBILE_HOST) && !lowerHost.equals(Constant.YOUTUBE_DOMAIN))
			return "unknown";

		if (segments.isEmpty()) return Constant.PAGE_HOME;

		return switch (segments.get(0).toLowerCase(NORMAL_LOCALE)) {
			case "shorts", "watch", "live" -> Constant.PAGE_WATCH;
			case "results" -> "searching";
			case "feed" -> segments.size() > 1 ? switch (segments.get(1).toLowerCase(NORMAL_LOCALE)) {
				case "subscriptions" -> Constant.PAGE_SUBSCRIPTIONS;
				case "library" -> Constant.PAGE_LIBRARY;
				case "history" -> "history";
				default -> "other";
			} : "other";
			default -> "other";
		};
	}
}
