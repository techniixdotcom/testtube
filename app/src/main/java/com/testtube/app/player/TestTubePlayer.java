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
import com.testtube.app.external.ExternalException;
import com.testtube.app.external.ExternalIds;
import com.testtube.app.external.ExternalSources;
import com.testtube.app.external.ExternalVideo;
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
import com.testtube.app.ui.Dialogs;
import com.testtube.app.ui.FailureDialog;
import com.testtube.app.util.NetworkWatcher;
import com.testtube.app.util.ToastUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
	/** Video that already got its one automatic retry. */
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
	/** Other site's link playing or loading (TestTube+), for retries. */
	@Nullable
	private String externalUrl;
	/** Replaces the watch screen's link (no Back step). */
	@Nullable
	private Consumer<String> watchReplacer;
	/**
	 * Reads other sites' links. Own thread: a SoundCloud preview waits on a YouTube search, which
	 * mustn't wait on the pool it may be running in.
	 */
	private static final ExecutorService EXTERNAL_READER = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "testtube-external");
		thread.setDaemon(true);
		return thread;
	});

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

	/** Switches quality when moving between Wi-Fi and mobile data. */
	public void watchNetwork(@NonNull NetworkWatcher watcher) {
		if (network != null) network.removeListener(networkListener);
		network = watcher;
		watcher.addListener(networkListener);
	}

	/** Replays the open video in the current network's quality. */
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
				if (engine.isExternal()) {
					// other site's link may have expired, read it again once
					if (Engine.playbackRecoveryReason(error) != null && refreshExternal()) return;
					String failedId = activeId;
					String url = externalUrl;
					if (failedId != null && url != null) {
						onExternalFailed(failedId, url, error);
					} else {
						showFailure(error, failedId);
					}
					return;
				}
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
	 * The video itself can't play here (broken stream, format, DRM). Network errors don't count or a
	 * bad connection would skip a whole playlist.
	 */
	private static boolean cannotEverPlay(@NonNull PlaybackException error) {
		int code = error.errorCode;
		return code >= PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED && code < 5000
						|| code >= PlaybackException.ERROR_CODE_DRM_UNSPECIFIED && code < 7000;
	}

	private void updateServiceProgress(boolean isPlaying) {
		if (playbackSvc != null) {
			playbackSvc.updateProgress(engine.position(), engine.getPlaybackRate(), isPlaying, engine.wantsToPlay());
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
		// before reading "v=", Facebook links have one too
		if (ExternalIds.isExternalUrl(url)) {
			playExternal(url);
			return;
		}
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

		// SponsorBlock loads in parallel so it never delays the start; segments and markers apply when
		// they arrive
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
								// removed, private, age-restricted, blocked…: in a playlist or the queue say why and move on
								// instead of stopping
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
	 * Plays a Facebook or SoundCloud item (TestTube+) like a YouTube video: same player, mini player,
	 * floating window, notification, queue, autoplay.
	 */
	private void playExternal(@NonNull String url) {
		String id = ExternalIds.idOf(url);
		if (id == null || Objects.equals(this.queuedId, id)) return;
		this.queuedId = id;
		this.externalUrl = url;
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
			currentThumbnailUrl = null;
			if (inMiniPlayer) {
				updateMiniBar();
			} else {
				playerView.fitToWidth();
				playerView.show();
			}
		});

		cancelExtraction();
		if (task != null) task.cancel(true);
		Context app = activity.getApplicationContext();
		task = CompletableFuture.<ExternalVideo>supplyAsync(() -> readExternal(app, url), EXTERNAL_READER)
						.thenAccept(video -> activity.runOnUiThread(() -> startExternal(id, video)))
						.exceptionally(e -> {
							Throwable cause = e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
							activity.runOnUiThread(() -> onExternalFailed(id, url, cause));
							return null;
						});
	}

	@NonNull
	private static ExternalVideo readExternal(@NonNull Context context, @NonNull String url) {
		try {
			return ExternalSources.resolve(context, url);
		} catch (ExternalException e) {
			throw new CompletionException(e);
		}
	}

	private void startExternal(@NonNull String id, @NonNull ExternalVideo video) {
		if (!Objects.equals(this.queuedId, id)) return;
		VideoDetails details = video.toDetails();
		playerView.setTitle(details.getTitle());
		try {
			engine.playExternal(video);
		} catch (RuntimeException e) {
			onExternalFailed(id, video.pageUrl(), e);
			return;
		}
		autoRetriedId = null;
		this.activeId = id;
		activeDurationSeconds = video.durationMs() / 1000L;
		stateStore.setVideoId(id);
		currentTitle = details.getTitle();
		currentAuthor = details.getAuthor();
		currentDetails = details;
		currentThumbnailUrl = details.getThumbnailUrl();
		updateMiniBar();
		if (detailsListener != null) detailsListener.accept(details);
		if (playbackSvc != null) {
			PlaybackService.start(activity);
			playbackSvc.showNotification(details.getTitle(), details.getAuthor(), details.getThumbnailUrl(),
							video.durationMs());
		}
		refreshQueueNav();
	}

	/**
	 * Other site's link failed: a SoundCloud preview plays from YouTube, queue or playlist skips it,
	 * otherwise show the reason with Try again.
	 */
	private void onExternalFailed(@NonNull String id, @NonNull String url, @NonNull Throwable cause) {
		if (!Objects.equals(this.queuedId, id) || Failure.isCancellation(cause)) return;
		ExternalException external = cause instanceof ExternalException e ? e : null;
		if (external != null && external.youtubeVideoId != null) {
			ToastUtils.show(activity, external.message);
			queuedId = null;
			String youtube = ExternalIds.youtubeUrl(external.youtubeVideoId);
			Consumer<String> replacer = watchReplacer;
			if (replacer != null) {
				replacer.accept(youtube);
			} else {
				play(youtube);
			}
			return;
		}
		int message = external != null ? external.message : R.string.failure_other;
		if (engine.skipUnavailable(id)) {
			ToastUtils.show(activity, message);
			return;
		}
		Dialogs.builder(activity)
						.setTitle(R.string.failure_title)
						.setMessage(message)
						.setPositiveButton(R.string.failure_retry, (dialog, which) -> {
							if (!Objects.equals(this.queuedId, id)) return;
							queuedId = null;
							play(url);
						})
						.setNegativeButton(R.string.cancel, null)
						.show();
	}

	/** How a link replaces the watch screen's one (SoundCloud preview going to YouTube). */
	public void setWatchReplacer(@Nullable Consumer<String> replacer) {
		this.watchReplacer = replacer;
	}

	/**
	 * Other site's video stopped loading (link expired): reread and continue at the same spot.
	 * @return true if a refresh started
	 */
	private boolean refreshExternal() {
		String id = activeId;
		String url = externalUrl;
		if (id == null || url == null || !Objects.equals(queuedId, id) || !engine.consumeRecoveryAttempt()) {
			return false;
		}
		long position = Math.max(0L, engine.position());
		Context app = activity.getApplicationContext();
		CompletableFuture.<ExternalVideo>supplyAsync(() -> readExternal(app, url), EXTERNAL_READER)
						.whenComplete((video, error) -> activity.runOnUiThread(() -> {
							if (!Objects.equals(this.activeId, id) || !Objects.equals(this.queuedId, id)) return;
							if (error != null || video == null) {
								Throwable cause = error instanceof CompletionException && error.getCause() != null
												? error.getCause() : error;
								onExternalFailed(id, url, cause != null ? cause : new IllegalStateException("no video"));
								return;
							}
							engine.replaceExternal(video, position);
						}));
		return true;
	}

	/**
	 * Picks streams by preferred quality, always original audio. A track picked in the menu only
	 * applies to that video.
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
	 * Re-extracts and resumes at the same spot. Used when every stream got rejected (expired or
	 * blocked URLs).
	 * @return true if a refresh started
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
	 * After a bot check or timeout, retries once by itself when the pause ends so a hiccup needs no
	 * tap. Nobody wants to mash retry because YouTube had a moment. A short notice shows meanwhile.
	 * @return true if a retry got scheduled
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
		String url = externalUrl;
		if (ExternalIds.isExternal(videoId) && url != null) {
			play(url);
			return;
		}
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
			// nothing playing, close the mini player without restoring
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

	/** Something's open and playing, so leaving the app keeps it floating. */
	private boolean floatsOnLeave() {
		return AppSettings.floatingPlayer() && queuedId != null && engine.isPlaying();
	}

	/** Keeps the floating window's buttons and auto-enter current. 12+ opens it by itself on leave. */
	public void updateFloatingPlayer() {
		playerView.updatePiPParams(floatsOnLeave());
	}

	/** User leaving (home or recents); before 12 the window opens here. */
	public void onUserLeaveHint() {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && floatsOnLeave()) {
			playerView.enterPiP();
		}
	}

	/** Prev, play/pause, next; the system adds expand and close. */
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
			// gone with the activity
		}
		floatingControls = null;
	}

	/** Moves the video to the bottom bar, keeps playing. */
	/** Minimizes to the floating window or the bottom bar, per the setting. */
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

	/** Minimize setting changed, a minimized video moves now. */
	public void applyMinimizeMode() {
		if (inMiniPlayer && !wasInPip) showMinimized();
	}

	/** Floating window bounds when minimizing goes there; null for the bar. */
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
	 * Restores the full player. No-op unless the mini player shows, so a late call after
	 * {@link #hide()} can't bring back an empty player.
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

	/** Details of the playing video, or null before one loaded. */
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

	/** Brings the video back from the bar, like tapping it. */
	public void restoreFromMiniPlayer() {
		Runnable restore = onRestore;
		if (inMiniPlayer && restore != null) restore.run();
	}

	/** Runs whenever playback starts or resumes. */
	public void setOnStartedPlaying(@Nullable Runnable listener) {
		onStartedPlaying = listener;
	}

	/** Likes, chapters and avatar that arrived after start. Main thread. */
	public void onExtras(@NonNull PlaybackDetails details) {
		if (!Objects.equals(activeId, details.video().getId())) return;
		engine.applyExtras(details);
		currentDetails = details.video();
		if (detailsListener != null) detailsListener.accept(details.video());
	}

	/** Gets each video's details once it starts. Main thread. */
	public void setDetailsListener(@Nullable Consumer<VideoDetails> listener) {
		this.detailsListener = listener;
	}

	public void setMiniPlayerCallbacks(@Nullable Runnable onRestore, @Nullable Runnable onClose) {
		this.onRestore = onRestore;
		this.onClose = onClose;
	}

	/** @param closed window was closed; otherwise expanded back into the app */
	public void onPictureInPictureModeChanged(boolean isInPiP, boolean closed) {
		controller.onPictureInPictureModeChanged(isInPiP);
		listenToFloatingControls(isInPiP);
		if (isInPiP) {
			// a swiped brightness would otherwise stick over other apps
			controller.resetBrightness();
			// in the bar the picture is hidden and the in-app window is scaled down; the system window shows
			// the video alone at full size
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
