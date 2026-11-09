package com.testtube.app.external;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.testtube.app.extractor.YoutubeExtractor;

/**
 * Id of whatever a watch link plays: a YouTube id, or for other sites (TestTube+) their id with
 * a prefix ("fb:…", "sc:…").
 * <p>
 * Other sites go first: {@link YoutubeExtractor#getVideoId} grabs "v=" plus 11 chars from any
 * link, so a Facebook watch/?v= link would pass as YouTube.
 */
public final class ExternalIds {
	private ExternalIds() {
	}

	/** Id of what the link plays, or null. */
	@Nullable
	public static String idOf(@Nullable String url) {
		if (url == null) return null;
		if (ExternalSources.handles(url)) return ExternalSources.idOf(url);
		return YoutubeExtractor.getVideoId(url);
	}

	/** Other site's id. YouTube ids never contain a colon. */
	public static boolean isExternal(@Nullable String id) {
		return id != null && id.indexOf(':') > 0;
	}

	/** Link plays from another site (TestTube+). */
	public static boolean isExternalUrl(@Nullable String url) {
		return url != null && ExternalSources.handles(url);
	}

	/** Watch link for a YouTube id. */
	@NonNull
	public static String youtubeUrl(@NonNull String videoId) {
		return "https://www.youtube.com/watch?v=" + videoId;
	}
}
