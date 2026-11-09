package com.testtube.app.plus;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;

/**
 * Finds a public Facebook video's file in its page, no account: the page has direct MP4 links
 * for the player (browser_native_hd_url, playable_url, …), same fields yt-dlp reads. Facebook
 * changes its pages now and then, which breaks this until updated. Private, friends-only and
 * group videos need a login and are never tried.
 */
final class FacebookReader {
	enum Problem {
		/** Not public, Facebook wants a login. */
		LOGIN,
		/** No video on the page, or it's gone. */
		NO_VIDEO,
		/** Facebook sent something we don't understand (anymore). */
		UNREADABLE
	}

	static final class FacebookException extends Exception {
		@NonNull
		final Problem problem;

		FacebookException(@NonNull Problem problem, @NonNull String message) {
			super(message);
			this.problem = problem;
		}
	}

	/** facebook.com and subdomains, fb.watch, fb.com. */
	private static final Pattern HOST = Pattern.compile("(?:[\\w-]+\\.)?facebook\\.com|fb\\.watch|(?:www\\.)?fb\\.com",
					Pattern.CASE_INSENSITIVE);
	/** Facebook paths that are videos (or share links to one). */
	private static final Pattern VIDEO_PATH = Pattern.compile(
					"/(?:watch(?:/live)?/?|video\\.php|video/video\\.php|video/embed|reel/\\d+|share/[vr]/[\\w-]+|"
									+ "[^/]+/videos/(?:[^/]+/)?(?:pfbid\\w+|\\d+)|plugins/video\\.php).*",
					Pattern.CASE_INSENSITIVE);
	/** Video id in a link: v=…, video_id=…, /reel/…, /videos/…. */
	private static final Pattern VIDEO_ID = Pattern.compile(
					"(?:[?&](?:v|video_id)=|/reel/|/videos/(?:[^/?#]+/)?)(\\d{5,})");
	/** Where a file link starts in the page data: key, then its value's opening quote. */
	private static final Pattern STREAM_KEY = Pattern.compile(
					"\"(browser_native_hd_url|playable_url_quality_hd|browser_native_sd_url|playable_url|progressive_url)\""
									+ "\\s*:\\s*\"");
	/** Longer than any real link; past this it's something else. */
	private static final int MAX_STREAM_LENGTH = 8_000;
	private static final Pattern QUALITY_AFTER = Pattern.compile("\"quality\"\\s*:\\s*\"(HD|SD)\"");
	private static final Pattern OWNER_NAME = Pattern.compile(
					"\"(?:owner|video_owner|owner_as_page)\"\\s*:\\s*\\{[^{}]{0,400}?\"name\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.){1,200})\"");
	private static final Pattern CHALLENGE = Pattern.compile("fetch\\s*\\(\\s*[\"'](/__rd_verify[^\"']+)[\"']");
	private static final Pattern TITLE_TAG = Pattern.compile("<title[^>]*>([^<]{1,500})</title>", Pattern.CASE_INSENSITIVE);
	private static final Pattern DURATION = Pattern.compile("\"playable_duration_in_ms\"\\s*:\\s*(\\d{1,10})");
	/** Allowed in an id from a path: letters, digits, dot, dash, underscore. */
	private static final Pattern NOT_ID = Pattern.compile("[^A-Za-z0-9._-]+");
	/** How close (in page chars) a file link has to be to the video id to count as its own. */
	private static final int SAME_VIDEO_WINDOW = 30_000;
	/** Facebook's CDN rate-limits browser agents on video downloads (found by yt-dlp). */
	static final String STREAM_USER_AGENT = "facebookexternalhit/1.1";

	private FacebookReader() {
	}

