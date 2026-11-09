package com.testtube.app.links;

import android.app.Activity;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** TestTube opens YouTube links only. TestTube+ has its own copy (src/plus). */
public final class ExtraLinks {
	private ExtraLinks() {
	}

	/** This edition handles the link itself (not YouTube's). */
	public static boolean isSupported(@Nullable String url) {
		return false;
	}

	/** Opens a link {@link #isSupported} accepted. */
	@MainThread
	public static void open(@NonNull Activity activity, @NonNull String url, @NonNull LinkTarget target) {
		// never called, nothing is supported
	}
}
