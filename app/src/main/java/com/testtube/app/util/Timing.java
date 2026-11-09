package com.testtube.app.util;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * How long the slow bits take (extraction, time to first frame), logged as "TestTubeTiming" in
 * debug builds only. Release skips all of it.
 * {@code adb logcat -s TestTubeTiming} on a debug build shows where the time goes.
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

	/** Start time for {@link #log}, or {@link #OFF}. */
	public static long start() {
		return enabled ? SystemClock.elapsedRealtime() : OFF;
	}

	/** Logs time since {@code start}. The message is only built if it actually gets logged. */
	public static void log(@NonNull String what, @Nullable String subject, long start) {
		if (start == OFF || !enabled) return;
		Log.d(TAG, what + (subject != null ? " " + subject : "") + ": "
						+ (SystemClock.elapsedRealtime() - start) + " ms");
	}
}
