package com.testtube.app.extractor;

import java.util.Date;

public class VideoDetails {
	private String id;
	private String title;
	private String author;
	private String description;
	private Long duration;
	private String thumbnailUrl;
	private long likeCount;
	private Date uploadDate;
	private String uploaderUrl;
	private String uploaderAvatarUrl;
	private long viewCount;
	private boolean live;

	public VideoDetails() {
	}

	public VideoDetails(String id, String title, String author, String description, Long duration, String thumbnailUrl, long likeCount, Date uploadDate, String uploaderUrl, String uploaderAvatarUrl, long viewCount) {
		this.id = id;
		this.title = title;
		this.author = author;
		this.description = description;
		this.duration = duration;
		this.thumbnailUrl = thumbnailUrl;
		this.likeCount = likeCount;
		this.uploadDate = uploadDate;
		this.uploaderUrl = uploaderUrl;
		this.uploaderAvatarUrl = uploaderAvatarUrl;
		this.viewCount = viewCount;
	}

	public boolean isLive() {
		return live;
	}

	public void setLive(boolean live) {
		this.live = live;
	}

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public String getTitle() {
		return title;
	}

	public void setTitle(String title) {
		this.title = title;
	}

	public String getAuthor() {
		return author;
	}

	public void setAuthor(String author) {
		this.author = author;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public Long getDuration() {
		return duration;
	}

	public void setDuration(Long duration) {
		this.duration = duration;
	}

	public String getThumbnailUrl() {
		return thumbnailUrl;
	}

	public void setThumbnailUrl(String thumbnailUrl) {
		this.thumbnailUrl = thumbnailUrl;
	}

	public long getLikeCount() {
		return likeCount;
	}

	public Date getUploadDate() {
		return uploadDate;
	}

	public String getUploaderUrl() {
		return uploaderUrl;
	}

	public String getUploaderAvatarUrl() {
		return uploaderAvatarUrl;
	}

	public long getViewCount() {
		return viewCount;
	}
}
