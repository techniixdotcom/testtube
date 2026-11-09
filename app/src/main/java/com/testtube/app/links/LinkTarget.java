package com.testtube.app.links;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** What a link from another service (Spotify etc.) can do once matched to YouTube. Main thread. */
public interface LinkTarget {
	/** Plays the YouTube video on the watch screen. */
	@MainThread
	void playVideo(@NonNull String videoId);

	/** Adds the YouTube video to the end of the queue. */
	@MainThread
	void queueVideo(@NonNull String videoId, @NonNull String title, @Nullable String author,
	                @Nullable String thumbnailUrl);

	/**
	 * Plays a link on the watch screen: YouTube, or another site's (Facebook, SoundCloud) in the
	 * same player.
	 */
	@MainThread
	void playUrl(@NonNull String url);

	/** Queues another site's video or song. */
	@MainThread
	void queueUrl(@NonNull String id, @NonNull String url, @NonNull String title, @Nullable String author,
	              @Nullable String thumbnailUrl);

	/** Shows YouTube search results. */
	@MainThread
	void search(@NonNull String query);

	/** Screen still alive; late work is dropped if not. */
	@MainThread
	boolean isAlive();
}
