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
 * A list row: a video, a channel or a playlist.
 *
 * @param durationSeconds -1 when unknown
 * @param count           views for videos, video count for playlists, subscribers for channels;
 *                        -1 when unknown
 * @param shortForm       a YouTube Short
 * @param authorAvatarUrl the uploader's profile image, null when the source has none
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

	/** A row whose source has no uploader image (it is looked up by {@link AvatarCache}). */
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

	/** The same row opening another url (a video inside a playlist, say). */
	@NonNull
	public FeedItem withUrl(@NonNull String newUrl) {
		return new FeedItem(kind, newUrl, videoId, title, author, authorUrl, thumbnailUrl, durationSeconds, count,
						published, live, shortForm, authorAvatarUrl);
	}

	/** The same row with another title and channel name (the original ones, say). */
	@NonNull
	public FeedItem withNames(@NonNull String newTitle, @Nullable String newAuthor) {
		return new FeedItem(kind, url, videoId, newTitle, newAuthor, authorUrl, thumbnailUrl, durationSeconds, count,
						published, live, shortForm, authorAvatarUrl);
	}

	/**
	 * 320x180 (16:9) thumbnail for list rows. About a third of the size of hqdefault, and without
	 * the black bars.
	 */
	@NonNull
	public static String thumbnailFor(@NonNull String videoId) {
		return "https://i.ytimg.com/vi/" + videoId + "/mqdefault.jpg";
	}

	/**
	 * Sorts by upload date, newest first. Live streams go on top. A row whose date can't be read
	 * stays right after the row it followed, and rows before the first date (upcoming premieres)
	 * stay on top.
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
					// Keeps the previous row's age, so the row doesn't move away from it
				}
			}
			ages.put(item, age);
			previous = age;
		}
		// Stable, so rows of the same age keep YouTube's order
		items.sort(Comparator.comparingLong(ages::get));
	}
}