	/** Link is a Facebook video (we open nothing else from Facebook). */
	static boolean isVideoUrl(@Nullable String url) {
		if (url == null) return false;
		HttpUrl parsed = HttpUrl.parse(url);
		if (parsed == null || !HOST.matcher(parsed.host()).matches()) return false;
		if ("fb.watch".equalsIgnoreCase(parsed.host())) return parsed.encodedPath().length() > 1;
		String path = parsed.encodedPath();
		if (!VIDEO_PATH.matcher(path).matches()) return false;
		// /watch and video.php only with a video, not Watch's start page
		if ((path.startsWith("/watch") && !path.startsWith("/watch/live")) || path.contains("video.php")) {
			return parsed.queryParameter("v") != null || parsed.queryParameter("video_id") != null
							|| parsed.queryParameter("href") != null;
		}
		return true;
	}

	@WorkerThread
	@NonNull
	static FacebookVideo read(@NonNull OkHttpClient client, @NonNull String url, @NonNull String userAgent)
					throws IOException, FacebookException {
		String pageUrl = www(url);
		WebPage page = WebPage.get(client, pageUrl, userAgent);
		if (page.code == 403) {
			// "Client challenge": one request sets a cookie, then the page loads
			Matcher challenge = CHALLENGE.matcher(page.text);
			if (challenge.find()) {
				HttpUrl verify = page.url.resolve(challenge.group(1));
				if (verify != null) {
					WebPage.postEmpty(client, verify.toString(), userAgent);
					page = WebPage.get(client, pageUrl, userAgent);
				}
			}
		}
		String landed = page.url.encodedPath();
		if (landed.startsWith("/login") || landed.startsWith("/checkpoint")) {
			throw new FacebookException(Problem.LOGIN, "redirected to " + landed);
		}
		if (page.code == 404 || page.code == 410) throw new FacebookException(Problem.NO_VIDEO, "HTTP " + page.code);
		if (page.code < 200 || page.code >= 300) throw new IOException("HTTP " + page.code);

		String videoId = videoId(page.url.toString());
		if (videoId == null) videoId = videoId(url);
		String stream = pickStream(page.text, videoId);
		if (stream == null) {
			if (page.text.contains("id=\"login_form\"") || page.text.contains("id=\"loginbutton\"")
							|| page.text.contains(">You must log in to continue")) {
				throw new FacebookException(Problem.LOGIN, "login form, no video data");
			}
			throw new FacebookException(page.text.contains("og:video") ? Problem.UNREADABLE : Problem.NO_VIDEO,
							"no video file in the page");
		}
		return new FacebookVideo(url, stream, title(page), author(page.text), page.meta("og:image"),
						page.meta("og:description"), duration(page.text));
	}

	/** Video id from the link alone: its number if present, otherwise the path (share and fb.watch). */
	@Nullable
	static String idKey(@Nullable String url) {
		if (!isVideoUrl(url)) return null;
		String number = videoId(url);
		if (number != null) return number;
		HttpUrl parsed = HttpUrl.parse(url);
		if (parsed == null) return null;
		String host = parsed.host().toLowerCase(Locale.ROOT);
		String path = NOT_ID.matcher(parsed.encodedPath()).replaceAll("_");
		path = path.replaceAll("^_+|_+$", "");
		if (path.isEmpty()) return null;
		String key = ("fb.watch".equals(host) ? "w_" : "") + path;
		return key.length() > 120 ? key.substring(0, 120) : key;
	}

	private static long duration(@NonNull String html) {
		Matcher matcher = DURATION.matcher(html);
		if (!matcher.find()) return 0L;
		try {
			return Long.parseLong(matcher.group(1));
		} catch (NumberFormatException e) {
			return 0L;
		}
	}

	/** Same link on www.facebook.com, whose pages carry the player data. */
	@NonNull
	private static String www(@NonNull String url) {
		HttpUrl parsed = HttpUrl.parse(url);
		if (parsed == null) return url;
		String host = parsed.host().toLowerCase(Locale.ROOT);
		if (host.endsWith("facebook.com") && !host.equals("www.facebook.com")) {
			return parsed.newBuilder().host("www.facebook.com").build().toString();
		}
		if (host.equals("fb.com") || host.equals("www.fb.com")) {
			return parsed.newBuilder().host("www.facebook.com").build().toString();
		}
		return url;
	}

