package com.testtube.app.player.queue;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public final class QueueItem {
	@Nullable
	private String videoId;
	@Nullable
	private String videoUrl;
	@Nullable
	private String title;
	@Nullable
	private String author;
	@Nullable
	private String thumbnailUrl;

	@NonNull
	public QueueItem copy() {
		return new QueueItem(videoId, videoUrl, title, author, thumbnailUrl);
	}

	public QueueItem() {
	}

	public QueueItem(@Nullable String videoId, @Nullable String videoUrl, @Nullable String title, @Nullable String author, @Nullable String thumbnailUrl) {
		this.videoId = videoId;
		this.videoUrl = videoUrl;
		this.title = title;
		this.author = author;
		this.thumbnailUrl = thumbnailUrl;
	}

	@Nullable
	public String getVideoId() {
		return videoId;
	}

	public void setVideoId(@Nullable String videoId) {
		this.videoId = videoId;
	}

	@Nullable
	public String getVideoUrl() {
		return videoUrl;
	}

	@Nullable
	public String getTitle() {
		return title;
	}

	public void setTitle(@Nullable String title) {
		this.title = title;
	}

	@Nullable
	public String getAuthor() {
		return author;
	}

	public void setAuthor(@Nullable String author) {
		this.author = author;
	}

	@Nullable
	public String getThumbnailUrl() {
		return thumbnailUrl;
	}

	public void setThumbnailUrl(@Nullable String thumbnailUrl) {
		this.thumbnailUrl = thumbnailUrl;
	}
}
