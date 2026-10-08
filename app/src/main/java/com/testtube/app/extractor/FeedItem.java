package com.testtube.app.extractor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A row in a native list: video, channel or playlist.
 *
 * @param durationSeconds -1 if unknown
 * @param count           views (videos), video count (playlists) or subscribers (channels),
 *                        -1 if unknown
 */
public record FeedItem(@NonNull Kind kind,
                       @NonNull String url,
                       @Nullable String videoId,
                       @NonNull String title,
                       @Nullable String author,
                       @Nullable String authorUrl,
                       @Nullable String thumbnailUrl,
                       long durationSeconds,
                       long count,
                       @Nullable String published,
                       boolean live) {
	public enum Kind {
		VIDEO,
		CHANNEL,
		PLAYLIST
	}

	// 320x180: already 16:9 and about a third the size of the 480x360 one (which has black bars)
	@NonNull
	public static String thumbnailFor(@NonNull String videoId) {
		return "https://i.ytimg.com/vi/" + videoId + "/mqdefault.jpg";
	}

	private static final Pattern AGE = Pattern.compile(
					"(\\d+)\\s*(second|minute|hour|day|week|month|year)s?\\s+ago", Pattern.CASE_INSENSITIVE);

	/**
	 * Age in seconds, parsed from YouTube's "3 days ago" text.
	 *
	 * @return -1 if missing or not in that format
	 */
	public static long ageSeconds(@Nullable String published) {
		if (published == null) return -1;
		Matcher matcher = AGE.matcher(published);
		if (!matcher.find()) return -1;
		long amount = Long.parseLong(matcher.group(1));
		return amount * switch (matcher.group(2).toLowerCase(Locale.ROOT)) {
			case "second" -> 1L;
			case "minute" -> 60L;
			case "hour" -> 3_600L;
			case "day" -> 86_400L;
			case "week" -> 604_800L;
			case "month" -> 2_592_000L;
			default -> 31_536_000L;
		};
	}

	// Newest first. If any date can't be read the order is left alone, so languages we
	// can't parse don't get scrambled.
	public static void sortNewestFirst(@NonNull List<FeedItem> items) {
		for (FeedItem item : items) {
			if (item.kind() == Kind.VIDEO && !item.live() && ageSeconds(item.published()) < 0) return;
		}
		items.sort((a, b) -> Long.compare(ageOf(a), ageOf(b)));
	}

	private static long ageOf(@NonNull FeedItem item) {
		long age = ageSeconds(item.published());
		// live streams and non-video rows stay near the top where YouTube put them
		return age < 0 ? 0 : age;
	}
}
