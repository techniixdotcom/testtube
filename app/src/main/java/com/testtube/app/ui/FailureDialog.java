package com.testtube.app.ui;

import android.app.Activity;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.testtube.app.R;
import com.testtube.app.net.Failure;
import com.testtube.app.util.DeviceUtils;

/**
 * Tells the person in plain words why a video didn't play and what they can do, instead of a
 * stack trace. The technical details stay one tap away for bug reports.
 */
public final class FailureDialog {
	private FailureDialog() {
	}

	/** @param retry what "Try again" does; no such button when null or when retrying can't help */
	public static void show(@NonNull Activity activity, @NonNull Failure failure, @NonNull Throwable error,
	                        @Nullable Runnable retry) {
		if (activity.isFinishing() || activity.isDestroyed() || DeviceUtils.isInPictureInPictureMode(activity)) {
			return;
		}
		String message = activity.getString(failure.message);
		MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity)
						.setTitle(R.string.failure_title)
						.setMessage(message)
						.setNeutralButton(R.string.failure_details, (dialog, which) -> ErrorDialog.show(activity, message, error));
		if (retry != null && failure.retryable) {
			builder.setPositiveButton(R.string.failure_retry, (dialog, which) -> retry.run())
							.setNegativeButton(R.string.close, null);
		} else {
			builder.setPositiveButton(R.string.close, null);
		}
		builder.show();
	}
}
