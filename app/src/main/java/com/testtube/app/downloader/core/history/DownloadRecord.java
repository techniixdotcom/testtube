package com.testtube.app.downloader.core.history;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Objects;

public class DownloadRecord {
	@NonNull
	private String taskId;
	@NonNull
	private String videoId;
	@NonNull
	private DownloadType type;
	@NonNull
	private DownloadStatus status;
	private int progress;
	@NonNull
	private String fileName;
	@NonNull
	private String outputPath;
	private long createdAt;
	private long updatedAt;
	@Nullable
	private String errorMessage;
	private long downloadedSize;
	private long totalSize;
	@Nullable
	private String parentId;
	@Nullable
	private String title;
	@Nullable
	private String thumbnailUrl;
	private int itemCount;
	private int doneCount;
	private int failedCount;
	private int runningCount;
	private boolean sealed;

	public DownloadRecord() {
	}

	public DownloadRecord(@NonNull String taskId, @NonNull String videoId, @NonNull DownloadType type, @NonNull DownloadStatus status, int progress, @NonNull String fileName, @NonNull String outputPath, long createdAt, long updatedAt, @Nullable String errorMessage, long downloadedSize, long totalSize, @Nullable String parentId, @Nullable String title, @Nullable String thumbnailUrl, int itemCount, int doneCount, int failedCount, int runningCount, boolean sealed) {
		this.taskId = taskId;
		this.videoId = videoId;
		this.type = type;
		this.status = status;
		this.progress = progress;
		this.fileName = fileName;
		this.outputPath = outputPath;
		this.createdAt = createdAt;
		this.updatedAt = updatedAt;
		this.errorMessage = errorMessage;
		this.downloadedSize = downloadedSize;
		this.totalSize = totalSize;
		this.parentId = parentId;
		this.title = title;
		this.thumbnailUrl = thumbnailUrl;
		this.itemCount = itemCount;
		this.doneCount = doneCount;
		this.failedCount = failedCount;
		this.runningCount = runningCount;
		this.sealed = sealed;
	}

	@NonNull
	public String getTaskId() {
		return taskId;
	}

	public void setTaskId(@NonNull String taskId) {
		this.taskId = taskId;
	}

	@NonNull
	public String getVideoId() {
		return videoId;
	}

	public void setVideoId(@NonNull String videoId) {
		this.videoId = videoId;
	}

	@NonNull
	public DownloadType getType() {
		return type;
	}

	public void setType(@NonNull DownloadType type) {
		this.type = type;
	}

	@NonNull
	public DownloadStatus getStatus() {
		return status;
	}

	public void setStatus(@NonNull DownloadStatus status) {
		this.status = status;
	}

	public int getProgress() {
		return progress;
	}

	public void setProgress(int progress) {
		this.progress = progress;
	}

	@NonNull
	public String getFileName() {
		return fileName;
	}

	public void setFileName(@NonNull String fileName) {
		this.fileName = fileName;
	}

	@NonNull
	public String getOutputPath() {
		return outputPath;
	}

	public void setOutputPath(@NonNull String outputPath) {
		this.outputPath = outputPath;
	}

	public long getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(long createdAt) {
		this.createdAt = createdAt;
	}

	public void setUpdatedAt(long updatedAt) {
		this.updatedAt = updatedAt;
	}

	public long getDownloadedSize() {
		return downloadedSize;
	}

	public void setDownloadedSize(long downloadedSize) {
		this.downloadedSize = downloadedSize;
	}

	public long getTotalSize() {
		return totalSize;
	}

	public void setTotalSize(long totalSize) {
		this.totalSize = totalSize;
	}

	@Nullable
	public String getParentId() {
		return parentId;
	}

	public void setParentId(@Nullable String parentId) {
		this.parentId = parentId;
	}

	@Nullable
	public String getTitle() {
		return title;
	}

	public void setTitle(@Nullable String title) {
		this.title = title;
	}

	@Nullable
	public String getThumbnailUrl() {
		return thumbnailUrl;
	}

	public void setThumbnailUrl(@Nullable String thumbnailUrl) {
		this.thumbnailUrl = thumbnailUrl;
	}

	public int getItemCount() {
		return itemCount;
	}

	public void setItemCount(int itemCount) {
		this.itemCount = itemCount;
	}

	public int getDoneCount() {
		return doneCount;
	}

	public void setDoneCount(int doneCount) {
		this.doneCount = doneCount;
	}

	public void setFailedCount(int failedCount) {
		this.failedCount = failedCount;
	}

	public void setRunningCount(int runningCount) {
		this.runningCount = runningCount;
	}

	public boolean isSealed() {
		return sealed;
	}

	public void setSealed(boolean sealed) {
		this.sealed = sealed;
	}

	@Override
	public boolean equals(Object other) {
		if (this == other) return true;
		if (!(other instanceof DownloadRecord o)) return false;
		return Objects.equals(taskId, o.taskId) && Objects.equals(videoId, o.videoId) && Objects.equals(type, o.type) && Objects.equals(status, o.status) && progress == o.progress && Objects.equals(fileName, o.fileName) && Objects.equals(outputPath, o.outputPath) && createdAt == o.createdAt && updatedAt == o.updatedAt && Objects.equals(errorMessage, o.errorMessage) && downloadedSize == o.downloadedSize && totalSize == o.totalSize && Objects.equals(parentId, o.parentId) && Objects.equals(title, o.title) && Objects.equals(thumbnailUrl, o.thumbnailUrl) && itemCount == o.itemCount && doneCount == o.doneCount && failedCount == o.failedCount && runningCount == o.runningCount && sealed == o.sealed;
	}

	@Override
	public int hashCode() {
		return Objects.hash(taskId, videoId, type, status, progress, fileName, outputPath, createdAt, updatedAt, errorMessage, downloadedSize, totalSize, parentId, title, thumbnailUrl, itemCount, doneCount, failedCount, runningCount, sealed);
	}
}
