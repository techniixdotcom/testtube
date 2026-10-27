package com.testtube.app.player.engine;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.exoplayer.ExoPlayer;

import com.testtube.app.extractor.PlaybackDetails;
import com.testtube.app.extractor.PlaybackPlan;
import com.testtube.app.extractor.PlaybackPlanner;
import com.testtube.app.util.StringUtils;

import java.util.function.Supplier;

/**
 * The player of the Shorts tab. Separate from the main player so a Short never touches its queue,
 * history or mini player, but it builds the streams the same way.
 * <p>
 * Two players take turns: while one plays, the other already loads the next Short, so a swipe
 * just switches over instead of starting from nothing.
 */
@UnstableApi
public final class ShortsEngine {
	/** Shorts don't change quality while playing; more than this mostly costs time to start. */
	private static final int MAX_HEIGHT = 720;

	/** Called on the main thread, for the player that's on screen only. */
	public interface Listener {
		void onFirstFrame();

		void onPlayingChanged(boolean playing);

		void onVideoSize(@NonNull VideoSize size);

		void onError(@NonNull PlaybackException error);
	}

	@NonNull
	private final PlayerDataSource sources;
	@NonNull
	private final Supplier<String> quality;
	@NonNull
	private ExoPlayer player;
	/** Loads the next Short without playing it. */
	@NonNull
	private ExoPlayer standby;
	@Nullable
	private String standbyId;
	@Nullable
	private Listener listener;

	public ShortsEngine(@NonNull Context context, @Nullable SimpleCache cache, @NonNull Supplier<String> quality) {
		this.sources = new PlayerDataSource(cache);
		this.quality = quality;
		this.player = build(context);
		this.standby = build(context);
	}

	@NonNull
	private ExoPlayer build(@NonNull Context context) {
		ExoPlayer exo = new ExoPlayer.Builder(context)
						.setLoadControl(PlayerLoadControl.create())
						.setAudioAttributes(new AudioAttributes.Builder()
										.setUsage(C.USAGE_MEDIA)
										.setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
										.build(), true)
						.setHandleAudioBecomingNoisy(true)
						.setUsePlatformDiagnostics(false)
						.build();
		// Shorts loop until the next one is swiped in
		exo.setRepeatMode(Player.REPEAT_MODE_ONE);
		// No captions over Shorts (the extraction comes with every subtitle track)
		exo.setTrackSelectionParameters(exo.getTrackSelectionParameters().buildUpon()
						.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
						.build());
		exo.addListener(new Player.Listener() {
			@Override
			public void onRenderedFirstFrame() {
				if (exo == player && listener != null) listener.onFirstFrame();
			}

			@Override
			public void onIsPlayingChanged(boolean isPlaying) {
				if (exo == player && listener != null) listener.onPlayingChanged(isPlaying);
			}

			@Override
			public void onVideoSizeChanged(@NonNull VideoSize size) {
				if (exo == player && listener != null) listener.onVideoSize(size);
			}

			@Override
			public void onPlayerError(@NonNull PlaybackException error) {
				if (exo == player) {
					if (listener != null) listener.onError(error);
				} else {
					// The preloaded one broke (expired link etc.); play() will just start fresh
					standbyId = null;
				}
			}
		});
		return exo;
	}

	/** The player on screen; changes when a preloaded Short takes over, see {@link #play}. */
	@NonNull
	public Player player() {
		return player;
	}

	public void setListener(@Nullable Listener listener) {
		this.listener = listener;
	}

	/**
	 * Starts a Short from the beginning. Returns true when the preloaded player took over, so the
	 * view has to show {@link #player()} now.
	 *
	 * @throws IllegalArgumentException when the extraction has nothing playable
	 */
	public boolean play(@NonNull PlaybackDetails details) {
		String id = details.video().getId();
		if (id != null && id.equals(standbyId) && standby.getPlaybackState() != Player.STATE_IDLE) {
			ExoPlayer previous = player;
			player = standby;
			standby = previous;
			standbyId = null;
			previous.stop();
			previous.clearMediaItems();
			player.seekTo(0L);
			player.setPlayWhenReady(true);
			VideoSize size = player.getVideoSize();
			if (listener != null && size.width > 0) listener.onVideoSize(size);
			return true;
		}
		player.setMediaSource(PlaybackSourceFactory.create(sources, details, plan(details)));
		player.prepare();
		player.setPlayWhenReady(true);
		return false;
	}

	/** Loads a Short in the background so it starts at once when it's swiped to. */
	public void preload(@NonNull PlaybackDetails details) {
		String id = details.video().getId();
		if (id == null || id.equals(standbyId)) return;
		try {
			standby.setMediaSource(PlaybackSourceFactory.create(sources, details, plan(details)));
		} catch (IllegalArgumentException e) {
			standbyId = null;
			return;
		}
		standbyId = id;
		standby.setPlayWhenReady(false);
		standby.prepare();
	}

	@NonNull
	private PlaybackPlan plan(@NonNull PlaybackDetails details) {
		String preferred = quality.get();
		int height = StringUtils.parseHeight(preferred);
		String capped = height > 0 && height <= MAX_HEIGHT ? preferred : MAX_HEIGHT + "p";
		return PlaybackPlanner.plan(details.deliveries(), capped);
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
		standbyId = null;
		player.release();
		standby.release();
	}
}
