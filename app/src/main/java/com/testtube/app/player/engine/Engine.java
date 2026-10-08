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
import com.testtube.app.extractor.Delivery;
import com.testtube.app.extractor.DeliveryCatalog;
import com.testtube.app.extractor.FeedClient;
import com.testtube.app.extractor.FeedItem;
import com.testtube.app.extractor.PlaybackDetails;
import com.testtube.app.extractor.PlaybackMode;
import com.testtube.app.extractor.PlaybackPlan;
import com.testtube.app.extractor.PlaybackPlanner;
import com.testtube.app.extractor.RelatedVideo;
import com.testtube.app.extractor.StreamCandidate;
import com.testtube.app.extractor.StreamCatalog;
import com.testtube.app.extractor.VideoDetails;
import com.testtube.app.extractor.YoutubeExtractor;
import com.testtube.app.filter.ContentFilters;
import com.testtube.app.history.WatchHistory;
import com.testtube.app.nav.TabManager;
import com.testtube.app.player.TestTubePlayerView;
import com.testtube.app.player.common.PlayerLoopMode;
import com.testtube.app.player.common.PlayerPreferences;
import com.testtube.app.player.common.PlayerUtils;
import com.testtube.app.player.queue.QueueItem;
import com.testtube.app.player.queue.QueueNav;
import com.testtube.app.player.queue.QueueRepository;
import com.testtube.app.player.sponsor.SponsorBlockManager;
import com.testtube.app.util.StringUtils;
import com.testtube.app.util.ToastUtils;
import com.testtube.app.util.UrlUtils;

