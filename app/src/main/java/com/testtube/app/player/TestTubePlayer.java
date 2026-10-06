package com.testtube.app.player;

import android.app.Activity;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.ui.DefaultTimeBar;

import com.testtube.app.PlaybackService;
import com.testtube.app.R;
import com.testtube.app.util.ToastUtils;
import com.testtube.app.extractor.ExtractionSession;
import com.testtube.app.extractor.PlaybackDetails;
import com.testtube.app.extractor.PlaybackPlan;
import com.testtube.app.extractor.PlaybackPlanner;
import com.testtube.app.extractor.VideoDetails;
import com.testtube.app.extractor.YoutubeExtractor;
import com.testtube.app.extractor.exception.ExtractionException;
import com.testtube.app.extractor.exception.LoginRequiredExtractionException;
import com.testtube.app.player.common.PlayerLoopMode;
import com.testtube.app.player.common.PlayerPreferences;
import com.testtube.app.player.controller.Controller;
import com.testtube.app.player.engine.Engine;
import com.testtube.app.player.queue.QueueInvalidationListener;
import com.testtube.app.player.queue.QueueNav;
import com.testtube.app.player.queue.QueueRepository;
import com.testtube.app.player.sponsor.SponsorBlockManager;
import com.testtube.app.player.sponsor.SponsorOverlayView;
import com.testtube.app.ui.ErrorDialog;
import com.tencent.mmkv.MMKV;

import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Playback facade that tracks the current media session and UI state.
 */
@UnstableApi
public class TestTubePlayer {
	private static final String KEY_LAST_AUDIO_LANG = "last_audio_lang";

	@NonNull
	private final Activity activity;
	@NonNull
	private final YoutubeExtractor extractor;
	@NonNull
	private final TestTubePlayerView playerView;
	@NonNull
	private final Controller controller;
	@NonNull
	private final Engine engine;
	@NonNull
	private final SponsorBlockManager sponsor;
	@NonNull
	private final QueueRepository queueRepo;
	@NonNull
	private final PlayerPreferences prefs;
	@NonNull
	private final PlayerStateStore stateStore;
	@NonNull
	private final Executor executor;
	private final MMKV kv = MMKV.defaultMMKV();
	@Nullable
	private PlaybackService playbackSvc;
	@NonNull
	private final QueueInvalidationListener queueListener = this::refreshQueueNav;
	@Nullable
	private CompletableFuture<Void> task;
	@Nullable
	private String queuedId;
	@Nullable
	private volatile String activeId;
	@Nullable
	private ExtractionSession extractSession;
	@Nullable
	private Runnable onRestore;
	@Nullable
	private Runnable onClose;
	@Nullable
	private Consumer<VideoDetails> detailsListener;
	private boolean inMiniPlayer;
	@Nullable
	private MiniBar miniBar;
	@Nullable
	private String currentTitle;
	@Nullable
	private String currentAuthor;
	@Nullable
	private String currentThumbnailUrl;
	private long activeDurationSeconds;
	private boolean wasInPip;

	public TestTubePlayer(@NonNull Activity activity,
	                  @NonNull YoutubeExtractor extractor,
	                  @NonNull TestTubePlayerView playerView,
	                  @NonNull Controller controller,
	                  @NonNull Engine engine,
	                  @NonNull SponsorBlockManager sponsor,
	                  @NonNull QueueRepository queueRepo,
	                  @NonNull PlayerPreferences prefs,
	                  @NonNull PlayerStateStore stateStore,
	                  @NonNull Executor executor) {
		this.activity = activity;
		this.extractor = extractor;
		this.playerView = playerView;
		this.controller = controller;
		this.engine = engine;
		this.sponsor = sponsor;
		this.queueRepo = queueRepo;
		this.prefs = prefs;
		this.stateStore = stateStore;
		this.executor = executor;
		playerView.setup();
		queueRepo.addListener(queueListener);
		setupEngineListeners();
	}

	private static boolean containsException(@Nullable Throwable throwable,
	                                         @NonNull Class<? extends Throwable> throwableClass) {
		if (throwable == null) return false;

		Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		List<Throwable> pending = new ArrayList<>();
		pending.add(throwable);

		for (int i = 0; i < pending.size(); i++) {
			Throwable th = pending.get(i);
			if (th == null || !visited.add(th)) continue;
			if (throwableClass.isInstance(th)) return true;

			Throwable cause = th.getCause();
			if (cause != null) pending.add(cause);
			Collections.addAll(pending, th.getSuppressed());
		}
		return false;
	}

