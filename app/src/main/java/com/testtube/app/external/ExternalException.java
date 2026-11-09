package com.testtube.app.external;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

/** A link from another site that can't play, with the reason in the user's language. */
public final class ExternalException extends Exception {
	/** What to show. */
	@StringRes
	public final int message;
	/** YouTube video to play instead (SoundCloud preview-only song), or null. */
	@Nullable
	public final String youtubeVideoId;

	public ExternalException(@StringRes int message, @NonNull String detail) {
		this(message, detail, null);
	}

	public ExternalException(@StringRes int message, @NonNull String detail, @Nullable String youtubeVideoId) {
		super(detail);
		this.message = message;
		this.youtubeVideoId = youtubeVideoId;
	}
}
