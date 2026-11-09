package com.testtube.app.external;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.media3.common.util.UnstableApi;

import com.testtube.app.AppGraph;
import com.testtube.app.plus.PlusSources;

import java.io.IOException;

/**
 * TestTube+ plays Facebook videos and SoundCloud songs in the player like YouTube videos. Work is in
 * {@link PlusSources}; TestTube's copy plays nothing else.
 */
@UnstableApi
public final class ExternalSources {
	private static final String TAG = "ExternalSources";

	private ExternalSources() {
	}

	/** Link plays from another site (Facebook video or SoundCloud track). */
	public static boolean handles(@Nullable String url) {
		return PlusSources.handles(url);
	}

	/** Prefixed id ("fb:…", "sc:…") without asking the site. */
	@Nullable
	public static String idOf(@Nullable String url) {
		return PlusSources.idOf(url);
	}

	/** Reads what to play from the site. Blocks, never on the main thread. */
	@WorkerThread
	@NonNull
	public static ExternalVideo resolve(@NonNull Context context, @NonNull String url) throws ExternalException {
		try {
			return PlusSources.resolve(context.getApplicationContext(), AppGraph.of(context), url);
		} catch (IOException e) {
			Log.w(TAG, "could not reach the site for " + url, e);
			throw new ExternalException(com.testtube.app.R.string.link_external_network, String.valueOf(e.getMessage()));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new ExternalException(com.testtube.app.R.string.link_external_network, "interrupted");
		}
	}
}
