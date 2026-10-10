package com.testtube.app.extractor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.localization.TimeAgoParser;
import org.schabi.newpipe.extractor.localization.TimeAgoPatternsManager;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

	/**
	 * Puts the newest uploads first, reading YouTube's dates ("3 days ago", "hace 3 días") in the
	 * content language. The order stays as it is when a date cannot be read, so a list is never
	 * scrambled.
	 */
	public static void sortNewestFirst(@NonNull List<FeedItem> items) {
		TimeAgoParser parser = TimeAgoPatternsManager.getTimeAgoParserFor(NewPipe.getPreferredLocalization());
		if (parser == null) return;
		Instant now = Instant.now();
		Map<FeedItem, Long> ages = new HashMap<>();
		for (FeedItem item : items) {
			long age = -1;
			try {
				if (item.published() != null) {
					age = Duration.between(parser.parse(item.published()).getInstant(), now).getSeconds();
				}
			} catch (Exception ignored) {
				// Not a date: an announced premiere, or a form the patterns do not know.
			}
			if (item.kind() == Kind.VIDEO && !item.live() && age < 0) return;
			// Live streams and non-video rows stay near the top, where YouTube put them.
			ages.put(item, Math.max(age, 0L));
		}
		items.sort(Comparator.comparingLong(ages::get));
	}
}
