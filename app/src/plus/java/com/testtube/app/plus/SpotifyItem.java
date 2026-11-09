package com.testtube.app.plus;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

/**
 * What a Spotify link is, per Spotify's embed page.
 * @param name   track, album, playlist, artist or show
 * @param tracks songs to find on YouTube: the track, or an album's/playlist's songs in order;
 *               empty for artists and shows
 */
record SpotifyItem(@NonNull SpotifyLink.Type type, @NonNull String name, @NonNull List<Track> tracks) {
	/** @param durationMs 0 if Spotify didn't say */
	record Track(@NonNull String title, @Nullable String artist, long durationMs) {
		/** What to search YouTube for. */
		@NonNull
		String query() {
			return artist == null || artist.isBlank() ? title : artist + " - " + title;
		}
	}
}