	private void setupEngineListeners() {
		engine.addListener(new Player.Listener() {
			@Override
			public void onIsPlayingChanged(boolean isPlaying) {
				updateServiceProgress(isPlaying);
				if (inMiniPlayer) miniBar().setPlaying(isPlaying);
			}

			@Override
			public void onTracksChanged(@NonNull Tracks tracks) {
				saveSelectedTrackLanguage(tracks);
			}

			@Override
			public void onPlaybackStateChanged(int state) {
				if (state == Player.STATE_READY) {
					updateServiceProgress(engine.isPlaying());
				}
			}

			@Override
			public void onPlayerError(@NonNull PlaybackException error) {
				if (engine.recoverFromPlaybackError(error)) {
					return;
				}
				if (Engine.playbackRecoveryReason(error) != null && refreshStreams(error)) {
					return;
				}
				ErrorDialog.show(activity, error.getMessage(), error);
			}
		});
	}

	private void saveSelectedTrackLanguage(Tracks tracks) {
		try {
			for (Tracks.Group group : tracks.getGroups()) {
				if (group.getType() == androidx.media3.common.C.TRACK_TYPE_AUDIO && group.isSelected()) {
					for (int i = 0; i < group.length; i++) {
						if (group.isTrackSelected(i)) {
							String lang = group.getTrackFormat(i).language;
							kv.encode(KEY_LAST_AUDIO_LANG, lang != null ? lang : "und");
							return;
						}
					}
				}
			}
		} catch (Exception ignored) {
		}
	}

	private void updateServiceProgress(boolean isPlaying) {
		if (playbackSvc != null) {
			playbackSvc.updateProgress(engine.position(), engine.getPlaybackRate(), isPlaying);
		}
	}

	public void attachPlaybackService(@Nullable PlaybackService service) {
		this.playbackSvc = service;
		if (service != null) {
			service.initialize(engine);
			refreshQueueNav();
		}
	}

	public void refreshQueueNav() {
		QueueNav availability = engine.getQueueNavigationAvailability();
		activity.runOnUiThread(() -> {
			controller.refreshQueueNavigationAvailability(availability);
			miniBar().setNavigation(availability.isPreviousActionEnabled(), availability.isNextActionEnabled());
		});
		if (playbackSvc != null) {
			playbackSvc.updateQueueNavigationAvailability(availability);
		}
	}

	public void play(String url) {
		String videoId = YoutubeExtractor.getVideoId(url);
		if (videoId == null || Objects.equals(this.queuedId, videoId)) return;
		this.queuedId = videoId;
		activeDurationSeconds = 0L;
		sponsor.clear();

		activity.runOnUiThread(() -> {
			engine.clear();
			playerView.setTitle(null);
			SponsorOverlayView layer = playerView.findViewById(R.id.sponsor_overlay);
			layer.setData(null, 0, TimeUnit.MILLISECONDS);
			DefaultTimeBar bar = playerView.findViewById(R.id.exo_progress);
			bar.setAdGroupTimesMs(null, null, 0);
			currentTitle = null;
			currentAuthor = null;
			currentThumbnailUrl = "https://img.youtube.com/vi/" + videoId + "/hqdefault.jpg";
			if (inMiniPlayer) {
				updateMiniBar();
			} else {
				playerView.fitToWidth();
				playerView.show();
			}
		});

		cancelExtraction();
		if (task != null) task.cancel(true);
		ExtractionSession session = new ExtractionSession();
		extractSession = session;

		// SponsorBlock runs next to the extraction, never in front of it, so the video
		// starts as soon as the streams are known; segments are picked up the moment
		// they arrive and the seek bar markers are redrawn then.
		CompletableFuture.runAsync(() -> sponsor.load(videoId), executor)
						.thenRunAsync(() -> {
							if (!Objects.equals(this.queuedId, videoId) || activeDurationSeconds <= 0L) return;
							playerView.updateSkipMarkers(activeDurationSeconds, TimeUnit.SECONDS);
						}, activity::runOnUiThread);

		task = extractor.getInfo(url, session)
						.thenApply(details -> withPreferredPlan(details, videoId)).thenAccept(er -> activity.runOnUiThread(() -> {
							if (this.extractSession == session) this.extractSession = null;
							if (!Objects.equals(this.queuedId, videoId)) return;
							playerView.setTitle(er.video().getTitle());
							playerView.updateSkipMarkers(er.video().getDuration(), TimeUnit.SECONDS);

							try {
								engine.play(er);
							} catch (IllegalArgumentException e) {
								ErrorDialog.show(activity, e.getMessage(), e);
								return;
							}
							this.activeId = videoId;
							activeDurationSeconds = er.video().getDuration();
							stateStore.setVideoId(videoId);
							currentTitle = er.video().getTitle();
							currentAuthor = er.video().getAuthor();
							if (er.video().getThumbnailUrl() != null) {
								currentThumbnailUrl = er.video().getThumbnailUrl();
							}
							updateMiniBar();
							if (detailsListener != null) detailsListener.accept(er.video());

							if (playbackSvc != null) {
								PlaybackService.start(activity);
								playbackSvc.showNotification(er.video().getTitle(), er.video().getAuthor(), er.video().getThumbnailUrl(), er.video().getDuration() * 1000);
							}
							refreshQueueNav();
						})).exceptionally(e -> {
							if (this.extractSession == session) this.extractSession = null;
							Throwable cause = e instanceof CompletionException ? e.getCause() : e;
							if (cause instanceof CompletionException && cause.getCause() != null) {
								cause = cause.getCause();
							}
							if (cause instanceof Exception && !(cause instanceof ExtractionException)) {
								cause = classifyException((Exception) cause);
							}
							boolean unavailable = isUnavailable(e);
							if (cause instanceof ExtractionException) {
								Throwable error = cause;
								activity.runOnUiThread(() -> {
									if (!Objects.equals(this.queuedId, videoId)) return;
									if (unavailable && engine.skipUnavailable(videoId)) {
										ToastUtils.show(activity, R.string.video_unavailable_skipped);
										return;
									}
									ErrorDialog.show(activity, error.getMessage(), error);
								});
							}
							return null;
						});
	}

