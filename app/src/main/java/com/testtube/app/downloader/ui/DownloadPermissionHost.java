package com.testtube.app.downloader.ui;

import androidx.annotation.NonNull;

public interface DownloadPermissionHost {
	void requestDownloadStoragePermission(@NonNull Runnable onGranted);
}