	@Nullable
	private static String videoId(@NonNull String url) {
		Matcher matcher = VIDEO_ID.matcher(url);
		return matcher.find() ? matcher.group(1) : null;
	}

	private record Candidate(int position, @NonNull String url, boolean hd) {
	}

	/**
	 * The video's file: links nearest its own id when the page has several videos (suggestions),
	 * HD before SD.
	 */
	@Nullable
	static String pickStream(@NonNull String html, @Nullable String videoId) {
		List<Candidate> candidates = new ArrayList<>();
		Matcher matcher = STREAM_KEY.matcher(html);
		while (matcher.find()) {
			int end = endOfJsonString(html, matcher.end());
			if (end < 0) continue;
			String decoded = WebPage.unescapeJson(html.substring(matcher.end(), end));
			if (decoded == null || !decoded.startsWith("https://") || decoded.contains(".mpd")) continue;
			String key = matcher.group(1);
			boolean hd = key.contains("hd");
			if (key.equals("progressive_url")) {
				Matcher quality = QUALITY_AFTER.matcher(html);
				quality.region(end, Math.min(html.length(), end + 300));
				hd = quality.find() && "HD".equals(quality.group(1));
			}
			candidates.add(new Candidate(matcher.start(), decoded, hd));
		}
		if (candidates.isEmpty()) return null;

		List<Integer> idPositions = new ArrayList<>();
		if (videoId != null) {
			for (String form : new String[]{"\"id\":\"" + videoId + "\"", "\"videoId\":\"" + videoId + "\"",
							"\"video_id\":\"" + videoId + "\""}) {
				int at = html.indexOf(form);
				while (at >= 0) {
					idPositions.add(at);
					at = html.indexOf(form, at + form.length());
				}
			}
		}
		// links that belong to the video: around its id, else the page's first
		int anchor = candidates.get(0).position();
		int bestDistance = Integer.MAX_VALUE;
		if (!idPositions.isEmpty()) {
			for (Candidate candidate : candidates) {
				for (int at : idPositions) {
					int distance = Math.abs(candidate.position() - at);
					if (distance < bestDistance) {
						bestDistance = distance;
						anchor = candidate.position();
					}
				}
			}
			if (bestDistance > SAME_VIDEO_WINDOW) anchor = candidates.get(0).position();
		}
		Candidate best = null;
		for (Candidate candidate : candidates) {
			if (Math.abs(candidate.position() - anchor) > SAME_VIDEO_WINDOW / 2) continue;
			if (best == null || candidate.hd() && !best.hd()) best = candidate;
		}
		return best != null ? best.url() : candidates.get(0).url();
	}

	/**
	 * End of the JSON string starting at {@code start} (just after its quote): index of the closing
	 * quote, or -1 if none within {@link #MAX_STREAM_LENGTH}. Scanned by hand, a regex would recurse
	 * once per char on long values.
	 */
	private static int endOfJsonString(@NonNull String text, int start) {
		int limit = Math.min(text.length(), start + MAX_STREAM_LENGTH);
		for (int i = start; i < limit; i++) {
			char c = text.charAt(i);
			if (c == '\\') {
				i++;
			} else if (c == '"') {
				return i;
			} else if (c == '\n' || c == '\r') {
				return -1;
			}
		}
		return -1;
	}

	@Nullable
	private static String title(@NonNull WebPage page) {
		String title = page.meta("og:title");
		if (title == null) {
			Matcher matcher = TITLE_TAG.matcher(page.text);
			if (matcher.find()) title = WebPage.unescapeHtml(matcher.group(1)).trim();
		}
		if (title == null) return null;
		if (title.endsWith(" | Facebook")) title = title.substring(0, title.length() - " | Facebook".length()).trim();
		if (title.equalsIgnoreCase("Facebook") || title.isEmpty()) return null;
		return title;
	}

	@Nullable
	private static String author(@NonNull String html) {
		Matcher matcher = OWNER_NAME.matcher(html);
		if (!matcher.find()) return null;
		String name = WebPage.unescapeJson(matcher.group(1));
		return name == null || name.isBlank() ? null : name.trim();
	}
}