	@NonNull
	private PlaybackDetails withPreferredPlan(@NonNull PlaybackDetails details,
	                                          @NonNull String videoId) {
		String lang = kv.decodeString(KEY_LAST_AUDIO_LANG, "und");
		String preferredQuality = prefs.getPreferredQuality();
		PlaybackPlan plan = PlaybackPlanner.plan(details.deliveries(), preferredQuality, lang);
		return new PlaybackDetails(
						details.video(),
						details.catalog(),
						details.deliveries(),
						plan,
						details.segments(),
						details.subtitles());
	}

	/**
	 * Re-extracts the current video with fresh stream URLs and resumes at the current position.
	 * Used when every stream of the current extraction was rejected (expired or blocked URLs).
	 *
	 * @return true when a refresh was started
	 */
	private boolean refreshStreams(@NonNull PlaybackException cause) {
		String videoId = activeId;
		if (videoId == null || !Objects.equals(queuedId, videoId) || !engine.consumeRecoveryAttempt()) {
			return false;
		}
		long position = Math.max(0L, engine.position());
		cancelExtraction();
		ExtractionSession session = new ExtractionSession();
		extractSession = session;
		extractor.getInfo("https://www.youtube.com/watch?v=" + videoId, session, true)
						.thenApply(details -> withPreferredPlan(details, videoId))
						.whenComplete((details, error) -> activity.runOnUiThread(() -> {
							if (this.extractSession == session) this.extractSession = null;
							if (!Objects.equals(this.activeId, videoId) || !Objects.equals(this.queuedId, videoId)) {
								return;
							}
							if (error != null || details == null) {
								ErrorDialog.show(activity, cause.getMessage(), cause);
								return;
							}
							try {
								engine.replace(details, position);
							} catch (IllegalArgumentException e) {
								ErrorDialog.show(activity, e.getMessage(), e);
							}
						}));
		return true;
	}

	/**
	 * Removed, private, blocked or otherwise unplayable videos.
	 */
	private static boolean isUnavailable(@Nullable Throwable error) {
		for (Throwable t = error; t != null; t = t.getCause()) {
			if (t instanceof org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException) return true;
			if (t.getCause() == t) break;
		}
		return false;
	}

	@NonNull
	private ExtractionException classifyException(@NonNull Exception exception) {
		return classifyExtractionException(exception);
	}

