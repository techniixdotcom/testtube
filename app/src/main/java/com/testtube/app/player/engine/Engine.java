package com.testtube.app.player.engine;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.common.text.CueGroup;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.exoplayer.DecoderCounters;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;

import com.testtube.app.Constant;
import com.testtube.app.R;
import com.testtube.app.browser.TabManager;
import com.testtube.app.extractor.Delivery;
import com.testtube.app.extractor.FeedClient;
import com.testtube.app.extractor.FeedItem;
import com.testtube.app.extractor.DeliveryCatalog;
import com.testtube.app.extractor.PlaybackDetails;
import com.testtube.app.extractor.PlaybackMode;
import com.testtube.app.extractor.PlaybackPlan;
import com.testtube.app.extractor.PlaybackPlanner;
import com.testtube.app.extractor.StreamCandidate;
import com.testtube.app.extractor.StreamCatalog;
import com.testtube.app.extractor.VideoDetails;
import com.testtube.app.extractor.YoutubeExtractor;
import com.testtube.app.filter.ContentFilters;
import com.testtube.app.history.WatchHistory;
import com.testtube.app.util.ToastUtils;
import com.testtube.app.player.TestTubePlayerView;
import com.testtube.app.player.common.PlayerLoopMode;
import com.testtube.app.player.common.PlayerPreferences;
import com.testtube.app.player.common.PlayerUtils;
import com.testtube.app.player.queue.QueueItem;
import com.testtube.app.player.queue.QueueNav;
import com.testtube.app.player.queue.QueueRepository;
import com.testtube.app.player.sponsor.SponsorBlockManager;
import com.testtube.app.util.StringUtils;
import com.testtube.app.util.UrlUtils;

import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamSegment;
import org.schabi.newpipe.extractor.stream.SubtitlesStream;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;



/**
 * Coordinates playback state and queue navigation.
 */
@UnstableApi
public class Engine {
	private static final String TAG = "TestTubePlayback";
	static final String NO_PLAYABLE_SOURCE_MESSAGE = "No supported playable stream URL in StreamCatalog";
	private static final int SAFE_ZONE_MS = 5000;
	private static final long TICK_MS = 1000L;
	private static final long MIN_TICK_MS = 50L;
	private static final long PERSIST_INTERVAL_MS = 5000L;
	/**
	 * Videos shorter than this loop instead of moving on (very short clips, like YouTube does).
	 */
	private static final long LOOPING_VIDEO_MAX_MS = 5000L;
	private static final long MIN_WATCHED_TAIL_MS = 10_000L;
	private static final long MAX_WATCHED_TAIL_MS = 60_000L;
	private static final int MAX_RECOVERIES_PER_VIDEO = 8;
	private static final String UNKNOWN_CLIENT = "UNKNOWN";
	@NonNull
	private final ExoPlayer player;
	@NonNull
	private final PlayerPreferences prefs;
	@NonNull
	private final TabManager tabManager;
	@NonNull
	private final SponsorBlockManager sponsor;
	@NonNull
	private final QueueRepository queueRepository;
	@NonNull
	private final PlayerDataSource sources;
	private final Handler handler = new Handler(Looper.getMainLooper());
	@NonNull
	private PlayerLoopMode loopMode = PlayerLoopMode.PLAYLIST_NEXT;
	@Nullable
	private String videoId;
	private final Runnable onTimeUpdate = new Runnable() {
		@Override
		public void run() {
			if (!player.isPlaying()) return;
			long pos = player.getCurrentPosition();
			long duration = player.getDuration();
			if (videoId != null && duration > 0) {
				contentFilters.recordProgress(videoId, pos, duration);
				if (duration - pos <= PREFETCH_BEFORE_END_MS && !videoId.equals(prefetchedFor)) {
					prefetchedFor = videoId;
					prefetchedNextUrl = null;
					prefetchNext(videoId);
				}
			}
			long now = SystemClock.elapsedRealtime();
			if (now - lastPersistAt >= PERSIST_INTERVAL_MS) {
				lastPersistAt = now;
				saveProgress(pos, duration);
			}
			// Skip sponsor segments, and wake up exactly when the next one starts.
			long delay = TICK_MS;
			for (final long[] segment : sponsor.getSegments()) {
				if (pos >= segment[0] && pos < segment[1]) {
					player.seekTo(segment[1]);
					delay = MIN_TICK_MS;
					break;
				}
				if (segment[0] > pos) delay = Math.min(delay, segment[0] - pos);
			}
			float speed = player.getPlaybackParameters().speed;
			if (speed > 0f) delay = (long) (delay / speed);
			handler.postDelayed(this, Math.max(MIN_TICK_MS, delay));
		}
	};
	private long lastPersistAt;

	/**
	 * Persists playback progress. Once the end of the video is reached the saved position is
	 * dropped, so a finished video starts from the beginning when it is opened again.
	 */
	private void saveCurrentProgress() {
		if (player.getPlaybackState() == Player.STATE_IDLE) return;
		saveProgress(player.getCurrentPosition(), player.getDuration());
	}

