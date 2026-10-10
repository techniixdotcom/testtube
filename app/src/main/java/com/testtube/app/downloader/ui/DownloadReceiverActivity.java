package com.testtube.app.downloader.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.util.UnstableApi;

import com.testtube.app.ui.MainActivity;

/**
 * Component that handles app logic.
 */
@UnstableApi
public class DownloadReceiverActivity extends AppCompatActivity {
	@Override
	protected void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		Intent intent = getIntent();
		if (intent != null) {
			Intent forwardIntent = new Intent(this, MainActivity.class);
			forwardIntent.setAction("TRIGGER_DOWNLOAD_FROM_SHARE");
			// Only the shared link travels on; any other extras of the incoming intent are dropped.
			CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
			if (text != null && text.length() <= 4096) forwardIntent.putExtra(Intent.EXTRA_TEXT, text.toString());
			Uri data = intent.getData();
			if (data != null && ("https".equals(data.getScheme()) || "http".equals(data.getScheme()))) {
				forwardIntent.setData(data);
			}
			forwardIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
			startActivity(forwardIntent);
		}
		finish();
	}
}