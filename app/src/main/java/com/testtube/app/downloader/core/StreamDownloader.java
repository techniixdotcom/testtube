package com.testtube.app.downloader.core;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.concurrent.CompletableFuture;

/** Multi-threaded stream downloads. */
public interface StreamDownloader {
	int DEFAULT_THREADS = 4;

	default CompletableFuture<File> download(@NonNull String url, @NonNull File output, @Nullable ProgressCallback callback) {
		return download(url, output, callback, DEFAULT_THREADS);
	}

	/** Downloads one stream over up to {@code threads} connections. */
	CompletableFuture<File> download(@NonNull String url, @NonNull File output, @Nullable ProgressCallback callback, int threads);

	void pause(@NonNull String url);

	void resume(@NonNull String url);

	void cancel(@NonNull String url);
}