	private void saveProgress(long pos, long duration) {
		if (videoId == null || duration <= 0) return;
		if (pos >= duration - watchedTailMs(duration)) {
			if (!watchedMarked) {
				prefs.clearProgress(videoId);
				watchedMarked = true;
			}
		} else {
			watchedMarked = false;
			if (pos > SAFE_ZONE_MS) {
				prefs.persistProgress(videoId, pos, duration, TimeUnit.MILLISECONDS);
			}
		}
	}
	@Nullable
	private VideoDetails videoDetails;
	@NonNull
	private List<StreamSegment> segments = List.of();
	@NonNull
	private List<SubtitlesStream> subtitles = List.of();
	@Nullable
	private StreamCatalog streamCatalog;
	@Nullable
	private DeliveryCatalog deliveries;
	@Nullable
	private PlaybackPlan playbackPlan;
	@Nullable
	private VideoStream videoStream;
	@NonNull
	private final Set<String> failedAdaptiveCandidates = new HashSet<>();
	@NonNull
	private final Set<String> failedClients = new HashSet<>();
	@NonNull
	private final YoutubeExtractor extractor;
	@NonNull
	private final ContentFilters contentFilters;
	@NonNull
	private final WatchHistory watchHistory;
	/**
	 * One background thread for history writes, so they happen in order and never queue behind
	 * extraction work.
	 */
	private static final java.util.concurrent.ExecutorService HISTORY_WRITER =
					java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
						Thread thread = new Thread(runnable, "testtube-history");
						thread.setDaemon(true);
						return thread;
					});
	@NonNull
	private final Context appContext;
	private int recoveries;
	private static final int MAX_HISTORY = 50;
	private static final int SUGGESTION_POOL = 5;
	private static final int SUGGESTION_RETRIES = 3;
	private static final long SUGGESTION_RETRY_DELAY_MS = 1500L;
	private static final long PREFETCH_BEFORE_END_MS = 30_000L;
	@Nullable
	private String prefetchedFor;
	@Nullable
	private String prefetchedNextUrl;
	/**
	 * Videos played before the current one, newest last, for the previous button.
	 */
	@NonNull
	private final java.util.ArrayDeque<String> history = new java.util.ArrayDeque<>();
	@Nullable
	private String returningToId;
	private boolean watchedMarked;
	private long autoplayToken;
	@Nullable
	private FeedClient feedClient;
	@Nullable
	private String currentTitle;
	@Nullable
	private String currentAuthor;

	public Engine(@NonNull Context context,
	              @NonNull TestTubePlayerView playerView,
	              @Nullable SimpleCache simpleCache,
	              @NonNull PlayerPreferences prefs,
	              @NonNull TabManager tabManager,
	              @NonNull SponsorBlockManager sponsor,
	              @NonNull QueueRepository queueRepository,
	              @NonNull YoutubeExtractor extractor,
	              @NonNull ContentFilters contentFilters,
	              @NonNull WatchHistory watchHistory) {
		this.watchHistory = watchHistory;
		this.extractor = extractor;
		this.contentFilters = contentFilters;
		this.appContext = context;
		this.prefs = prefs;
		this.tabManager = tabManager;
		this.sponsor = sponsor;
		this.queueRepository = queueRepository;
		this.sources = new PlayerDataSource(simpleCache);
		DefaultTrackSelector trackSelector = new DefaultTrackSelector(context, new AdaptiveTrackSelection.Factory());
		trackSelector.setParameters(params(trackSelector).setTunnelingEnabled(true).build());
		this.player = new ExoPlayer.Builder(context)
						.setTrackSelector(trackSelector)
						.setLoadControl(PlayerLoadControl.create())
						.setAudioAttributes(new AudioAttributes.Builder()
										.setUsage(C.USAGE_MEDIA)
										.setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
										.build(), true)
						.setWakeMode(C.WAKE_MODE_NETWORK)
						.setHandleAudioBecomingNoisy(true)
						.setUsePlatformDiagnostics(false)
						.setMediaSourceFactory(
										new DefaultMediaSourceFactory(context)
														.setLiveMaxSpeed(1.0f)
						).build();
		this.player.addListener(new Player.Listener() {

			@Override
			public void onPlaybackStateChanged(int state) {
				if (state == Player.STATE_ENDED) {
					if (videoId != null) {
						prefs.clearProgress(videoId);
						contentFilters.recordProgress(videoId, 1L, 1L);
					}
					if (isShortVideo()) {
						player.seekTo(0);
						player.play();
						return;
					}
					if (loopMode.skipsToNextOnEnded()) {
						String endedId = videoId;
						skipToNext(false);
						removeFromQueue(endedId);
						return;
					}
					if (loopMode.selectsRandomPlaylistItemOnEnded()) {
						playRandomPlaylistItem();
					}
				}
			}

			@Override
			public void onIsPlayingChanged(boolean isPlaying) {
				handler.removeCallbacks(onTimeUpdate);
				if (isPlaying) handler.post(onTimeUpdate);
			}

			@Override
			public void onCues(@NonNull CueGroup cueGroup) {
				playerView.cueing(cueGroup);
			}

			@Override
			public void onTracksChanged(@NonNull Tracks tracks) {
				applyPreferredVideoTrack();
			}
		});
		playerView.setPlayer(this.player);
	}

	@Nullable
	private static VideoStream selectedVideo(@NonNull PlaybackPlan plan) {
		if (plan.getVideoCandidate() != null && plan.getVideoCandidate().getVideoStream() != null) {
			return plan.getVideoCandidate().getVideoStream();
		}
		if (plan.getMuxedCandidate() != null && plan.getMuxedCandidate().getVideoStream() != null) {
			return plan.getMuxedCandidate().getVideoStream();
		}
		return null;
	}

	@Nullable
	private static AudioStream selectedAudio(@NonNull PlaybackPlan plan) {
		if (plan.getAudioCandidate() != null && plan.getAudioCandidate().getAudioStream() != null) {
			return plan.getAudioCandidate().getAudioStream();
		}
		return null;
	}

	private static long durationMs(@NonNull VideoDetails details) {
		Long duration = details.getDuration();
		if (duration == null || duration <= 0L) return 0L;
		return TimeUnit.SECONDS.toMillis(duration);
	}

	@Nullable
	private static String candidateKey(@Nullable StreamCandidate candidate) {
		if (candidate == null) return null;
		return candidate.getKind() + "|" + candidate.getSourceClient() + "|" + candidate.getUrl();
	}

	@NonNull
	private static String clientKey(@NonNull StreamCandidate candidate) {
		String client = candidate.getSourceClient();
		return client == null || client.isBlank() ? UNKNOWN_CLIENT : client;
	}

	/**
	 * A queued video leaves the queue once it has been played, whether it ran to the end or was
	 * skipped.
	 */
	private void removeFromQueue(@Nullable String id) {
		if (id != null && queueRepository.containsVideo(id)) {
			queueRepository.remove(id);
		}
	}

	/**
	 * Length of the tail of a video after which it counts as watched.
	 */
	static long watchedTailMs(long durationMs) {
		return Math.max(MIN_WATCHED_TAIL_MS, Math.min(MAX_WATCHED_TAIL_MS, durationMs / 20L));
	}

	@NonNull
	private static DefaultTrackSelector.Parameters.Builder params(@NonNull DefaultTrackSelector trackSelector) {
		return Objects.requireNonNull(trackSelector.buildUponParameters());
	}

	@NonNull
	private DefaultTrackSelector trackSelector() {
		return (DefaultTrackSelector) Objects.requireNonNull(player.getTrackSelector());
	}

	@Nullable
	private static StreamCandidate findAudioCandidate(@NonNull StreamCatalog catalog,
	                                                  @NonNull AudioStream stream) {
		String content = stream.getContent();
		for (StreamCandidate candidate : catalog.getAudioCandidates()) {
			if (candidate.getAudioStream() != null
							&& content.equals(candidate.getAudioStream().getContent())) {
				return candidate;
			}
		}
		return null;
	}

	private boolean isShortVideo() {
		long duration = player.getDuration();
		return duration > 0 && duration < LOOPING_VIDEO_MAX_MS;
	}

	public boolean isPlaying() {
		return this.player.isPlaying();
	}

	public boolean isCurrentVideoInQueue() {
		String watchId = watchVideoId();
		return queueRepository.containsVideo(watchId);
	}

	public void play(@NonNull PlaybackDetails details) {
		VideoDetails video = details.video();
		PlaybackPlan plan = details.plan();
		List<SubtitlesStream> subtitles = details.subtitles();
		if (!Objects.equals(this.videoId, video.getId())) {
			saveCurrentProgress();
			if (Objects.equals(returningToId, video.getId())) {
				returningToId = null;
			} else if (this.videoId != null && !this.videoId.equals(history.peekLast())) {
				history.addLast(this.videoId);
				while (history.size() > MAX_HISTORY) history.removeFirst();
			}
			// Leaving a queued video (watched to the end or skipped) takes it out of the queue.
			removeFromQueue(this.videoId);
			failedAdaptiveCandidates.clear();
			failedClients.clear();
			recoveries = 0;
			autoplayToken++;
			// History writes serialize whole lists, so they stay off the main thread.
			String id = video.getId();
			String title = video.getTitle();
			String author = video.getAuthor();
			currentTitle = title;
			currentAuthor = author;
			String thumbnail = video.getThumbnailUrl();
			HISTORY_WRITER.execute(() -> {
				watchHistory.record(id, title, author, thumbnail);
				prefs.recordPlayed(id);
				queueRepository.clearPlayNext(id);
			});
		}
		watchedMarked = false;
		this.videoId = video.getId();
		this.videoDetails = video;
		this.streamCatalog = details.catalog();
		this.deliveries = details.deliveries();
		this.playbackPlan = plan;
		this.segments = details.segments();
		this.subtitles = subtitles;
		applyPlaybackTrackMode();

		this.videoStream = selectedVideo(plan);
		boolean enabled = this.prefs.isSubtitleEnabled();
		setSubtitlesEnabled(enabled);
		String saved = this.prefs.getSubtitleLanguage();
		if (enabled && saved != null && !saved.isEmpty() && !subtitles.isEmpty()) {
			setSubtitleLanguage(saved);
		}

		long duration = durationMs(video);
		this.player.setMediaSource(PlaybackSourceFactory.create(sources, details, plan));
		this.player.setPlaybackParameters(new PlaybackParameters(1.0f));

		// Resume position
		long resumePos = prefs.getResumePosition(videoId);
		if (resumePos > SAFE_ZONE_MS && resumePos < duration - watchedTailMs(duration)) {
			this.player.seekTo(resumePos);
		}

		this.player.prepare();
		this.player.setPlayWhenReady(true);
	}

	public void play() {
		this.player.play();
	}

	/**
	 * Reloads the same video with freshly extracted stream URLs and continues at the given
	 * position. Used when every stream of the current extraction has been rejected.
	 */
	public void replace(@NonNull PlaybackDetails details, long positionMs) {
		VideoDetails video = details.video();
		PlaybackPlan plan = details.plan();
		float speed = player.getPlaybackParameters().speed;
		failedAdaptiveCandidates.clear();
		failedClients.clear();
		this.videoId = video.getId();
		this.videoDetails = video;
		this.streamCatalog = details.catalog();
		this.deliveries = details.deliveries();
		this.playbackPlan = plan;
		this.segments = details.segments();
		this.subtitles = details.subtitles();
		applyPlaybackTrackMode();
		this.videoStream = selectedVideo(plan);
		player.setMediaSource(PlaybackSourceFactory.create(sources, details, plan));
		player.seekTo(Math.max(0L, positionMs));
		player.setPlaybackParameters(new PlaybackParameters(speed));
		player.prepare();
		player.setPlayWhenReady(true);
	}

	@Nullable
	public String getVideoId() {
		return videoId;
	}

	/**
	 * Tries to continue playback with other streams of the current extraction after a stream was
	 * rejected. An HTTP 403 blocks the whole InnerTube client that produced the stream, because
	 * YouTube enforces its restrictions per client, so retrying other formats of the same client
	 * would only fail again. Returns false when nothing usable is left, in which case the caller
	 * should re-extract the video.
	 */
	public boolean recoverFromPlaybackError(@NonNull PlaybackException error) {
		PlaybackRecoveryReason reason = playbackRecoveryReason(error);
		if (reason == null) {
			return false;
		}
		State state = state();
		if (state == null || isLiveMode(state.plan())) {
			return false;
		}
		if (recoveries >= MAX_RECOVERIES_PER_VIDEO) {
			Log.w(TAG, "recovery limit reached videoId=" + state.video().getId());
			return false;
		}
		rememberFailedCandidates(state.plan(), reason == PlaybackRecoveryReason.HTTP_403);
		PlaybackPlan fallback = PlaybackPlanner.adaptiveFallbackPlan(
						state.deliveries(),
						prefs.getPreferredQuality(),
						null,
						this::isBlocked);
		if (fallback == null) {
			fallback = PlaybackPlanner.muxedFallbackPlan(
							state.deliveries(),
							prefs.getPreferredQuality(),
							this::isBlocked);
		}
		if (fallback == null) {
			return false;
		}
		recoveries++;
		return recoverWithPlan(state, fallback, reason);
	}

	/**
	 * Counts a re-extraction attempt against the per-video recovery budget.
	 *
	 * @return false when the budget is exhausted and no further attempt should be made
	 */
	public boolean consumeRecoveryAttempt() {
		if (recoveries >= MAX_RECOVERIES_PER_VIDEO) {
			return false;
		}
		recoveries++;
		return true;
	}

	private boolean recoverWithPlan(@NonNull State state,
	                                @NonNull PlaybackPlan fallback,
	                                @NonNull PlaybackRecoveryReason reason) {
		long position = Math.max(0L, player.getCurrentPosition());
		PlaybackParameters speed = player.getPlaybackParameters();
		boolean playWhenReady = player.getPlayWhenReady();
		try {
			playbackPlan = fallback;
			videoStream = selectedVideo(fallback);
			player.setMediaSource(PlaybackSourceFactory.create(sources,
							new PlaybackDetails(state.video(), state.catalog(), state.deliveries(),
											fallback, segments, subtitles),
							fallback));
			player.seekTo(position);
			player.setPlaybackParameters(speed);
			player.prepare();
			player.setPlayWhenReady(playWhenReady);
			Log.w(TAG, "recovered from " + reason.logLabel + " with " + fallback.getMode()
							+ " videoId=" + state.video().getId());
			return true;
		} catch (RuntimeException e) {
			Log.w(TAG, fallback.getMode() + " fallback failed", e);
			return false;
		}
	}

	private void rememberFailedCandidates(@NonNull PlaybackPlan plan, boolean blockClients) {
		for (StreamCandidate candidate : new StreamCandidate[]{
						plan.getVideoCandidate(), plan.getAudioCandidate(), plan.getMuxedCandidate()}) {
			if (candidate == null) continue;
			String key = candidateKey(candidate);
			if (key != null) failedAdaptiveCandidates.add(key);
			if (blockClients) failedClients.add(clientKey(candidate));
		}
	}

	private boolean isBlocked(@NonNull StreamCandidate candidate) {
		return failedAdaptiveCandidates.contains(candidateKey(candidate))
						|| failedClients.contains(clientKey(candidate));
	}

	public void pause() {
		this.player.pause();
		saveCurrentProgress();
	}

	public void seekTo(long pos) {
		long duration = this.player.getDuration();
		this.player.seekTo(Math.max(0L, duration > 0 ? Math.min(duration, pos) : pos));
	}

	public void seekBy(long offset) {
		long target = Math.max(0L, this.player.getCurrentPosition() + offset);
		long duration = this.player.getDuration();
		if (duration > 0) target = Math.min(duration, target);
		this.player.seekTo(target);
	}

	public float getPlaybackRate() {
		return this.player.getPlaybackParameters().speed;
	}

	public void addListener(@NonNull Player.Listener listener) {
		this.player.addListener(listener);
	}

	public VideoSize getVideoSize() {
		return this.player.getVideoSize();
	}

	public void setSubtitlesEnabled(boolean enabled) {
		this.prefs.setSubtitleEnabled(enabled);
		this.player.setTrackSelectionParameters(this.player.getTrackSelectionParameters().buildUpon()
						.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !enabled)
						.build());
	}

	@Nullable
	public String getSubtitleLanguage() {
		return this.prefs.getSubtitleLanguage();
	}

	public void setSubtitleLanguage(@Nullable String language) {
		if (language == null) return;
		this.prefs.setSubtitleEnabled(true);
		this.prefs.setSubtitleLanguage(language);
		Tracks tracks = this.player.getCurrentTracks();
		for (final Tracks.Group group : tracks.getGroups()) {
			if (group.getType() == C.TRACK_TYPE_TEXT) {
				for (int i = 0; i < group.length; i++) {
					Format format = group.getTrackFormat(i);
					if (language.equals(format.label) || language.equals(format.language)) {
						this.player.setTrackSelectionParameters(this.player.getTrackSelectionParameters().buildUpon()
										.clearOverrides()
										.setOverrideForType(new TrackSelectionOverride(group.getMediaTrackGroup(), i))
										.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
										.build());
						return;
					}
				}
			}
		}
	}

	public long position() {
		return this.player.getCurrentPosition();
	}

	public void skipToNext() {
		skipToNext(true);
	}

	/**
	 * @param manual true when the user asked for the next video (button, notification, bar);
	 *               false when the current video ended on its own
	 */
	public void skipToNext(boolean manual) {
		boolean queueEnabled = queueRepository.isEnabled();
		boolean hasQueueItems = queueRepository.hasItems();
		String watchId = watchVideoId();
		boolean hasPlaylist = tabManager.watchHasPlaylist();
		boolean queueContext = queueEnabled && hasQueueItems;
		boolean playlistContext = !queueContext && hasPlaylist;
		if (queueContext) {
			QueueItem item = queueRepository.findRelative(watchId, 1);
			boolean inQueue = queueRepository.containsVideo(watchId);
			// Inside the queue the user's order is followed. Coming from outside the queue (for
			// example from an autoplayed suggestion) the queue is only re-entered with a video that
			// has not been played recently, otherwise finished queues would loop forever.
			if (item != null && item.getVideoUrl() != null
							&& (inQueue
							|| queueRepository.isPlayNext(item.getVideoId())
							|| !prefs.wasRecentlyPlayed(item.getVideoId()))) {
				tabManager.playInWatch(item.getVideoUrl());
				return;
			}
			autoplaySuggestion(watchId, manual);
			return;
		}
		if (playlistContext) {
			tabManager.playlistStep(1, (url, end) -> {
				if (url != null) tabManager.playInWatch(url);
				else if (end) autoplaySuggestion(watchId, manual);
			});
			return;
		}
		autoplaySuggestion(watchId, manual);
	}

	/**
	 * A video in the queue or a playlist that cannot be played (removed, private, blocked) is
	 * skipped instead of stopping playback with an error.
	 *
	 * @return true when the video was skipped
	 */
	public boolean skipUnavailable(@NonNull String unavailableId) {
		boolean queueContext = queueRepository.isEnabled() && queueRepository.hasItems();
		boolean playlistContext = !queueContext && tabManager.watchHasPlaylist();
		if (!queueContext && !playlistContext) return false;
		skipToNext(false);
		removeFromQueue(unavailableId);
		return true;
	}

	/**
	 * Plays the first suggestion of the given video that has not been played recently, the same
	 * way YouTube continues with "Up next" once nothing else is queued.
	 */
	/**
	 * Plays the next suggested video, like YouTube's autoplay. Suggestions come from the watch
	 * page first and from the extractor when the page has none.
	 *
	 * @param manual true when the user pressed next; false when the video ended on its own
	 */
	private void autoplaySuggestion(@Nullable String fromId, boolean manual) {
		String sourceId = fromId != null ? fromId : videoId;
		if (sourceId == null) return;
		if (!manual && prefetchedNextUrl != null && sourceId.equals(prefetchedFor)) {
			// Picked and loaded during the last seconds of the video, so it starts right away.
			String url = prefetchedNextUrl;
			prefetchedNextUrl = null;
			tabManager.playInWatch(url);
			return;
		}
		long token = ++autoplayToken;
		requestSuggestions(sourceId, manual, token);
	}

	public void setFeedClient(@Nullable FeedClient feedClient) {
		this.feedClient = feedClient;
	}

	private void requestSuggestions(@NonNull String sourceId, boolean manual, long token) {
		requestSuggestions(sourceId, manual, token, 0);
	}

	private void requestSuggestions(@NonNull String sourceId, boolean manual, long token, int attempt) {
		gatherSuggestions(sourceId, token, ids -> {
			if (ids.isEmpty() && attempt < SUGGESTION_RETRIES) {
				// Nothing yet (slow network, page still loading): try again shortly instead of giving up.
				handler.postDelayed(() -> {
					if (token == autoplayToken) requestSuggestions(sourceId, manual, token, attempt + 1);
				}, SUGGESTION_RETRY_DELAY_MS);
				return;
			}
			playSuggestion(sourceId, ids, manual);
		});
	}

	/**
	 * Finds videos to continue with, trying in turn: the extractor's suggestions, YouTube's "next"
	 * suggestions, a search for the current video's title, and finally the Home feed. The first
	 * source that has videos wins, so a next video is found whenever YouTube answers at all.
	 */
	private void gatherSuggestions(@NonNull String sourceId, long token, @NonNull Consumer<List<String>> done) {
		extractor.getRelatedVideoIds(sourceId).whenComplete((ids, error) -> handler.post(() -> {
			if (token != autoplayToken) return;
			if (error != null) Log.w(TAG, "suggestions unavailable videoId=" + sourceId, error);
			if (hasOther(ids, sourceId)) {
				done.accept(ids);
				return;
			}
			FeedClient feed = feedClient;
			if (feed == null) {
				done.accept(List.of());
				return;
			}
			fromFeed(feed.related(sourceId), sourceId, token, related -> {
				if (!related.isEmpty()) {
					done.accept(related);
					return;
				}
				String query = currentTitle != null && !currentTitle.isBlank() ? currentTitle : currentAuthor;
				if (query == null || query.isBlank()) {
					fromFeed(feed.browse(FeedClient.Feed.HOME, null), sourceId, token, done);
					return;
				}
				fromFeed(feed.search(query, null), sourceId, token, similar -> {
					if (!similar.isEmpty()) {
						done.accept(similar);
						return;
					}
					fromFeed(feed.browse(FeedClient.Feed.HOME, null), sourceId, token, done);
				});
			});
		}));
	}

	private void fromFeed(@NonNull FeedClient.Call call, @NonNull String sourceId, long token,
	                      @NonNull Consumer<List<String>> done) {
		call.result.whenComplete((page, error) -> handler.post(() -> {
			if (token != autoplayToken) return;
			List<String> ids = new ArrayList<>();
			if (page != null) {
				for (FeedItem item : page.items()) {
					if (item.kind() != FeedItem.Kind.VIDEO || item.live() || item.videoId() == null) continue;
					if (item.videoId().equals(sourceId)) continue;
					if (item.author() != null && contentFilters.isChannelBlocked(item.author(), item.authorUrl())) continue;
					ids.add(item.videoId());
				}
			} else if (error != null) {
				Log.w(TAG, "feed suggestions unavailable videoId=" + sourceId, error);
			}
			done.accept(ids);
		}));
	}

	private static boolean hasOther(@Nullable List<String> ids, @NonNull String sourceId) {
		if (ids == null) return false;
		for (String id : ids) {
			if (id != null && !id.equals(sourceId)) return true;
		}
		return false;
	}

	private void playSuggestion(@NonNull String sourceId, @NonNull List<String> ids, boolean manual) {
		// The user moved on to another video in the meantime.
		if (!Objects.equals(sourceId, watchVideoId())) return;
		String pick = pickSuggestion(sourceId, ids);
		if (pick == null) {
			Log.i(TAG, "no suggestion videoId=" + sourceId);
			if (manual) ToastUtils.show(appContext, R.string.no_suggestions);
			return;
		}
		tabManager.playInWatch(Constant.HOME_URL + "/watch?v=" + pick);
	}

	/**
	 * Picks the next video: at random among the top suggestions, preferring ones not watched in
	 * the last day, falling back to the top suggestions so playback never stops.
	 */
	@Nullable
	private String pickSuggestion(@NonNull String sourceId, @NonNull List<String> ids) {
		// Pick at random among the top suggestions, preferring ones not watched in the last day,
		// so autoplay wanders through similar videos instead of walking down one list.
		List<String> fresh = new ArrayList<>();
		List<String> any = new ArrayList<>();
		for (String id : ids) {
			if (id == null || id.equals(sourceId) || any.contains(id)) continue;
			if (any.size() < SUGGESTION_POOL) any.add(id);
			if (fresh.size() < SUGGESTION_POOL && !prefs.wasRecentlyPlayed(id)) fresh.add(id);
			if (fresh.size() >= SUGGESTION_POOL) break;
		}
		List<String> pool = !fresh.isEmpty() ? fresh : any;
		if (pool.isEmpty()) return null;
		return pool.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(pool.size()));
	}

	/**
	 * Near the end of a video, works out what plays next and extracts it in the background, so
	 * the next video starts without waiting.
	 */
	private void prefetchNext(@NonNull String sourceId) {
		if (!loopMode.skipsToNextOnEnded()) return;
		if (queueRepository.isEnabled() && queueRepository.hasItems()) {
			QueueItem item = queueRepository.findRelative(watchVideoId(), 1);
			if (item != null && item.getVideoUrl() != null) warm(item.getVideoUrl());
			return;
		}
		if (tabManager.watchHasPlaylist()) return;
		gatherSuggestions(sourceId, autoplayToken, ids -> rememberPrefetch(sourceId, pickSuggestion(sourceId, ids)));
	}

	private void rememberPrefetch(@NonNull String sourceId, @Nullable String pick) {
		if (pick == null || !sourceId.equals(prefetchedFor) || !sourceId.equals(videoId)) return;
		prefetchedNextUrl = Constant.HOME_URL + "/watch?v=" + pick;
		warm(prefetchedNextUrl);
	}

	private void warm(@NonNull String url) {
		extractor.getInfo(url, null).whenComplete((details, error) -> {
			// Only fills the cache; the next play picks it up.
		});
	}



	public void skipToPrevious() {
		boolean queueEnabled = queueRepository.isEnabled();
		boolean hasQueueItems = queueRepository.hasItems();
		String watchId = watchVideoId();
		boolean inQueue = queueRepository.containsVideo(watchId);
		boolean hasPlaylist = tabManager.watchHasPlaylist();
		boolean queueContext = queueEnabled && hasQueueItems;
		boolean playlistContext = !queueContext && hasPlaylist;
		if (queueContext && inQueue) {
			QueueItem item = queueRepository.findRelative(watchId, -1);
			if (item != null && item.getVideoUrl() != null) {
				tabManager.playInWatch(item.getVideoUrl());
				return;
			}
		}
		if (playlistContext) {
			tabManager.playlistStep(-1, (url, end) -> {
				if (url != null) tabManager.playInWatch(url);
				else playPreviousFromHistory();
			});
			return;
		}
		playPreviousFromHistory();
	}

	/**
	 * Goes back to the video played before this one. Works the same in the full player, the
	 * mini player and the bottom bar because the player is told directly.
	 */
	private void playPreviousFromHistory() {
		String previous = history.pollLast();
		while (previous != null && previous.equals(videoId)) {
			previous = history.pollLast();
		}
		if (previous != null) {
			returningToId = previous;
			tabManager.playInWatch(Constant.HOME_URL + "/watch?v=" + previous);
			return;
		}
		if (tabManager.canGoBackInWatch()) {
			tabManager.goBackInWatch();
		}
	}


	public void playRandomPlaylistItem() {
		boolean queueEnabled = queueRepository.isEnabled();
		boolean hasQueueItems = queueRepository.hasItems();
		String watchId = watchVideoId();
		boolean hasPlaylist = tabManager.watchHasPlaylist();
		boolean queueContext = queueEnabled && hasQueueItems;
		boolean playlistContext = !queueContext && hasPlaylist;
		if (queueContext) {
			QueueItem item = queueRepository.findRandom(watchId);
			if (item != null && item.getVideoUrl() != null) {
				tabManager.playInWatch(item.getVideoUrl());
			}
			return;
		}
		if (playlistContext) {
			tabManager.playlistStep(0, (url, end) -> {
				if (url != null) tabManager.playInWatch(url);
			});
		}
	}

	@NonNull
	public QueueNav getQueueNavigationAvailability() {
		boolean queueEnabled = queueRepository.isEnabled();
		boolean hasQueueItems = queueRepository.hasItems();
		String watchId = watchVideoId();
		boolean inQueue = queueRepository.containsVideo(watchId);
		boolean hasPlaylist = tabManager.watchHasPlaylist();
		boolean canGoBack = !history.isEmpty() || tabManager.canGoBackInWatch();
		boolean playlistAtHead = UrlUtils.isPlaylistFirstItemUrl(tabManager.getWatchUrl());
		boolean queueContext = queueEnabled && hasQueueItems;
		boolean playlistContext = !queueContext && hasPlaylist;
		boolean queueAtHead = queueContext && queueRepository.findRelative(watchId, -1) == null;
		if (queueContext) {
			boolean queuePrevEnabled = inQueue && !queueAtHead;
			boolean queueBackEnabled = canGoBack && (!inQueue || queueAtHead);
			return new QueueNav(true, true, true, queuePrevEnabled, queueBackEnabled);
		}
		if (playlistContext) {
			boolean playlistPrevEnabled = !playlistAtHead || canGoBack;
			return new QueueNav(false, true, true, false, playlistPrevEnabled);
		}
		return new QueueNav(false, true, false, false, canGoBack);
	}

	@Nullable
	private String watchVideoId() {
		String watchUrl = tabManager.getWatchUrl();
		if (watchUrl == null || watchUrl.isEmpty()) {
			return videoId;
		}
		try {
			String query = URI.create(watchUrl).getRawQuery();
			if (query != null && !query.isBlank()) {
				for (String pair : query.split("&")) {
					int separator = pair.indexOf('=');
					String name = separator >= 0 ? pair.substring(0, separator) : pair;
					if (!"v".equals(name)) continue;
					return separator >= 0 ? pair.substring(separator + 1) : "";
				}
			}
		} catch (IllegalArgumentException ignored) {
			// Fall back to the cached engine id.
		}
		return videoId;
	}

	@Nullable
	public Format getVideoFormat() {
		for (final Tracks.Group group : this.player.getCurrentTracks().getGroups()) {
			if (group.getType() == C.TRACK_TYPE_VIDEO && group.isSelected()) {
				for (int i = 0; i < group.length; i++)
					if (group.isTrackSelected(i)) return group.getTrackFormat(i);
			}
		}
		return null;
	}

	@Nullable
	public Format getAudioFormat() {
		for (final Tracks.Group group : this.player.getCurrentTracks().getGroups()) {
			if (group.getType() == C.TRACK_TYPE_AUDIO && group.isSelected()) {
				for (int i = 0; i < group.length; i++)
					if (group.isTrackSelected(i)) return group.getTrackFormat(i);
			}
		}
		return null;
	}

	public List<String> getAvailableResolutions() {
		List<String> resolutions = new ArrayList<>();
		if (streamCatalog != null) {
			for (VideoStream stream : PlayerUtils.filterBestStreams(streamCatalog.getVideoStreams())) {
				String res = stream.getResolution();
				if (!resolutions.contains(res)) resolutions.add(res);
			}
		}
		// If empty, fall back to the active tracks, such as DASH or HLS.
		if (resolutions.isEmpty()) {
			for (final Tracks.Group group : this.player.getCurrentTracks().getGroups()) {
				if (group.getType() == C.TRACK_TYPE_VIDEO) {
					for (int i = 0; i < group.length; i++) {
						Format format = group.getTrackFormat(i);
						if (format.height != Format.NO_VALUE) {
							String res = format.height + "p";
							if (!resolutions.contains(res)) resolutions.add(res);
						}
					}
				}
			}
		}
		return PlayerUtils.sortResolutionLabels(resolutions);
	}

	public void onQualitySelected(@Nullable String res) {
		if (res == null) return;
		State state = state();
		if (state == null) return;
		prefs.setPreferredQuality(res);
		PlaybackPlan plan = PlaybackPlanner.plan(state.deliveries(), res, null);
		this.playbackPlan = plan;
		Delivery delivery = plan.getDelivery();
		if (isLiveMode(plan) && delivery != null && !delivery.isTrackLock()) {
			applyPlaybackTrackMode();
			return;
		}
		if (delivery != null && delivery.isTrackLock()) {
			int actualHeight = StringUtils.parseHeight(res);
			VideoStream match = selectedVideo(plan);
			if (match != null) {
				actualHeight = match.getHeight();
			}
			setVideoQuality(actualHeight);
			return;
		}
		long pos = this.player.getCurrentPosition();
		float speed = this.player.getPlaybackParameters().speed;
		play(new PlaybackDetails(state.video(), state.catalog(), state.deliveries(), plan, segments, subtitles));
		if (plan.getMode() != PlaybackMode.LIVE_DASH
						&& plan.getMode() != PlaybackMode.LIVE_HLS) {
			this.player.seekTo(pos);
		}
		this.player.setPlaybackParameters(new PlaybackParameters(speed));
	}

	public void setVideoQuality(int height) {
		DefaultTrackSelector trackSelector = trackSelector();
		final DefaultTrackSelector.Parameters.Builder builder = params(trackSelector)
						.clearOverridesOfType(C.TRACK_TYPE_VIDEO)
						.setForceHighestSupportedBitrate(false)
						.setMaxVideoSize(Integer.MAX_VALUE, height)
						.setMinVideoSize(0, height);
		TrackOverride override = findVideoOverride(height);
		if (override != null) {
			builder.setOverrideForType(new TrackSelectionOverride(override.group(), override.track()));
		}
		trackSelector.setParameters(builder.build());
	}

	private void applyPreferredVideoTrack() {
		PlaybackPlan plan = playbackPlan;
		if (plan == null || plan.getDelivery() == null || !plan.getDelivery().isTrackLock()) {
			return;
		}
		String quality = prefs.getPreferredQuality();
		if (quality == null || quality.isEmpty()) {
			return;
		}
		int height = StringUtils.parseHeight(quality);
		if (height > 0) {
			setVideoQuality(height);
		}
	}

	private void applyPlaybackTrackMode() {
		DefaultTrackSelector trackSelector = trackSelector();
		final DefaultTrackSelector.Parameters.Builder builder = params(trackSelector)
						.clearOverridesOfType(C.TRACK_TYPE_VIDEO)
						.setForceHighestSupportedBitrate(false);
		PlaybackPlan plan = playbackPlan;
		if (plan == null || plan.getDelivery() == null) {
			builder.clearVideoSizeConstraints();
		} else {
			int height = StringUtils.parseHeight(prefs.getPreferredQuality());
			if (height > 0) {
				builder.setMaxVideoSize(Integer.MAX_VALUE, height);
				if (plan.getDelivery().isTrackLock()) {
					builder.setMinVideoSize(0, height);
				} else {
					builder.clearVideoSizeConstraints();
					builder.setMaxVideoSize(Integer.MAX_VALUE, height);
				}
			} else {
				builder.clearVideoSizeConstraints();
			}
		}
		trackSelector.setParameters(builder.build());
	}

	@Nullable
	private TrackOverride findVideoOverride(int preferredHeight) {
		TrackOverride best = null;
		int bestDelta = Integer.MAX_VALUE;
		for (final Tracks.Group group : this.player.getCurrentTracks().getGroups()) {
			if (group.getType() != C.TRACK_TYPE_VIDEO) {
				continue;
			}
			for (int i = 0; i < group.length; i++) {
				Format format = group.getTrackFormat(i);
				if (format.height == Format.NO_VALUE || !group.isTrackSupported(i)) {
					continue;
				}
				int delta = Math.abs(format.height - preferredHeight);
				if (best == null || delta < bestDelta) {
					best = new TrackOverride(group.getMediaTrackGroup(), i);
					bestDelta = delta;
				}
			}
		}
		return best;
	}

	@Nullable
	public String getQuality() {
		VideoStream videoStream = this.videoStream;
		if (videoStream != null) return videoStream.getResolution();
		Format format = getVideoFormat();
		if (format != null && format.height > 0) {
			int fps = Math.round(format.frameRate);
			return fps > 30 ? format.height + "p" + fps : format.height + "p";
		}
		return prefs.getPreferredQuality();
	}

	public String getQualityLabel() {
		String quality = getQuality();
		if (quality != null && !quality.isEmpty()) {
			return quality;
		}
		String preferredQuality = prefs.getPreferredQuality();
		return preferredQuality == null ? "" : preferredQuality;
	}

	public void setRepeatMode(int mode) {
		this.player.setRepeatMode(mode);
	}

	public void setLoopMode(@NonNull PlayerLoopMode mode) {
		this.loopMode = mode;
		setRepeatMode(mode.repeatMode());
	}

	public int getPlaybackState() {
		return this.player.getPlaybackState();
	}

	public boolean areSubtitlesEnabled() {
		return !this.player.getTrackSelectionParameters().disabledTrackTypes.contains(C.TRACK_TYPE_TEXT);
	}

	@Nullable
	public String getSelectedSubtitle() {
		for (final Tracks.Group group : this.player.getCurrentTracks().getGroups()) {
			if (group.getType() == C.TRACK_TYPE_TEXT && group.isSelected()) {
				for (int i = 0; i < group.length; i++) {
					if (group.isTrackSelected(i)) {
						Format format = group.getTrackFormat(i);
						return format.label != null ? format.label : format.language;
					}
				}
			}
		}
		return null;
	}

	public List<String> getSubtitles() {
		List<String> subtitles = new ArrayList<>();
		for (final Tracks.Group group : this.player.getCurrentTracks().getGroups()) {
			if (group.getType() == C.TRACK_TYPE_TEXT) {
				for (int i = 0; i < group.length; i++) {
					Format format = group.getTrackFormat(i);
					if (format.label != null) subtitles.add(format.label);
					else if (format.language != null) subtitles.add(format.language);
				}
			}
		}
		return subtitles;
	}

	public List<StreamSegment> getSegments() {
		if (!segments.isEmpty()) return segments;

		// Create default segment with video title at 0 seconds
		List<StreamSegment> segments = new ArrayList<>();
		VideoDetails video = videoDetails;
		if (video != null) segments.add(new StreamSegment(video.getTitle() != null ? video.getTitle() : "", 0));
		return segments;
	}

	@Nullable
	public String getThumbnailUrl() {
		return videoDetails != null ? videoDetails.getThumbnailUrl() : null;
	}

	@Nullable
	public StreamCatalog getStreamCatalog() {
		return streamCatalog;
	}

	@Nullable
	public DecoderCounters getVideoDecoderCounters() {
		return player.getVideoDecoderCounters();
	}

	@NonNull
	public List<AudioStream> getAvailableAudioTracks() {
		return streamCatalog != null ? streamCatalog.getAudioStreams() : Collections.emptyList();
	}

	@Nullable
	public AudioStream getAudioTrack() {
		if (playbackPlan == null) return null;
		return selectedAudio(playbackPlan);
	}

	public void setAudioTrack(@NonNull AudioStream stream) {
		State state = state();
		if (state == null) return;
		PlaybackPlan plan = state.plan();
		AudioStream audio = selectedAudio(plan);
		String content = stream.getContent();
		if (audio != null && content.equals(audio.getContent())) return;
		long pos = player.getCurrentPosition();
		boolean playWhenReady = player.getPlayWhenReady();
		plan.setAudioCandidate(findAudioCandidate(state.catalog(), stream));
		player.setMediaSource(PlaybackSourceFactory.create(sources,
						new PlaybackDetails(state.video(), state.catalog(), state.deliveries(), plan, segments, subtitles),
						plan));
		player.seekTo(pos);
		player.setPlayWhenReady(playWhenReady);
		player.prepare();
	}

	public int getSelectedAudioTrackIndex() {
		AudioStream selected = getAudioTrack();
		if (selected == null || streamCatalog == null) return -1;
		String content = selected.getContent();
		for (int i = 0; i < streamCatalog.getAudioStreams().size(); i++) {
			if (content.equals(streamCatalog.getAudioStreams().get(i).getContent()))
				return i;
		}
		return -1;
	}

	@Nullable
	private State state() {
		VideoDetails video = videoDetails;
		StreamCatalog catalog = streamCatalog;
		DeliveryCatalog deliveries = this.deliveries;
		PlaybackPlan plan = playbackPlan;
		if (video == null || catalog == null || deliveries == null || plan == null) {
			return null;
		}
		return new State(video, catalog, deliveries, plan);
	}

	private boolean isLiveMode(@Nullable PlaybackPlan plan) {
		return plan != null && (plan.getMode() == PlaybackMode.LIVE_DASH
						|| plan.getMode() == PlaybackMode.LIVE_HLS);
	}

	@Nullable
	public static PlaybackRecoveryReason playbackRecoveryReason(@NonNull Throwable throwable) {
		List<Throwable> pending = new ArrayList<>();
		List<Throwable> visited = new ArrayList<>();
		pending.add(throwable);
		for (int i = 0; i < pending.size(); i++) {
			Throwable current = pending.get(i);
			if (current == null || visited.contains(current)) {
				continue;
			}
			visited.add(current);
			if (current instanceof HttpDataSource.InvalidResponseCodeException http
							&& http.responseCode == 403) {
				return PlaybackRecoveryReason.HTTP_403;
			}
			if (current instanceof HttpDataSource.HttpDataSourceException http
							&& http.type == HttpDataSource.HttpDataSourceException.TYPE_OPEN
							&& hasCause(http, SocketTimeoutException.class, ConnectException.class, NoRouteToHostException.class)) {
				return PlaybackRecoveryReason.CONNECTION_OPEN_FAILED;
			}
			if (current.getCause() != null) {
				pending.add(current.getCause());
			}
			Collections.addAll(pending, current.getSuppressed());
		}
		return null;
	}

	@SafeVarargs
	private static boolean hasCause(@NonNull Throwable throwable,
	                                @NonNull Class<? extends Throwable>... causeTypes) {
		Throwable current = throwable;
		while (current != null) {
			for (Class<? extends Throwable> causeType : causeTypes) {
				if (causeType.isInstance(current)) {
					return true;
				}
			}
			current = current.getCause();
		}
		return false;
	}

	public void clear() {
		handler.removeCallbacks(onTimeUpdate);
		saveCurrentProgress();
		this.player.stop();
		this.player.clearMediaItems();
	}

	public void release() {
		handler.removeCallbacks(onTimeUpdate);
		saveCurrentProgress();
		this.player.release();
	}

/**
 * Value object for app logic.
 */
	private record TrackOverride(@NonNull TrackGroup group, int track) {
	}

/**
 * Snapshot of the active playback state.
 */
	private record State(@NonNull VideoDetails video,
	                     @NonNull StreamCatalog catalog,
	                     @NonNull DeliveryCatalog deliveries,
	                     @NonNull PlaybackPlan plan) {
	}

	public enum PlaybackRecoveryReason {
		HTTP_403("HTTP 403"),
		CONNECTION_OPEN_FAILED("connection open failure");

		@NonNull
		private final String logLabel;

		PlaybackRecoveryReason(@NonNull String logLabel) {
			this.logLabel = logLabel;
		}
	}
}
