package com.testtube.app.plus;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A SoundCloud link: track (soundcloud.com/artist/track), set or album
 * (soundcloud.com/artist/sets/name), artist (soundcloud.com/artist) or short link
 * (on.soundcloud.com/…). Private links keep their secret part (/s-…).
 */
final class SoundCloudLink {
	enum Kind {
		TRACK, SET, USER, SHORT
	}

	record Ref(@NonNull Kind kind, @NonNull String url, @NonNull String key) {
	}

	private static final Pattern HOST = Pattern.compile("https?://(?:www\\.|m\\.)?soundcloud\\.com(/[^?#]*)?(?:[?#].*)?",
					Pattern.CASE_INSENSITIVE);
	private static final Pattern SHORT = Pattern.compile("https?://on\\.soundcloud\\.com/([\\w-]+)/?(?:[?#].*)?",
					Pattern.CASE_INSENSITIVE);
	private static final Pattern SEGMENT = Pattern.compile("[\\w-]+");
	/** soundcloud.com/… pages that aren't artists. */
	private static final Set<String> NOT_USERS = Set.of("discover", "search", "stream", "upload", "you", "charts",
					"pages", "terms-of-use", "mobile", "settings", "messages", "notifications", "stations", "people",
					"tags", "popular", "jobs", "imprint", "creators", "pro", "go", "artists", "feed", "signin",
					"connect", "logout", "help", "for", "apps", "press", "community-guidelines", "next", "player");
	/** soundcloud.com/artist/… pages that aren't tracks. */
	private static final Set<String> NOT_TRACKS = Set.of("tracks", "albums", "sets", "reposts", "likes", "spotlight",
					"comments", "popular-tracks", "followers", "following", "toptracks", "recommended", "playlists");

	private SoundCloudLink() {
	}

	@NonNull
	private static String lower(@NonNull String text) {
		return text.toLowerCase(Locale.ROOT);
	}

	static boolean isSoundCloudUrl(@Nullable String url) {
		return url != null && parse(url) != null;
	}

	/** What the link is, or null if it's nothing we open. */
	@Nullable
	static Ref parse(@NonNull String url) {
		Matcher shortLink = SHORT.matcher(url);
		if (shortLink.matches()) {
			return new Ref(Kind.SHORT, url, "on/" + shortLink.group(1));
		}
		Matcher matcher = HOST.matcher(url);
		if (!matcher.matches() || matcher.group(1) == null) return null;
		// as written for SoundCloud (it keeps case), lower case for the id
		List<String> segments = new ArrayList<>(4);
		for (String part : matcher.group(1).split("/")) {
			if (part.isEmpty()) continue;
			if (!SEGMENT.matcher(part).matches()) return null;
			segments.add(part);
		}
		if (segments.isEmpty() || NOT_USERS.contains(lower(segments.get(0)))) return null;
		if (segments.size() == 1) {
			return new Ref(Kind.USER, "https://soundcloud.com/" + segments.get(0), lower(segments.get(0)));
		}
		String second = lower(segments.get(1));
		List<String> kept = new ArrayList<>(4);
		kept.add(segments.get(0));
		Kind kind;
		if ("sets".equals(second) || "albums".equals(second)) {
			if (segments.size() < 3) return null;
			kind = Kind.SET;
			kept.add("sets");
			kept.add(segments.get(2));
			if (segments.size() > 3 && lower(segments.get(3)).startsWith("s-")) kept.add(segments.get(3));
		} else {
			if (NOT_TRACKS.contains(second)) return null;
			kind = Kind.TRACK;
			kept.add(segments.get(1));
			if (segments.size() > 2 && lower(segments.get(2)).startsWith("s-")) kept.add(segments.get(2));
		}
		String path = String.join("/", kept);
		return new Ref(kind, "https://soundcloud.com/" + path, lower(path));
	}
}