import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamSegment;
import org.schabi.newpipe.extractor.stream.SubtitlesStream;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Owns the ExoPlayer instance: loads videos, follows the queue and handles autoplay. */
@UnstableApi
public class Engine {
	private static final String TAG = "TestTubePlayback";
	static final String NO_PLAYABLE_SOURCE_MESSAGE = "No supported playable stream URL in StreamCatalog";
	private static final int SAFE_ZONE_MS = 5000;
	private static final long TICK_MS = 1000L;
	private static final long MIN_TICK_MS = 50L;
	private static final long PERSIST_INTERVAL_MS = 5000L;
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
				long remaining = duration - pos;
				if (remaining <= PREFETCH_BEFORE_END_MS && !videoId.equals(prefetchedFor)) {
					prefetchedFor = videoId;
					prefetchedPick = null;
					prefetchNext(videoId);
				}
				if (remaining <= UP_NEXT_LEAD_MS && !videoId.equals(upNextShownFor)) showUpNext(videoId, remaining);
			}
			long now = SystemClock.elapsedRealtime();
			if (now - lastPersistAt >= PERSIST_INTERVAL_MS) {
				lastPersistAt = now;
				saveProgress(pos, duration);
			}
			// skip sponsor segments, wake up right when the next one starts
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
	 * Saves progress. At the end the saved position is dropped so the video starts over next time.
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
	// single thread for history writes: keeps them in order and out of the extraction pool
	private static final ExecutorService HISTORY_WRITER =
					Executors.newSingleThreadExecutor(runnable -> {
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
	private static final int RECENT_AUTHORS = 3;
	private static final long UP_NEXT_LEAD_MS = 15_000L;
	private static final long SUGGESTION_RETRY_DELAY_MS = 1500L;
	private static final long PREFETCH_BEFORE_END_MS = 45_000L;
	@Nullable
	private String prefetchedFor;
	// video the "Up next" notice was shown for, and the one it was cancelled for
	@Nullable
	private String upNextShownFor;
	@Nullable
	private String upNextCancelledFor;
	// for the previous button, newest last
	@NonNull
	private final ArrayDeque<String> history = new ArrayDeque<>();
	@Nullable
	private String returningToId;
	private boolean watchedMarked;
	private long autoplayToken;
	@Nullable
	private FeedClient feedClient;
	@NonNull
	private final ArrayDeque<String> recentAuthors = new ArrayDeque<>();
	@Nullable
	private UpNextListener upNextListener;
	@Nullable
	private Candidate prefetchedPick;
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
					if (loopMode.skipsToNextOnEnded()) {
						// "Up next" was cancelled, just let the video end
						if (videoId != null && videoId.equals(upNextCancelledFor)) return;
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

	private void removeFromQueue(@Nullable String id) {
		if (id != null && queueRepository.containsVideo(id)) {
			queueRepository.remove(id);
		}
	}

	/** How much of the end can be left unwatched and still count as watched. */
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
			// leaving a queued video (finished or skipped) removes it from the queue
			removeFromQueue(this.videoId);
			failedAdaptiveCandidates.clear();
			failedClients.clear();
			recoveries = 0;
			autoplayToken++;
			upNextShownFor = null;
			upNextCancelledFor = null;
			// history writes serialize the whole list, keep that off the main thread
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
	 * Tries other streams of the current extraction after one got rejected. A 403 blocks the whole
	 * InnerTube client that produced the stream, since YouTube enforces restrictions per client and
	 * other formats from it would fail too. Returns false when nothing usable is left (caller should
	 * re-extract).
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
	 * Counts a re-extraction against the per-video retry budget.
	 *
	 * @return false once the budget is used up
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

	/** Video track off/on, audio keeps playing. */
	public void setVideoEnabled(boolean enabled) {
		this.player.setTrackSelectionParameters(this.player.getTrackSelectionParameters().buildUpon()
						.setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !enabled)
						.build());
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
	 * @param manual true if the user asked for next (button, notification, bar), false if the
	 *               video just ended
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
			// Inside the queue we follow the user's order. Coming from outside (e.g. an autoplayed
			// suggestion) we only re-enter the queue with something not played recently, otherwise
			// finished queues would loop forever.
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
	 * Skips unplayable queue/playlist videos (removed, private, blocked) instead of stopping with
	 * an error.
	 *
	 * @return true if the video was skipped
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
	 * Autoplay. Tries the extractor's suggestions, then YouTube's "next" list, a search and Home.
	 *
	 * @param manual true if the user pressed next, false if the video ended
	 */
	private void autoplaySuggestion(@Nullable String fromId, boolean manual) {
		String sourceId = fromId != null ? fromId : videoId;
		if (sourceId == null) return;
		if (!manual && prefetchedPick != null && sourceId.equals(prefetchedFor)) {
			// already picked and preloaded, starts right away
			Candidate pick = prefetchedPick;
			prefetchedPick = null;
			startNext(pick);
			return;
		}
		long token = ++autoplayToken;
		requestSuggestions(sourceId, manual, token);
	}

	private record Candidate(@NonNull String id, @Nullable String title, @Nullable String author) {
	}

	public interface UpNextListener {
		/**
		 * @param delayMs  how long the notice stays up (until the video ends)
		 * @param onCancel cancels the switch, the next video won't start
		 */
		void show(@NonNull String title, long delayMs, @NonNull Runnable onCancel);
	}

	public void setUpNextListener(@Nullable UpNextListener listener) {
		this.upNextListener = listener;
	}

	public void setFeedClient(@Nullable FeedClient feedClient) {
		this.feedClient = feedClient;
	}

	private void requestSuggestions(@NonNull String sourceId, boolean manual, long token) {
		requestSuggestions(sourceId, manual, token, 0);
	}

	private void requestSuggestions(@NonNull String sourceId, boolean manual, long token, int attempt) {
		gatherSuggestions(sourceId, token, found -> {
			if (found.isEmpty() && attempt < SUGGESTION_RETRIES) {
				// nothing yet (slow network etc.), retry shortly instead of giving up
				handler.postDelayed(() -> {
					if (token == autoplayToken) requestSuggestions(sourceId, manual, token, attempt + 1);
				}, SUGGESTION_RETRY_DELAY_MS);
				return;
			}
			playSuggestion(sourceId, found, manual);
		});
	}

	/**
	 * Candidates for what plays next: extractor suggestions, YouTube's "next" list, a title search,
	 * then Home. The first source with results wins.
	 */
	private void gatherSuggestions(@NonNull String sourceId, long token, @NonNull Consumer<List<Candidate>> done) {
		extractor.getRelatedVideos(sourceId).whenComplete((related, error) -> handler.post(() -> {
			if (token != autoplayToken) return;
			if (error != null) Log.w(TAG, "suggestions unavailable videoId=" + sourceId, error);
			List<Candidate> found = new ArrayList<>();
			if (related != null) {
				for (RelatedVideo video : related) {
					found.add(new Candidate(video.id(), video.title(), video.uploaderName()));
				}
			}
			if (hasOther(found, sourceId)) {
				done.accept(found);
				return;
			}
			FeedClient feed = feedClient;
			if (feed == null) {
				done.accept(List.of());
				return;
			}
			fromFeed(feed.related(sourceId), sourceId, token, related2 -> {
				if (!related2.isEmpty()) {
					done.accept(related2);
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
	                      @NonNull Consumer<List<Candidate>> done) {
		call.result.whenComplete((page, error) -> handler.post(() -> {
			if (token != autoplayToken) return;
			List<Candidate> found = new ArrayList<>();
			if (page != null) {
				for (FeedItem item : page.items()) {
					if (item.kind() != FeedItem.Kind.VIDEO || item.live() || item.videoId() == null) continue;
					if (item.videoId().equals(sourceId)) continue;
					if (item.author() != null && contentFilters.isChannelBlocked(item.author(), item.authorUrl())) continue;
					found.add(new Candidate(item.videoId(), item.title(), item.author()));
				}
			} else if (error != null) {
				Log.w(TAG, "feed suggestions unavailable videoId=" + sourceId, error);
			}
			done.accept(found);
		}));
	}

	private static boolean hasOther(@NonNull List<Candidate> found, @NonNull String sourceId) {
		for (Candidate candidate : found) {
			if (!candidate.id().equals(sourceId)) return true;
		}
		return false;
	}

	private void playSuggestion(@NonNull String sourceId, @NonNull List<Candidate> found, boolean manual) {
		// user already moved on to another video
		if (!Objects.equals(sourceId, watchVideoId())) return;
		Candidate pick = pickSuggestion(sourceId, found);
		if (pick == null) {
			Log.i(TAG, "no suggestion videoId=" + sourceId);
			if (manual) ToastUtils.show(appContext, R.string.no_suggestions);
			return;
		}
		startNext(pick);
	}

	/** Switches right away; the "Up next" notice already gave a chance to cancel. */
	private void startNext(@NonNull Candidate pick) {
		recordAuthor(pick.author());
		tabManager.playInWatch(Constant.HOME_URL + "/watch?v=" + pick.id());
	}

	/**
	 * Shows "Up next" near the end once the next video is known, otherwise retried on the next tick.
	 */
	private void showUpNext(@NonNull String currentId, long remainingMs) {
		UpNextListener listener = upNextListener;
		if (listener == null || !loopMode.skipsToNextOnEnded()) return;
		String title = nextTitle(currentId);
		if (title == null) return;
		upNextShownFor = currentId;
		listener.show(title, remainingMs, () -> upNextCancelledFor = currentId);
	}

	/** Title of what plays next, or null if not known (yet) or it comes from a playlist. */
	@Nullable
	private String nextTitle(@NonNull String currentId) {
		if (queueRepository.isEnabled() && queueRepository.hasItems()) {
			QueueItem item = queueRepository.findRelative(currentId, 1);
			return item != null ? item.getTitle() : null;
		}
		if (tabManager.watchHasPlaylist() || !currentId.equals(prefetchedFor) || prefetchedPick == null) return null;
		String title = prefetchedPick.title();
		return title != null && !title.isBlank() ? title : prefetchedPick.id();
	}

	private void recordAuthor(@Nullable String author) {
		if (author == null || author.isBlank()) return;
		recentAuthors.remove(author);
		recentAuthors.addFirst(author);
		while (recentAuthors.size() > RECENT_AUTHORS) recentAuthors.removeLast();
	}

	/**
	 * Random pick among the top suggestions. Prefers videos not played or watched recently and
	 * avoids the channel that just played. Falls back to any suggestion so playback never stops.
	 */
	@Nullable
	private Candidate pickSuggestion(@NonNull String sourceId, @NonNull List<Candidate> found) {
		List<Candidate> pool = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		for (Candidate candidate : found) {
			if (candidate.id().equals(sourceId) || !seen.add(candidate.id())) continue;
			pool.add(candidate);
			if (pool.size() >= SUGGESTION_POOL * 3) break;
		}
		if (pool.isEmpty()) return null;
		List<Candidate> fresh = new ArrayList<>();
		List<Candidate> unwatched = new ArrayList<>();
		List<Candidate> notRecent = new ArrayList<>();
		for (Candidate candidate : pool) {
			if (prefs.wasRecentlyPlayed(candidate.id())) continue;
			notRecent.add(candidate);
			if (contentFilters.isWatched(candidate.id())) continue;
			unwatched.add(candidate);
			String author = candidate.author();
			boolean sameChannel = author != null && (recentAuthors.contains(author) || author.equals(currentAuthor));
			if (!sameChannel) fresh.add(candidate);
		}
		List<Candidate> chosen = !fresh.isEmpty() ? fresh
						: !unwatched.isEmpty() ? unwatched
						: !notRecent.isEmpty() ? notRecent : pool;
		List<Candidate> top = chosen.subList(0, Math.min(chosen.size(), SUGGESTION_POOL));
		return top.get(ThreadLocalRandom.current().nextInt(top.size()));
	}

	private void prefetchNext(@NonNull String sourceId) {
		if (!loopMode.skipsToNextOnEnded()) return;
		if (queueRepository.isEnabled() && queueRepository.hasItems()) {
			QueueItem item = queueRepository.findRelative(watchVideoId(), 1);
			if (item != null && item.getVideoUrl() != null) warm(item.getVideoUrl());
			return;
		}
		if (tabManager.watchHasPlaylist()) return;
		gatherSuggestions(sourceId, autoplayToken, found -> rememberPrefetch(sourceId, pickSuggestion(sourceId, found)));
	}

	private void rememberPrefetch(@NonNull String sourceId, @Nullable Candidate pick) {
		if (pick == null || !sourceId.equals(prefetchedFor) || !sourceId.equals(videoId)) return;
		prefetchedPick = pick;
		warm(Constant.HOME_URL + "/watch?v=" + pick.id());
	}

	private void warm(@NonNull String url) {
		// only warms the cache, the next play picks it up
		extractor.getInfo(url, null);
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
			// fall back to the cached engine id
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
		// if empty, fall back to the active tracks (DASH/HLS)
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

		// No chapters: one segment named after the video
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

	private record TrackOverride(@NonNull TrackGroup group, int track) {
	}

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
