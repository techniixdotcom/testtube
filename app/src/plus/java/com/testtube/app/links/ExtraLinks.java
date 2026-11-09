package com.testtube.app.links;

import android.app.Activity;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;

import com.testtube.app.plus.PlusSources;
import com.testtube.app.plus.SoundCloudOpener;
import com.testtube.app.plus.SpotifyOpener;

/**
 * TestTube+ also opens Spotify (played from YouTube), Facebook and SoundCloud links (played in the
 * player). TestTube's copy (src/standard) opens nothing extra.
 */
@UnstableApi
public final class ExtraLinks {
	private ExtraLinks() {
	}

	/** This edition handles the link itself (not YouTube's). */
	public static boolean isSupported(@Nullable String url) {
		return url != null && (PlusSources.handles(url) || SoundCloudOpener.isSupported(url)
						|| SpotifyOpener.isSupported(url));
	}

	/** Opens a link {@link #isSupported} accepted. */
	@MainThread
	public static void open(@NonNull Activity activity, @NonNull String url, @NonNull LinkTarget target) {
		if (PlusSources.handles(url)) {
			// Facebook video or SoundCloud track: straight into the player
			target.playUrl(url);
		} else if (SoundCloudOpener.isSupported(url)) {
			SoundCloudOpener.open(activity, url, target);
		} else if (SpotifyOpener.isSupported(url)) {
			SpotifyOpener.open(activity, url, target);
		}
	}
}
