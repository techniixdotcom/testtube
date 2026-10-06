package com.testtube.app.extractor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

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
}
