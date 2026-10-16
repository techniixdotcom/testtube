package com.testtube.app.player.engine;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.exoplayer.ExoPlayer;

import com.testtube.app.extractor.PlaybackDetails;

/**
 * The player of the Shorts tab. Separate from the main player so a Short never touches its queue,
 * history or mini player, but it builds the streams the same way.
 */
@UnstableApi
public final class ShortsEngine {
	/** Called on the main thread. */
	public interface Listener {
		void onFirstFrame();

		void onPlayingChanged(boolean playing);

		void onError(@NonNull PlaybackException error);
	}

	@NonNull
	private final PlayerDataSource sources;
	@NonNull
	private final ExoPlayer player;
	@Nullable
	private Listener listener;

	public ShortsEngine(@NonNull Context context, @Nullable SimpleCache cache) {
		this.sources = new PlayerDataSource(cache);
		this.player = new ExoPlayer.Builder(context)
						.setLoadControl(PlayerLoadControl.create())
						.setAudioAttributes(new AudioAttributes.Builder()
										.setUsage(C.USAGE_MEDIA)
										.setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
										.build(), true)
						.setHandleAudioBecomingNoisy(true)
						.setUsePlatformDiagnostics(false)
						.build();
		// Shorts loop until the next one is swiped in
		player.setRepeatMode(Player.REPEAT_MODE_ONE);
		// No captions over Shorts (the extraction comes with every subtitle track)
		player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
						.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
						.build());
		player.addListener(new Player.Listener() {
			@Override
			public void onRenderedFirstFrame() {
				if (listener != null) listener.onFirstFrame();
			}

			@Override
			public void onIsPlayingChanged(boolean isPlaying) {
				if (listener != null) listener.onPlayingChanged(isPlaying);
			}

			@Override
			public void onPlayerError(@NonNull PlaybackException error) {
				if (listener != null) listener.onError(error);
			}
		});
	}

	@NonNull
	public Player player() {
		return player;
	}

	public void setListener(@Nullable Listener listener) {
		this.listener = listener;
	}

	/**
	 * Starts a Short from the beginning.
	 *
	 * @throws IllegalArgumentException when the extraction has nothing playable
	 */
	public void play(@NonNull PlaybackDetails details) {
		player.setMediaSource(PlaybackSourceFactory.create(sources, details, details.plan()));
		player.prepare();
		player.setPlayWhenReady(true);
	}

	public void resume() {
		if (player.getPlaybackState() == Player.STATE_IDLE && player.getMediaItemCount() > 0) player.prepare();
		player.setPlayWhenReady(true);
	}

	public void pause() {
		player.setPlayWhenReady(false);
	}

	public boolean isPlaying() {
		return player.getPlayWhenReady();
	}

	public void stop() {
		player.stop();
		player.clearMediaItems();
	}

	/** Played share of the Short, 0 to 1. */
	public float progress() {
		long duration = player.getDuration();
		if (duration == C.TIME_UNSET || duration <= 0) return 0f;
		return Math.max(0f, Math.min(1f, player.getCurrentPosition() / (float) duration));
	}

	public void release() {
		listener = null;
		player.release();
	}
}
