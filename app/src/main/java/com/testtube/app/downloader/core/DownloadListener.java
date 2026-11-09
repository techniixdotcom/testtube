package com.testtube.app.downloader.core;

import java.io.File;

/** One download task: progress, merge step, outcome. */
public interface DownloadListener {
	void onProgress(int progress, long downloadedBytes, long totalBytes);

	void onComplete(File file);

	void onError(Exception error);

	void onCancel();

	void onMerge();
}
