package com.testtube.app.downloader.core;

import java.io.File;

/** What happens to one download task as a whole: progress, the merge step, and how it ended. */
public interface DownloadListener {
	void onProgress(int progress, long downloadedBytes, long totalBytes);

	void onComplete(File file);

	void onError(Exception error);

	void onCancel();

	void onMerge();
}
