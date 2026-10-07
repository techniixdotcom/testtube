package com.testtube.app.extractor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One entry of a native list: a video, a channel or a playlist.
 *
 * @param durationSeconds -1 when unknown
 * @param count           views for videos, video count for playlists, subscribers for channels;
 *                        -1 when unknown
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

	/**
	 * The thumbnail for list rows: 320x180 and already 16:9, about a third of the data of the
	 * 480x360 one, which also carries black bars.
	 */
	@NonNull
	public static String thumbnailFor(@NonNull String videoId) {
		return "https://i.ytimg.com/vi/" + videoId + "/mqdefault.jpg";
	}

	private static final Pattern AGE = Pattern.compile(
					"(\\d+)\\s*(second|minute|hour|day|week|month|year)s?\\s+ago", Pattern.CASE_INSENSITIVE);

	/**
	 * How long ago a video was published, read from YouTube's text ("3 days ago"), in seconds.
	 *
	 * @return -1 when the text is missing or not in that form
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

	/**
	 * Puts the newest uploads first. The order stays as it is when a date cannot be read, so a
	 * language the dates cannot be parsed in is never scrambled.
	 */
	public static void sortNewestFirst(@NonNull List<FeedItem> items) {
		for (FeedItem item : items) {
			if (item.kind() == Kind.VIDEO && !item.live() && ageSeconds(item.published()) < 0) return;
		}
		items.sort((a, b) -> Long.compare(ageOf(a), ageOf(b)));
	}

	private static long ageOf(@NonNull FeedItem item) {
		long age = ageSeconds(item.published());
		// Live streams and non-video rows stay near the top, where YouTube put them.
		return age < 0 ? 0 : age;
	}
}
