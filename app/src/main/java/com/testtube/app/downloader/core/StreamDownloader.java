package com.testtube.app.downloader.core;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.concurrent.CompletableFuture;

/**
 * Download video/audio stream in multiple threads.
 */
public interface StreamDownloader {

	int DEFAULT_THREADS = 4;

	default CompletableFuture<File> download(@NonNull String url, @NonNull File output, @Nullable ProgressCallback callback) {
		return download(url, output, callback, DEFAULT_THREADS);
	}

	/**
	 * Downloads one stream using at most {@code threads} parallel connections for this file.
	 */
	CompletableFuture<File> download(@NonNull String url, @NonNull File output, @Nullable ProgressCallback callback, int threads);

	void pause(@NonNull String url);

	void resume(@NonNull String url);

	void cancel(@NonNull String url);

}
