package com.testtube.app.external;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

/** TestTube plays YouTube only. TestTube+'s copy (src/plus) plays Facebook and SoundCloud too. */
public final class ExternalSources {
	private ExternalSources() {
	}

	/** Link plays from another site. */
	public static boolean handles(@Nullable String url) {
		return false;
	}

	/** Prefixed id, without asking the site. */
	@Nullable
	public static String idOf(@Nullable String url) {
		return null;
	}

	/** Reads what to play from the site. */
	@WorkerThread
	@NonNull
	public static ExternalVideo resolve(@NonNull Context context, @NonNull String url) throws ExternalException {
		throw new ExternalException(com.testtube.app.R.string.failure_other, "no other sites in this edition");
	}
}
