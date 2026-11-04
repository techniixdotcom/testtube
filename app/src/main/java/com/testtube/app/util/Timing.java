package com.testtube.app.util;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * How long the slow bits take (extractions, time to the first frame), logged under "TestTubeTiming"
 * in debug builds only. Release builds skip all of it, so there's nothing to strip out:
 * {@code adb logcat -s TestTubeTiming} while testing a debug build shows where the time goes.
 */
public final class Timing {
	private static final String TAG = "TestTubeTiming";
	/** {@link #start()} when timing is off; {@link #log} ignores it. */
	public static final long OFF = 0L;

	private static volatile boolean enabled;

	private Timing() {
	}

	public static void init(@NonNull Context context) {
		enabled = (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
	}

	/** A start time for {@link #log}, or {@link #OFF}. */
	public static long start() {
		return enabled ? SystemClock.elapsedRealtime() : OFF;
	}

	/**
	 * Logs how long it's been since {@code start}. The message is only put together when it's
	 * actually logged.
	 */
	public static void log(@NonNull String what, @Nullable String subject, long start) {
		if (start == OFF || !enabled) return;
		Log.d(TAG, what + (subject != null ? " " + subject : "") + ": "
						+ (SystemClock.elapsedRealtime() - start) + " ms");
	}
}
