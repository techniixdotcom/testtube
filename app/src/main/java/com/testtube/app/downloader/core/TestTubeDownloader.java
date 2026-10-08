package com.testtube.app.downloader.core;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public interface TestTubeDownloader {
	void setCallback(@NonNull String videoId, @Nullable DownloadTaskCallback callback);

	void download(@NonNull Task task);

	boolean pause(@NonNull String videoId);

	boolean resume(@NonNull String videoId);

	void cancel(@NonNull String videoId);
}