	@NonNull
	static ExtractionException classifyExtractionException(@NonNull Exception exception) {
		if (containsException(exception, SignInConfirmNotBotException.class)) {
			return new LoginRequiredExtractionException(exception);
		}
		if (containsException(exception, org.schabi.newpipe.extractor.exceptions.ReCaptchaException.class)) {
			return new LoginRequiredExtractionException(exception);
		}
		if (containsException(exception, org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException.class)) {
			return new ExtractionException("This video is age restricted and could not be extracted.", exception);
		}
		if (containsException(exception, org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException.class)
						|| containsException(exception, org.schabi.newpipe.extractor.exceptions.UnsupportedContentInCountryException.class)) {
			return new ExtractionException("This video is not available in your region.", exception);
		}
		if (containsException(exception, org.schabi.newpipe.extractor.exceptions.PrivateContentException.class)) {
			return new ExtractionException("This video is private or requires permission.", exception);
		}
		if (containsException(exception, org.schabi.newpipe.extractor.exceptions.PaidContentException.class)
						|| containsException(exception, org.schabi.newpipe.extractor.exceptions.YoutubeMusicPremiumContentException.class)
						|| containsException(exception, org.schabi.newpipe.extractor.exceptions.ContentNotSupportedException.class)) {
			return new ExtractionException("This video type is not supported.", exception);
		}
		if (containsException(exception, SocketTimeoutException.class)
						|| containsException(exception, ConnectException.class)
						|| containsException(exception, NoRouteToHostException.class)
						|| containsException(exception, UnknownHostException.class)) {
			return new ExtractionException("Network error while extracting video info.", exception);
		}
		if (containsException(exception, IOException.class)) {
			return new ExtractionException("I/O error while extracting video info.", exception);
		}
		org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException unavailable =
						firstException(exception, org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException.class);
		if (unavailable != null) {
			return new ExtractionException(messageOrDefault(unavailable, "This video is not available."), exception);
		}
		org.schabi.newpipe.extractor.exceptions.ExtractionException extraction =
						firstException(exception, org.schabi.newpipe.extractor.exceptions.ExtractionException.class);
		if (extraction != null) {
			return new ExtractionException("Extract failed: " + messageOrDefault(extraction, extraction.getClass().getSimpleName()), exception);
		}
		return new ExtractionException("Extract failed", exception);
	}

	@Nullable
	private static <T extends Throwable> T firstException(@Nullable Throwable throwable,
	                                                     @NonNull Class<T> throwableClass) {
		if (throwable == null) return null;

		Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		List<Throwable> pending = new ArrayList<>();
		pending.add(throwable);

		for (int i = 0; i < pending.size(); i++) {
			Throwable th = pending.get(i);
			if (th == null || !visited.add(th)) continue;
			if (throwableClass.isInstance(th)) return throwableClass.cast(th);

			Throwable cause = th.getCause();
			if (cause != null) pending.add(cause);
			Collections.addAll(pending, th.getSuppressed());
		}
		return null;
	}

	@NonNull
	private static String messageOrDefault(@NonNull Throwable throwable,
	                                       @NonNull String fallback) {
		String message = throwable.getMessage();
		return message == null || message.isBlank() ? fallback : message;
	}

	public void hide() {
		this.queuedId = null;
		this.activeId = null;
		stateStore.setVideoId(null);
		cancelExtraction();
		if (task != null) task.cancel(true);
		activity.runOnUiThread(() -> {
			playerView.disableAutoPiP();
			// Leave the mini player without showing the full player: nothing is playing anymore.
			if (inMiniPlayer) leaveMiniPlayer();
			setMiniPlayerCallbacks(null, null);
			playerView.hide();
			engine.clear();
			if (playbackSvc != null) {
				playbackSvc.hideNotification();
			}
		});
	}

	public boolean isPlaying() {
		return engine.isPlaying();
	}

	public void pause() {
		engine.pause();
	}

	public boolean seekLoadedVideo(@Nullable String url, long positionMs) {
		if (positionMs < 0L || url == null) return false;
		String videoId = YoutubeExtractor.getVideoId(url);
		if (videoId == null || !Objects.equals(activeId, videoId)) return false;
		activity.runOnUiThread(() -> engine.seekTo(positionMs));
		return true;
	}

	public long getResumePosition(@Nullable String videoId) {
		return prefs.getResumePosition(videoId);
	}

	public boolean isFullscreen() {
		return controller.isFullscreen();
	}

	public void enterFullscreen() {
		controller.enterFullscreen();
	}

	public void exitFullscreen() {
		controller.exitFullscreen();
	}

	public void exitFullscreenImmediately() {
		controller.exitFullscreenImmediately();
	}

