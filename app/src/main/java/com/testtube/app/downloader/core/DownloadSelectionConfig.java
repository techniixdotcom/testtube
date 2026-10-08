package com.testtube.app.downloader.core;

import androidx.annotation.NonNull;

public record DownloadSelectionConfig(
				@NonNull PrimaryMediaMode primaryMediaMode,
				boolean subtitleEnabled,
				boolean thumbnailEnabled,
				int threadCount) {
	public DownloadSelectionConfig {
		threadCount = Math.max(1, threadCount);
	}

	public boolean hasAnyOutputEnabled() {
		return primaryMediaMode != PrimaryMediaMode.NONE || subtitleEnabled || thumbnailEnabled;
	}

	public enum PrimaryMediaMode {
		NONE,
		VIDEO,
		AUDIO
	}
}
