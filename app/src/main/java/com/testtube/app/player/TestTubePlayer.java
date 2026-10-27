package com.testtube.app.player;

import android.app.Activity;
import android.app.PendingIntent;
import android.app.RemoteAction;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.RectF;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.view.View;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.content.ContextCompat;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.ui.DefaultTimeBar;

import com.testtube.app.AppSettings;
import com.testtube.app.PlaybackService;
import com.testtube.app.R;
import com.testtube.app.extractor.ExtractionSession;
import com.testtube.app.extractor.PlaybackDetails;
import com.testtube.app.extractor.PlaybackPlan;
import com.testtube.app.extractor.PlaybackPlanner;
import com.testtube.app.extractor.VideoDetails;
import com.testtube.app.extractor.YoutubeExtractor;
import com.testtube.app.net.Failure;
import com.testtube.app.net.RequestPacer;
import com.testtube.app.player.common.PlayerLoopMode;
import com.testtube.app.player.common.PlayerPreferences;
import com.testtube.app.player.controller.Controller;
import com.testtube.app.player.controller.gesture.WatchDrag;
import com.testtube.app.player.engine.Engine;
import com.testtube.app.player.queue.QueueInvalidationListener;
import com.testtube.app.player.queue.QueueNav;
import com.testtube.app.player.queue.QueueRepository;
import com.testtube.app.player.sponsor.SponsorBlockManager;
import com.testtube.app.player.sponsor.SponsorOverlayView;
import com.testtube.app.ui.FailureDialog;
import com.testtube.app.util.NetworkWatcher;
import com.testtube.app.util.ToastUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@UnstableApi
public class TestTubePlayer {
	private static final String ACTION_FLOATING_CONTROL = "com.testtube.app.action.FLOATING_CONTROL";
	private static final String EXTRA_CONTROL = "control";
	private static final int CONTROL_PREVIOUS = 1;
	private static final int CONTROL_PLAY_PAUSE = 2;
	private static final int CONTROL_NEXT = 3;

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
	@Nullable
	private PlaybackService playbackSvc;
	@NonNull
	private final QueueInvalidationListener queueListener = this::onQueueChanged;
	@Nullable
	private Runnable onStartedPlaying;
	@Nullable
	private CompletableFuture<Void> task;
	@Nullable
	private String queuedId;
	@Nullable
	private volatile String activeId;
	@Nullable
	private ExtractionSession extractSession;
	/** The video already retried by itself after a failure, so it isn't retried endlessly. */
	@Nullable
	private String autoRetriedId;
	private static final long AUTO_RETRY_MIN_MS = 2_500L;
	private static final long AUTO_RETRY_MAX_MS = 20_000L;
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
	private FloatingWindow floatingWindow;
	@Nullable
	private WatchDrag watchDrag;
	@Nullable
	private String currentTitle;
	@Nullable
	private String currentAuthor;
	@Nullable
	private String currentThumbnailUrl;
	@Nullable
	private VideoDetails currentDetails;
	private long activeDurationSeconds;
	private boolean wasInPip;
	@Nullable
	private NetworkWatcher network;
	@NonNull
	private final NetworkWatcher.Listener networkListener = mobile -> applyPreferredQuality();
	@Nullable
	private BroadcastReceiver floatingControls;

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
		playerView.setPiPActions(this::floatingActions);
		queueRepo.addListener(queueListener);
		engine.setPlanSelector(this::withPreferredPlan);
		setupEngineListeners();
	}

	/** Switches the quality when the phone moves between Wi-Fi and mobile data. */
	public void watchNetwork(@NonNull NetworkWatcher watcher) {
		if (network != null) network.removeListener(networkListener);
		network = watcher;
		watcher.addListener(networkListener);
	}

	/** Plays the open video in the quality set for the network the phone is on now. */
	public void applyPreferredQuality() {
		if (activeId != null) engine.applyPreferredQuality();
	}

	private void setupEngineListeners() {
		engine.addListener(new Player.Listener() {
			@Override
			public void onIsPlayingChanged(boolean isPlaying) {
				updateServiceProgress(isPlaying);
				if (inMiniPlayer) miniBar().setPlaying(isPlaying);
				if (floatingWindow != null) floatingWindow.setPlaying(isPlaying);
				updateFloatingPlayer();
				Runnable listener = onStartedPlaying;
				if (isPlaying && listener != null) listener.run();
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
				String failedId = activeId;
				if (failedId != null && cannotEverPlay(error) && engine.skipUnavailable(failedId)) {
					ToastUtils.show(activity, R.string.video_unplayable_skipped);
					return;
				}
				showFailure(error, failedId);
			}
		});
	}

	/**
	 * The video itself can't be played here (broken stream, unsupported format, DRM). Network
	 * errors don't count, otherwise a bad connection would skip through a whole playlist.
	 */
	private static boolean cannotEverPlay(@NonNull PlaybackException error) {
		int code = error.errorCode;
		return code >= PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED && code < 5000
						|| code >= PlaybackException.ERROR_CODE_DRM_UNSPECIFIED && code < 7000;
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

	private void onQueueChanged() {
		refreshQueueNav();
		engine.onQueueChanged();
	}

	public void refreshQueueNav() {
		QueueNav availability = engine.getQueueNavigationAvailability();
		activity.runOnUiThread(() -> {
			controller.refreshQueueNavigationAvailability(availability);
			miniBar().setNavigation(availability.isPreviousActionEnabled(), availability.isNextActionEnabled());
			updateFloatingPlayer();
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
			currentDetails = null;
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

		// SponsorBlock loads in parallel so it never delays the start. Segments and seek bar
		// markers are applied when they arrive.
		CompletableFuture.runAsync(() -> sponsor.load(videoId), executor)
						.thenRunAsync(() -> {
							if (!Objects.equals(this.queuedId, videoId) || activeDurationSeconds <= 0L) return;
							playerView.updateSkipMarkers(activeDurationSeconds, TimeUnit.SECONDS);
						}, activity::runOnUiThread);

		task = extractor.getInfo(url, session)
						.thenApply(this::withPreferredPlan).thenAccept(er -> activity.runOnUiThread(() -> {
							if (this.extractSession == session) this.extractSession = null;
							if (!Objects.equals(this.queuedId, videoId)) return;
							playerView.setTitle(er.video().getTitle());
							playerView.updateSkipMarkers(er.video().getDuration(), TimeUnit.SECONDS);

							try {
								engine.play(er);
							} catch (IllegalArgumentException e) {
								showFailure(e, videoId);
								return;
							}
							autoRetriedId = null;
							this.activeId = videoId;
							activeDurationSeconds = er.video().getDuration();
							stateStore.setVideoId(videoId);
							currentTitle = er.video().getTitle();
							currentAuthor = er.video().getAuthor();
							currentDetails = er.video();
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
							Throwable cause = e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
							if (session.isCancelled() || Failure.isCancellation(cause)) return null;
							Failure failure = Failure.report(cause);
							activity.runOnUiThread(() -> {
								if (!Objects.equals(this.queuedId, videoId)) return;
								// Removed, private, age-restricted, blocked…: in a playlist or the
								// queue, say why and move on instead of stopping on an error
								if (!failure.retryable && engine.skipUnavailable(videoId)) {
									ToastUtils.show(activity, failure.skippedMessage());
									return;
								}
								if (!retryLater(failure, videoId)) showFailure(cause, videoId);
							});
							return null;
						});
	}

	/**
	 * Picks the streams by the preferred quality, always with the original audio track. A track
	 * chosen in the menu only applies to that video.
	 */
	@NonNull
	private PlaybackDetails withPreferredPlan(@NonNull PlaybackDetails details) {
		PlaybackPlan plan = PlaybackPlanner.plan(details.deliveries(), prefs.getPreferredQuality());
		return new PlaybackDetails(
						details.video(),
						details.catalog(),
						details.deliveries(),
						plan,
						details.segments(),
						details.subtitles());
	}

	/**
	 * Re-extracts the current video and resumes at the same position. Used when all streams were
	 * rejected (expired or blocked URLs).
	 *
	 * @return true if a refresh was started
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
						.thenApply(this::withPreferredPlan)
						.whenComplete((details, error) -> activity.runOnUiThread(() -> {
							if (this.extractSession == session) this.extractSession = null;
							if (!Objects.equals(this.activeId, videoId) || !Objects.equals(this.queuedId, videoId)) {
								return;
							}
							if (error != null || details == null) {
								showFailure(error != null ? error : cause, videoId);
								return;
							}
							try {
								engine.replace(details, position);
							} catch (IllegalArgumentException e) {
								showFailure(e, videoId);
							}
						}));
		return true;
	}

	/**
	 * After YouTube's bot check or a timeout, tries the video once more by itself when the pause is
	 * over, so a short hiccup needs no tap. Nobody wants to mash retry because YouTube had a
	 * moment. The person sees a short notice meanwhile.
	 *
	 * @return true if a retry was scheduled
	 */
	private boolean retryLater(@NonNull Failure failure, @NonNull String videoId) {
		if (failure != Failure.SLOW_DOWN && failure != Failure.TIMEOUT) return false;
		if (videoId.equals(autoRetriedId)) return false;
		autoRetriedId = videoId;
		long delay = failure == Failure.SLOW_DOWN
						? Math.max(AUTO_RETRY_MIN_MS, Math.min(AUTO_RETRY_MAX_MS, RequestPacer.pauseRemainingMs()))
						: AUTO_RETRY_MIN_MS;
		ToastUtils.show(activity, failure == Failure.SLOW_DOWN ? R.string.failure_retrying_slow_down
						: R.string.failure_retrying);
		playerView.postDelayed(() -> {
			if (Objects.equals(queuedId, videoId)) playAgain(videoId);
		}, delay);
		return true;
	}

	private void playAgain(@NonNull String videoId) {
		queuedId = null;
		play("https://www.youtube.com/watch?v=" + videoId);
	}

	private void showFailure(@NonNull Throwable error, @Nullable String videoId) {
		Failure failure = Failure.report(error);
		FailureDialog.show(activity, failure, error, videoId == null ? null : () -> {
			autoRetriedId = videoId;
			playAgain(videoId);
		});
	}

	public void hide() {
		this.queuedId = null;
		this.activeId = null;
		stateStore.setVideoId(null);
		cancelExtraction();
		if (task != null) task.cancel(true);
		activity.runOnUiThread(() -> {
			updateFloatingPlayer();
			// Nothing is playing, so close the mini player without restoring the full player
			if (inMiniPlayer) leaveMiniPlayer();
			controller.resetBrightness();
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

	public void setVideoEnabled(boolean enabled) {
		engine.setVideoEnabled(enabled);
	}

	public boolean canSuspendWatch() {
		return playerView.getVisibility() == View.VISIBLE;
	}

	/** A video is open and playing, so leaving the app keeps it in the floating window. */
	private boolean floatsOnLeave() {
		return AppSettings.floatingPlayer() && queuedId != null && engine.isPlaying();
	}

	/**
	 * Keeps the floating window's buttons and the open-on-leave setting current. Android 12 and
	 * newer open the window by themselves when the app is left.
	 */
	public void updateFloatingPlayer() {
		playerView.updatePiPParams(floatsOnLeave());
	}

	/** The user is leaving the app (home or recent apps); before Android 12 the window opens here. */
	public void onUserLeaveHint() {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && floatsOnLeave()) {
			playerView.enterPiP();
		}
	}

	/** Previous, play or pause, and next; the system adds expand and close. */
	@NonNull
	private List<RemoteAction> floatingActions() {
		QueueNav nav = engine.getQueueNavigationAvailability();
		boolean playing = engine.isPlaying();
		List<RemoteAction> actions = new ArrayList<>(3);
		actions.add(floatingAction(CONTROL_PREVIOUS, R.drawable.ic_previous, R.string.mini_bar_previous,
						nav.isPreviousActionEnabled()));
		actions.add(floatingAction(CONTROL_PLAY_PAUSE, playing ? R.drawable.ic_pause : R.drawable.ic_play,
						playing ? R.string.mini_bar_pause : R.string.mini_bar_play, true));
		actions.add(floatingAction(CONTROL_NEXT, R.drawable.ic_next, R.string.mini_bar_next,
						nav.isNextActionEnabled()));
		return actions;
	}

	@NonNull
	private RemoteAction floatingAction(int control, @DrawableRes int icon, @StringRes int title, boolean enabled) {
		Intent intent = new Intent(ACTION_FLOATING_CONTROL)
						.setPackage(activity.getPackageName())
						.putExtra(EXTRA_CONTROL, control);
		PendingIntent pending = PendingIntent.getBroadcast(activity, control, intent,
						PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
		String label = activity.getString(title);
		RemoteAction action = new RemoteAction(Icon.createWithResource(activity, icon), label, label, pending);
		action.setEnabled(enabled);
		return action;
	}

	private void onFloatingControl(int control) {
		switch (control) {
			case CONTROL_PREVIOUS -> engine.skipToPrevious();
			case CONTROL_NEXT -> engine.skipToNext();
			case CONTROL_PLAY_PAUSE -> {
				if (engine.isPlaying()) {
					engine.pause();
				} else {
					if (engine.getPlaybackState() == Player.STATE_ENDED) engine.seekTo(0L);
					engine.play();
				}
			}
			default -> {
			}
		}
	}

	private void listenToFloatingControls(boolean listen) {
		if (listen == (floatingControls != null)) return;
		if (listen) {
			BroadcastReceiver receiver = new BroadcastReceiver() {
				@Override
				public void onReceive(Context context, Intent intent) {
					onFloatingControl(intent.getIntExtra(EXTRA_CONTROL, 0));
				}
			};
			ContextCompat.registerReceiver(activity, receiver, new IntentFilter(ACTION_FLOATING_CONTROL),
							ContextCompat.RECEIVER_NOT_EXPORTED);
			floatingControls = receiver;
			return;
		}
		try {
			activity.unregisterReceiver(floatingControls);
		} catch (IllegalArgumentException e) {
			// Already gone with the activity
		}
		floatingControls = null;
	}

	/** Moves the video into the bottom bar; playback continues. */
	/**
	 * Minimizes the video: into the floating window over the app, or into the bottom bar, as set in
	 * the settings.
	 */
	public void enterInAppMiniPlayer() {
		inMiniPlayer = true;
		stateStore.setInMiniPlayer(true);
		if (controller.isFullscreen()) controller.exitFullscreenImmediately();
		controller.enterMiniPlayer();
		showMinimized();
		refreshQueueNav();
	}

	private void showMinimized() {
		if (AppSettings.floatingPlayer()) {
			miniBar().hide();
			playerView.show();
			floatingWindow().setPlaying(engine.isPlaying());
			floatingWindow().show();
		} else {
			if (floatingWindow != null) floatingWindow.hide();
			playerView.hide();
			updateMiniBar();
			miniBar().setPlaying(engine.isPlaying());
			miniBar().show();
		}
	}

	/** The minimize setting changed: a minimized video moves to the bar or the window right away. */
	public void applyMinimizeMode() {
		if (inMiniPlayer && !wasInPip) showMinimized();
	}

	/** Where the floating window is, when minimizing goes there; null for the bottom bar. */
	@Nullable
	public RectF floatingTarget() {
		return AppSettings.floatingPlayer() ? floatingWindow().bounds() : null;
	}

	@NonNull
	private FloatingWindow floatingWindow() {
		FloatingWindow window = floatingWindow;
		if (window == null) {
			window = new FloatingWindow(activity, playerView, new FloatingWindow.Actions() {
				@Override
				public void expand() {
					WatchDrag drag = watchDrag;
					if (drag == null || !drag.expand()) restoreFromMiniPlayer();
				}

				@Override
				public void playPause() {
					togglePlayback();
				}

				@Override
				public void close() {
					closeMinimized();
				}
			});
			floatingWindow = window;
		}
		return window;
	}

	/**
	 * Restores the full player from the mini player. Does nothing if the mini player isn't shown,
	 * so a late call after {@link #hide()} can't bring back an empty player.
	 */
	public void exitInAppMiniPlayer() {
		if (!inMiniPlayer) return;
		inMiniPlayer = false;
		stateStore.setInMiniPlayer(false);
		miniBar().hide();
		if (floatingWindow != null) floatingWindow.hide();
		playerView.fitToWidth();
		playerView.show();
		controller.exitMiniPlayer();
	}

	private void leaveMiniPlayer() {
		inMiniPlayer = false;
		stateStore.setInMiniPlayer(false);
		miniBar().hide();
		if (floatingWindow != null) floatingWindow.hide();
		controller.exitMiniPlayer();
	}

	private void togglePlayback() {
		if (engine.isPlaying()) {
			engine.pause();
		} else {
			if (engine.getPlaybackState() == Player.STATE_ENDED) engine.seekTo(0L);
			engine.play();
		}
	}

	private void closeMinimized() {
		Runnable close = onClose;
		hide();
		if (close != null) close.run();
	}

	@NonNull
	private MiniBar miniBar() {
		MiniBar bar = miniBar;
		if (bar == null) {
			bar = new MiniBar(activity);
			bar.setActions(
							this::restoreFromMiniPlayer,
							engine::skipToPrevious,
							this::togglePlayback,
							engine::skipToNext,
							this::closeMinimized);
			miniBar = bar;
		}
		return bar;
	}

	private void updateMiniBar() {
		miniBar().setVideo(currentTitle, currentAuthor, currentThumbnailUrl);
	}

	public void restoreInAppMiniPlayerUiIfNeeded() {
		if (!inMiniPlayer) return;
		showMinimized();
	}

	/**
	 * Details of the video that is playing, or null before one has loaded.
	 */
	@Nullable
	public VideoDetails currentDetails() {
		return Objects.equals(activeId, currentDetails == null ? null : currentDetails.getId()) ? currentDetails : null;
	}

	public void setLongPressAction(@Nullable Runnable action) {
		controller.setLongPressAction(action);
	}

	public void setWatchDrag(@Nullable WatchDrag drag) {
		this.watchDrag = drag;
		controller.setWatchDrag(drag);
	}

	/** Brings the video back from the bottom bar, as tapping the bar does. */
	public void restoreFromMiniPlayer() {
		Runnable restore = onRestore;
		if (inMiniPlayer && restore != null) restore.run();
	}

	/** Runs every time playback starts or resumes. */
	public void setOnStartedPlaying(@Nullable Runnable listener) {
		onStartedPlaying = listener;
	}

	/** Receives the details of each video once it starts, on the main thread. */
	public void setDetailsListener(@Nullable Consumer<VideoDetails> listener) {
		this.detailsListener = listener;
	}

	public void setMiniPlayerCallbacks(@Nullable Runnable onRestore, @Nullable Runnable onClose) {
		this.onRestore = onRestore;
		this.onClose = onClose;
	}

	/**
	 * @param closed the floating window was closed; otherwise it was expanded back into the app
	 */
	public void onPictureInPictureModeChanged(boolean isInPiP, boolean closed) {
		controller.onPictureInPictureModeChanged(isInPiP);
		listenToFloatingControls(isInPiP);
		if (isInPiP) {
			// A brightness set by swiping would otherwise stay on over other apps
			controller.resetBrightness();
			// In the bottom bar the picture is hidden, and the in-app window is scaled down; the
			// system window shows the video alone, at full size
			if (inMiniPlayer) {
				if (floatingWindow != null) floatingWindow.hide();
				playerView.show();
				miniBar().hide();
			}
		} else if (wasInPip && inMiniPlayer) {
			if (!closed && onRestore != null) {
				onRestore.run();
			} else {
				showMinimized();
			}
		}
		wasInPip = isInPiP;
		updateFloatingPlayer();
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
		if (network != null) network.removeListener(networkListener);
		activity.runOnUiThread(() -> {
			listenToFloatingControls(false);
			playerView.updatePiPParams(false);
		});
		inMiniPlayer = false;
		if (miniBar != null) miniBar.hide();
		if (floatingWindow != null) floatingWindow.hide();
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
