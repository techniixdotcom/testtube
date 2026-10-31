package com.testtube.app.util;

import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Keeps the error that closed the app, so the next start can show it and people can paste it
 * into a bug report instead of "it crashed". Stays on the phone; nothing is sent anywhere.
 */
public final class CrashLog {
	private static final String FILE = "last_crash.txt";

	private CrashLog() {
	}

	public static void install(@NonNull Context context) {
		Context app = context.getApplicationContext();
		File file = new File(app.getFilesDir(), FILE);
		Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
		Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
			try {
				StringWriter trace = new StringWriter();
				error.printStackTrace(new PrintWriter(trace));
				String version = app.getPackageManager().getPackageInfo(app.getPackageName(), 0).versionName;
				String report = "TestTube " + version + ", Android " + Build.VERSION.RELEASE
								+ " (" + Build.VERSION.SDK_INT + "), " + Build.MANUFACTURER + " " + Build.MODEL
								+ "\nThread: " + thread.getName() + "\n\n" + trace;
				Files.write(file.toPath(), report.getBytes(StandardCharsets.UTF_8));
			} catch (Throwable ignored) {
				// Writing the report must never hide the real error
			}
			if (previous != null) previous.uncaughtException(thread, error);
		});
	}

	/** The report of the last crash, once; null when the app closed normally. */
	@Nullable
	public static String take(@NonNull Context context) {
		File file = new File(context.getFilesDir(), FILE);
		if (!file.exists()) return null;
		try {
			return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
		} catch (IOException e) {
			return null;
		} finally {
			//noinspection ResultOfMethodCallIgnored
			file.delete();
		}
	}
}
