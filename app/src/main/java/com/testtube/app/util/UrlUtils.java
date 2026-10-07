package com.testtube.app.util;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.testtube.app.Constant;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Arrays;

/**
 * URL helpers for page classification and host checks.
 */
public final class UrlUtils {

	private static final Locale NORMAL_LOCALE = Locale.ROOT;

	private static final Set<String> ALLOWED_DOMAINS = Set.of(
					Constant.YOUTUBE_DOMAIN,
					"youtu.be",
					"youtube.googleapis.com",
					"googlevideo.com",
					"ytimg.com",
					"accounts.google",
					"accounts.google.com",
					"googleusercontent.com",
					"apis.google.com",
					"gstatic.com"
	);

	public static boolean isAllowedDomain(@Nullable Uri uri) {
		if (uri == null) return false;
		return isAllowedHost(uri.getHost());
	}

	/**
	 * True for https pages on youtube.com, the only pages trusted with the native bridge.
	 */
	public static boolean isTrustedPageUrl(@Nullable String url) {
		if (url == null || url.isEmpty()) return false;
		try {
			URI uri = URI.create(url);
			String host = uri.getHost();
			return "https".equalsIgnoreCase(uri.getScheme())
							&& host != null
							&& isYoutubeHost(host.toLowerCase(Locale.ROOT));
		} catch (IllegalArgumentException ignored) {
			return false;
		}
	}

	/**
	 * True for https links to youtube.com or youtu.be.
	 */
	public static boolean isYoutubeLink(@Nullable String url) {
		if (isTrustedPageUrl(url)) return true;
		try {
			URI uri = URI.create(url);
			String host = uri.getHost();
			return "https".equalsIgnoreCase(uri.getScheme())
							&& host != null
							&& "youtu.be".equalsIgnoreCase(host);
		} catch (IllegalArgumentException | NullPointerException ignored) {
			return false;
		}
	}

	public static boolean isAllowedUrl(@Nullable String url) {
		if (url == null || url.isEmpty()) return false;
		try {
			return isAllowedHost(URI.create(url).getHost());
		} catch (IllegalArgumentException ignored) {
			return false;
		}
	}

	@Nullable
	public static Uri externalUri(@Nullable String url) {
		if (url == null || url.isBlank()) return null;
		try {
			return externalUri(Uri.parse(url));
		} catch (RuntimeException ignored) {
			return null;
		}
	}

	@Nullable
	public static Uri externalUri(@Nullable Uri uri) {
		if (uri == null) return null;
		String host = uri.getHost();
		if (host == null) return null;
		String lowerHost = host.toLowerCase(NORMAL_LOCALE);
		if (!isYoutubeHost(lowerHost) || !"/redirect".equals(uri.getPath())) return null;

		String target = uri.getQueryParameter("q");
		if (target == null || target.isBlank()) {
			target = uri.getQueryParameter("url");
		}
		if (target == null || target.isBlank()) return null;

		Uri targetUri;
		try {
			targetUri = Uri.parse(target);
		} catch (RuntimeException ignored) {
			return null;
		}
		String scheme = targetUri.getScheme();
		if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) return null;
		return isAllowedHost(targetUri.getHost()) ? null : targetUri;
	}

	public static boolean isGoogleAccountsUrl(@Nullable String url) {
		if (url == null || url.isEmpty()) return false;
		try {
			String host = URI.create(url).getHost();
			return host != null && isGoogleAccountsHost(host.toLowerCase(NORMAL_LOCALE));
		} catch (IllegalArgumentException ignored) {
			return false;
		}
	}

	public static boolean isPlaylistFirstItemUrl(@Nullable String url) {
		if (url == null || url.isEmpty()) return false;
		try {
			String listId = getQueryParameter(url, "list");
			if (listId == null || listId.isBlank()) return false;
			String index = getQueryParameter(url, "index");
			return index == null || index.isBlank() || "1".equals(index);
		} catch (Exception ignored) {
			return false;
		}
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

	private static boolean isAllowedHost(@Nullable String host) {
		if (host == null) return false;
		String lowerHost = host.toLowerCase(NORMAL_LOCALE);
		if (isGoogleAccountsHost(lowerHost)) return true;
		return ALLOWED_DOMAINS.stream().anyMatch(domain ->
						lowerHost.equals(domain) || lowerHost.endsWith("." + domain));
	}

	private static boolean isYoutubeHost(@NonNull String lowerHost) {
		return lowerHost.equals(Constant.YOUTUBE_DOMAIN)
						|| lowerHost.endsWith("." + Constant.YOUTUBE_DOMAIN);
	}

	private static boolean isGoogleAccountsHost(@NonNull String lowerHost) {
		return lowerHost.equals("accounts.google")
						|| lowerHost.equals("accounts.google.com")
						|| lowerHost.startsWith("accounts.google.")
						|| lowerHost.equals("accounts.youtube.com");
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

	/**
	 * Channel pages the app shows natively: the channel itself or its videos.
	 */
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
		if (segments.size() == base) return true;
		if (segments.size() != base + 1) return false;
		String tab = segments.get(base).toLowerCase(NORMAL_LOCALE);
		return tab.equals("videos") || tab.equals("featured");
	}

	/**
	 * Playlist pages the app shows natively.
	 */
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

		String s0 = segments.get(0).toLowerCase(NORMAL_LOCALE);
		if (s0.startsWith("@")) return "@";

		return switch (s0) {
			case "shorts" -> Constant.PAGE_SHORTS;
			case "watch" -> Constant.PAGE_WATCH;
			case "channel" -> "channel";
			case "gaming" -> "gaming";
			case "select_site" -> "select_site";
			case "results" -> "searching";
			case "feed" -> (segments.size() > 1) ? switch (segments.get(1).toLowerCase(NORMAL_LOCALE)) {
				case "subscriptions" -> Constant.PAGE_SUBSCRIPTIONS;
				case "library" -> Constant.PAGE_LIBRARY;
				case "history" -> "history";
				case "channels" -> "channels";
				case "playlists" -> "playlists";
				default -> String.join("/", segments);
			} : String.join("/", segments);
			default -> String.join("/", segments);
		};
	}
}
