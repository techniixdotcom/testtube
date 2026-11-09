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
 * A list row: video, channel or playlist.
 * @param durationSeconds -1 if unknown
 * @param count           views for videos, video count for playlists, subs for channels; -1 if unknown
 * @param shortForm       a Short
 * @param authorAvatarUrl uploader avatar, null if the source has none
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
                       boolean live,
                       boolean shortForm,
                       @Nullable String authorAvatarUrl) {
	public enum Kind {
		VIDEO,
		CHANNEL,
		PLAYLIST
	}

	/** Row without an uploader avatar ({@link AvatarCache} looks it up). */
	public FeedItem(@NonNull Kind kind,
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
		this(kind, url, videoId, title, author, authorUrl, thumbnailUrl, durationSeconds, count, published,
						live, false, null);
	}

	/** Same row, different url (a video inside a playlist, say). */
	@NonNull
	public FeedItem withUrl(@NonNull String newUrl) {
		return new FeedItem(kind, newUrl, videoId, title, author, authorUrl, thumbnailUrl, durationSeconds, count,
						published, live, shortForm, authorAvatarUrl);
	}

	/** Same row with another title and channel (the originals, say). */
	@NonNull
	public FeedItem withNames(@NonNull String newTitle, @Nullable String newAuthor) {
		return new FeedItem(kind, url, videoId, newTitle, newAuthor, authorUrl, thumbnailUrl, durationSeconds, count,
						published, live, shortForm, authorAvatarUrl);
	}

	/** 320x180 thumbnail for rows. A third of hqdefault's size and no black bars. */
	@NonNull
	public static String thumbnailFor(@NonNull String videoId) {
		return "https://i.ytimg.com/vi/" + videoId + "/mqdefault.jpg";
	}

	/**
	 * Newest first, live on top. Undated rows stay right after the row they followed, rows before
	 * the first date (upcoming premieres) stay on top.
	 */
	public static void sortByUploadDate(@NonNull List<FeedItem> items) {
		TimeAgoParser parser = TimeAgoPatternsManager.getTimeAgoParserFor(NewPipe.getPreferredLocalization());
		if (parser == null || items.size() < 2) return;
		Instant now = Instant.now();
		Map<FeedItem, Long> ages = new HashMap<>();
		long previous = -1;
		for (FeedItem item : items) {
			long age = item.live() ? -1 : previous;
			if (!item.live() && item.published() != null) {
				try {
					age = Duration.between(parser.parse(item.published()).getInstant(), now).getSeconds();
				} catch (Exception ignored) {
					// keep the previous row's age so it doesn't wander off
				}
			}
			ages.put(item, age);
			previous = age;
		}
		// stable, same age keeps YouTube's order
		items.sort(Comparator.comparingLong(ages::get));
	}
}