	public void enterPictureInPicture() {
		playerView.enterPiP();
	}

	public boolean shouldAutoEnterPictureInPicture() {
		return playerView.getVisibility() == View.VISIBLE;
	}

	public boolean canSuspendWatch() {
		return playerView.getVisibility() == View.VISIBLE;
	}

	/**
	 * Moves the video into the bar at the bottom of the screen; playback continues.
	 */
	public void enterInAppMiniPlayer() {
		inMiniPlayer = true;
		stateStore.setInMiniPlayer(true);
		if (controller.isFullscreen()) controller.exitFullscreenImmediately();
		controller.enterMiniPlayer();
		playerView.hide();
		updateMiniBar();
		miniBar().setPlaying(engine.isPlaying());
		miniBar().show();
		refreshQueueNav();
	}

	/**
	 * Restores the full player from the mini player. Does nothing when the mini player is not
	 * shown, so a late call after {@link #hide()} can never bring back an empty black player.
	 */
	public void exitInAppMiniPlayer() {
		if (!inMiniPlayer) return;
		inMiniPlayer = false;
		stateStore.setInMiniPlayer(false);
		miniBar().hide();
		playerView.fitToWidth();
		playerView.show();
		controller.exitMiniPlayer();
	}

	private void leaveMiniPlayer() {
		inMiniPlayer = false;
		stateStore.setInMiniPlayer(false);
		miniBar().hide();
		controller.exitMiniPlayer();
	}

	@NonNull
	private MiniBar miniBar() {
		MiniBar bar = miniBar;
		if (bar == null) {
			bar = new MiniBar(activity);
			bar.setActions(
							() -> {
								Runnable restore = onRestore;
								if (restore != null) restore.run();
							},
							engine::skipToPrevious,
							() -> {
								if (engine.isPlaying()) {
									engine.pause();
								} else {
									if (engine.getPlaybackState() == Player.STATE_ENDED) engine.seekTo(0L);
									engine.play();
								}
							},
							engine::skipToNext,
							() -> {
								Runnable close = onClose;
								hide();
								if (close != null) close.run();
							});
			miniBar = bar;
		}
		return bar;
	}

	private void updateMiniBar() {
		miniBar().setVideo(currentTitle, currentAuthor, currentThumbnailUrl);
	}

	public void restoreInAppMiniPlayerUiIfNeeded() {
		if (!inMiniPlayer) return;
		updateMiniBar();
		miniBar().setPlaying(engine.isPlaying());
		miniBar().show();
	}

	/**
	 * Receives the details of each video once it starts, on the main thread.
	 */
	public void setDetailsListener(@Nullable Consumer<VideoDetails> listener) {
		this.detailsListener = listener;
	}

	public void setMiniPlayerCallbacks(@Nullable Runnable onRestore, @Nullable Runnable onClose) {
		this.onRestore = onRestore;
		this.onClose = onClose;
	}

	public void onPictureInPictureModeChanged(boolean isInPiP) {
		controller.onPictureInPictureModeChanged(isInPiP);
		if (!isInPiP) playerView.disableAutoPiP();
		if (wasInPip && !isInPiP && inMiniPlayer && onRestore != null) {
			onRestore.run();
		}
		wasInPip = isInPiP;
	}

	@Nullable
	public String getVideoId() {
		return activeId;
	}

	@NonNull
	public PlayerLoopMode getLoopMode() {
		return controller.getLoopMode();
	}

	public void setLoopMode(@NonNull PlayerLoopMode mode) {
		controller.setLoopMode(mode);
	}

	@Nullable
	public String getSubtitleLanguage() {
		return engine.getSubtitleLanguage();
	}

	public void setSubtitleLanguage(@Nullable String language) {
		engine.setSubtitleLanguage(language);
	}

	public void release() {
		cancelExtraction();
		if (task != null) task.cancel(true);
		activeId = null;
		queueRepo.removeListener(queueListener);
		stateStore.clear();
		wasInPip = false;
		onRestore = null;
		onClose = null;
		controller.release();
		activity.runOnUiThread(playerView::disableAutoPiP);
		inMiniPlayer = false;
		if (miniBar != null) miniBar.hide();
		engine.release();
	}

	private void cancelExtraction() {
		if (extractSession == null) return;
		extractSession.cancel();
		extractSession = null;
	}

	public boolean isInMiniPlayer() {
		return inMiniPlayer;
	}
}
