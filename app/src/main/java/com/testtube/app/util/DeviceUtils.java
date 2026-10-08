package com.testtube.app.util;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;

import androidx.annotation.NonNull;

public final class DeviceUtils {
	public static boolean isInPictureInPictureMode(@NonNull Activity activity) {
		return activity.isInPictureInPictureMode();
	}

	public static void copyToClipboard(@NonNull Context context, @NonNull String label, @NonNull String text) {
		ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
		if (clipboard != null) {
			ClipData clip = ClipData.newPlainText(label, text);
			clipboard.setPrimaryClip(clip);
		}
	}
}
