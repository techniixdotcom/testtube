package com.testtube.app.downloader.ui;

import android.os.Bundle;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.media3.common.util.UnstableApi;

import com.google.android.material.appbar.MaterialToolbar;
import com.testtube.app.R;
import com.testtube.app.util.PermissionUtils;

/**
 * The items of one downloaded playlist. All other downloads live in the Library's Downloads tab.
 */
@UnstableApi
public class DownloadActivity extends AppCompatActivity implements DownloadPermissionHost {
	public static final String EXTRA_PARENT_ID = "extra_parent_id";
	public static final String EXTRA_PARENT_TITLE = "extra_parent_title";
	private DownloadsList list;
	@Nullable
	private Runnable pendingPermissionAction;

	@Override
	protected void onCreate(@Nullable Bundle savedInstanceState) {
		EdgeToEdge.enable(this);
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_download);
		ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root), (v, insets) -> {
			Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
			v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
			return insets;
		});

		String title = getIntent().getStringExtra(EXTRA_PARENT_TITLE);
		MaterialToolbar toolbar = findViewById(R.id.toolbar);
		toolbar.setNavigationOnClickListener(v -> finish());
		toolbar.setTitle(title == null || title.isBlank() ? getString(R.string.playlist_download_title_placeholder) : title);
		list = new DownloadsList(this, findViewById(R.id.root), getIntent().getStringExtra(EXTRA_PARENT_ID), () -> {
		});
	}

	@Override
	protected void onStart() {
		super.onStart();
		list.start();
	}

	@Override
	protected void onStop() {
		super.onStop();
		list.stop();
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		list.release();
	}

	@Override
	public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
		super.onRequestPermissionsResult(requestCode, permissions, grantResults);
		if (requestCode != PermissionUtils.REQUEST_STORAGE_PERMISSION) return;
		Runnable action = pendingPermissionAction;
		pendingPermissionAction = null;
		if (action != null && PermissionUtils.allGranted(grantResults)) action.run();
	}

	@Override
	public void requestDownloadStoragePermission(@NonNull Runnable onGranted) {
		if (!PermissionUtils.needsLegacyStoragePermission() || PermissionUtils.hasDownloadStoragePermission(this)) {
			onGranted.run();
			return;
		}
		pendingPermissionAction = onGranted;
		ActivityCompat.requestPermissions(this, PermissionUtils.downloadStoragePermissions(),
						PermissionUtils.REQUEST_STORAGE_PERMISSION);
	}
}
