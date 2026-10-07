package com.testtube.app.util;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;

import androidx.annotation.NonNull;

/**
 * Utility methods for device-level operations like PIP mode, clipboard, and system settings.
 */
public final class DeviceUtils {

	/**
	 * Checks if the activity is currently in Picture-in-Picture mode.
	 */
	public static boolean isInPictureInPictureMode(@NonNull Activity activity) {
		return activity.isInPictureInPictureMode();
	}

	/**
	 * Copies text to the system clipboard.
	 */
	public static void copyToClipboard(@NonNull Context context, @NonNull String label, @NonNull String text) {
		ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
		if (clipboard != null) {
			ClipData clip = ClipData.newPlainText(label, text);
			clipboard.setPrimaryClip(clip);
		}
	}
}
