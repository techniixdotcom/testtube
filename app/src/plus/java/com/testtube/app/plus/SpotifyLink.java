package com.testtube.app.plus;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Spotify link: open.spotify.com/track/… or a spotify.link short link. */
final class SpotifyLink {
	enum Type {
		TRACK, ALBUM, PLAYLIST, ARTIST, EPISODE, SHOW
	}

	record Ref(@NonNull Type type, @NonNull String id) {
		@NonNull
		String embedUrl() {
			return "https://open.spotify.com/embed/" + type.name().toLowerCase(Locale.ROOT) + "/" + id;
		}

		@NonNull
		String openUrl() {
			return "https://open.spotify.com/" + type.name().toLowerCase(Locale.ROOT) + "/" + id;
		}
	}

	/** open.spotify.com, optional /intl-xx/ part or embed form. */
	private static final Pattern OPEN = Pattern.compile(
					"https?://open\\.spotify\\.com/(?:intl-[A-Za-z0-9-]+/)?(?:embed/)?"
									+ "(track|album|playlist|artist|episode|show)/([A-Za-z0-9]{22})(?![A-Za-z0-9])",
					Pattern.CASE_INSENSITIVE);
	private static final Pattern SHORT = Pattern.compile(
					"https?://(?:spotify\\.link|spotify\\.app\\.link)/[A-Za-z0-9_-]+.*", Pattern.CASE_INSENSITIVE);

	private SpotifyLink() {
	}

	static boolean isSpotifyUrl(@Nullable String url) {
		return url != null && (parse(url) != null || isShortLink(url));
	}

	static boolean isShortLink(@NonNull String url) {
		return SHORT.matcher(url).matches();
	}

	/** Item a full link points to, or null (short links too). */
	@Nullable
	static Ref parse(@NonNull String url) {
		Matcher matcher = OPEN.matcher(url);
		if (!matcher.lookingAt()) return null;
		return new Ref(Type.valueOf(matcher.group(1).toUpperCase(Locale.ROOT)), matcher.group(2));
	}

	/** First full Spotify link in a page (where a short link lands). */
	@Nullable
	static Ref find(@NonNull String text) {
		Matcher matcher = OPEN.matcher(text);
		if (!matcher.find()) return null;
		return new Ref(Type.valueOf(matcher.group(1).toUpperCase(Locale.ROOT)), matcher.group(2));
	}
}
