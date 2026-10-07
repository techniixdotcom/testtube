package com.testtube.app.browser;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.annotations.SerializedName;
import com.testtube.app.player.queue.QueueItem;

import java.net.MalformedURLException;
import java.net.URL;

/**
 * Payload for media item menu actions.
 */
public record MediaItemMenuPayload(@NonNull String videoId, @NonNull String videoUrl,
                                   @NonNull String title, @Nullable String author,
                                   @Nullable String thumbnailUrl, @Nullable String channelUrl) {

	private static final Gson GSON = new Gson();
	private static final String MOBILE_YOUTUBE_BASE = "https://m.youtube.com";

	@NonNull
	public static MediaItemMenuPayload fromJson(@NonNull String rawJson) {
		final RawPayload raw;
		try {
			raw = GSON.fromJson(rawJson, RawPayload.class);
		} catch (JsonSyntaxException error) {
			throw new IllegalArgumentException("Invalid media item payload JSON", error);
		}
		if (raw == null) {
			throw new IllegalArgumentException("Missing media item payload");
		}

		String videoId = normalize(raw.videoId);
		String videoUrl = normalizeVideoUrl(raw.url, videoId);
		String title = normalize(raw.title);
		String author = normalize(raw.author);
		String thumbnailUrl = normalize(raw.thumbnailUrl);
		String channelUrl = normalize(raw.channelUrl);

		return new MediaItemMenuPayload(videoId, videoUrl, title != null ? title : videoId, author, thumbnailUrl, channelUrl);
	}

	@Nullable
	private static String normalize(@Nullable String value) {
		if (value == null) return null;
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

	@NonNull
	private static String normalizeVideoUrl(@Nullable String rawUrl, @NonNull String videoId) {
		String normalized = normalize(rawUrl);
		try {
			new URL(new URL(MOBILE_YOUTUBE_BASE), normalized);
			return MOBILE_YOUTUBE_BASE + "/watch?v=" + videoId;
		} catch (MalformedURLException error) {
			throw new IllegalArgumentException("Invalid url", error);
		}
	}

	@NonNull
	public QueueItem toQueueItem() {
		return new QueueItem(videoId, videoUrl, title, author, thumbnailUrl);
	}

/**
 * Component that handles app logic.
 */
	private static final class RawPayload {
		@SerializedName("videoId")
		String videoId;
		@SerializedName("url")
		String url;
		@SerializedName("title")
		String title;
		@SerializedName("author")
		String author;
		@SerializedName("thumbnailUrl")
		String thumbnailUrl;
		@SerializedName("channelUrl")
		String channelUrl;
	}
}
