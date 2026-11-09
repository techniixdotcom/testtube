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
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.common.text.CueGroup;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.exoplayer.DecoderCounters;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;

import com.testtube.app.AppSettings;
import com.testtube.app.Constant;
import com.testtube.app.R;
import com.testtube.app.external.ExternalIds;
import com.testtube.app.external.ExternalVideo;
import com.testtube.app.extractor.DeliveryCatalog;
import com.testtube.app.extractor.ExtractionSession;
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
import com.testtube.app.util.Timing;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

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
				if (remaining <= PREFETCH_BEFORE_END_MS && !videoId.equals(prefetchedFor)
								&& canPreload(pos, duration, remaining)) {
					prefetchedFor = videoId;
					prefetchedPick = null;
					prefetchNext(videoId);
				}
			}
			long now = SystemClock.elapsedRealtime();
			if (now - lastPersistAt >= PERSIST_INTERVAL_MS) {
				lastPersistAt = now;
				saveProgress(pos, duration);
			}
			// skip sponsor segments and wake exactly when the next one starts
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

	/** Saves the position. Dropped at the end so a finished video starts over next time. */
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
				prefs.persistProgress(videoId, pos);
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
	/** One thread for history writes, keeps them ordered and out of the extraction pool. */
	private static final ExecutorService HISTORY_WRITER =
					Executors.newSingleThreadExecutor(runnable -> {
						Thread thread = new Thread(runnable, "testtube-history");
						thread.setDaemon(true);
						return thread;
					});
	@NonNull
	private final Context appContext;
	/** "%1$s (auto-generated)" in the app's language, for subtitle labels */
	@NonNull
	private final String autoSubtitleFormat;
	private int recoveries;
	private static final int MAX_HISTORY = 50;
	private static final int SUGGESTION_POOL = 5;
	private static final int SUGGESTION_RETRIES = 3;
	private static final int RECENT_AUTHORS = 3;
	private static final long SUGGESTION_RETRY_DELAY_MS = 1500L;
	/** When the next video gets picked, extracted and its start cached. */
	private static final long PREFETCH_BEFORE_END_MS = 120_000L;
	/** Buffer the current video needs before preload may steal bandwidth. */
	private static final long PRELOAD_MIN_BUFFER_MS = 20_000L;
	/** From here preload runs even on a slow connection. */
	private static final long PRELOAD_ANYWAY_MS = 45_000L;
	@Nullable
	private String prefetchedFor;
	/** When the loading video was handed to the player, for the debug timing log. */
	private long firstFrameStarted = Timing.OFF;
	@NonNull
	private final StreamPreloader preloader;
	@Nullable
	private volatile String preloadTarget;
	@Nullable
	private volatile UnaryOperator<PlaybackDetails> planSelector;
	/** Videos played before this one, newest last, for previous. */
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
	private Candidate prefetchedPick;
	@Nullable
	private String currentTitle;
	@Nullable
	private String currentAuthor;
	/**
	 * Links of other sites' recent videos (TestTube+) by id; Back replays them from the link since
	 * their ids aren't YouTube's.
	 */
	@NonNull
	private final Map<String, String> externalUrls = new LinkedHashMap<>(16, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
			return size() > MAX_HISTORY;
		}
	};

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
		this.autoSubtitleFormat = context.getString(R.string.subtitle_auto_generated);
		this.prefs = prefs;
		this.tabManager = tabManager;
		this.sponsor = sponsor;
		this.queueRepository = queueRepository;
		this.sources = new PlayerDataSource(context, simpleCache);
		this.preloader = new StreamPreloader(sources.preloadSource());
		DefaultTrackSelector trackSelector = new DefaultTrackSelector(context, new AdaptiveTrackSelection.Factory());
		// no tunneling: on Samsung (Exynos) and some Snapdragon firmware tunneled video skips the GPU's
		// colour handling and skin turns blue. Costs a bit of battery, worth it.
		trackSelector.setParameters(params(trackSelector).setTunnelingEnabled(false).build());
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
					markFinished();
					if (loopMode.skipsToNextOnEnded()) {
						playNextAfterFinished();
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
			public void onRenderedFirstFrame() {
				if (firstFrameStarted == Timing.OFF) return;
				Timing.log("first frame", videoId, firstFrameStarted);
				firstFrameStarted = Timing.OFF;
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

	private void markFinished() {
		if (videoId == null) return;
		prefs.clearProgress(videoId);
		contentFilters.recordProgress(videoId, 1L, 1L);
	}

	private void playNextAfterFinished() {
		String endedId = videoId;
		skipToNext(false);
		removeFromQueue(endedId);
	}

	private void removeFromQueue(@Nullable String id) {
		if (id != null && queueRepository.containsVideo(id)) {
			queueRepository.remove(id);
		}
	}

	/** Tail length after which a video counts as watched. */
	private static long watchedTailMs(long durationMs) {
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

	public boolean isPlaying() {
		return this.player.isPlaying();
	}

	/** Playing or about to: buffering, or between two queue videos. False only when paused. */
	public boolean wantsToPlay() {
		return player.getPlayWhenReady();
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
			// preload done or not needed, don't download the start twice
			preloadTarget = null;
			preloader.cancel();
			saveCurrentProgress();
			if (Objects.equals(returningToId, video.getId())) {
				returningToId = null;
			} else if (this.videoId != null && !this.videoId.equals(history.peekLast())) {
				history.addLast(this.videoId);
				while (history.size() > MAX_HISTORY) history.removeFirst();
			}
			// played or skipped, drop it from the queue
			removeFromQueue(this.videoId);
			failedAdaptiveCandidates.clear();
			failedClients.clear();
			recoveries = 0;
			autoplayToken++;
			// history writes serialize whole lists, keep them off the main thread
			String id = video.getId();
			String title = video.getTitle();
			String author = video.getAuthor();
			currentTitle = title;
			currentAuthor = author;
			String thumbnail = video.getThumbnailUrl();
			HISTORY_WRITER.execute(() -> {
				watchHistory.record(id, title, author, thumbnail);
				prefs.recordPlayed(id);
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
		firstFrameStarted = Timing.start();
		this.player.setMediaSource(PlaybackSourceFactory.create(sources, autoSubtitleFormat, details, plan));
		this.player.setPlaybackParameters(new PlaybackParameters(1.0f));

		// resume position
		long resumePos = prefs.getResumePosition(videoId);
		if (resumePos > SAFE_ZONE_MS && resumePos < duration - watchedTailMs(duration)) {
			this.player.seekTo(resumePos);
		}

		this.player.prepare();
		this.player.setPlayWhenReady(true);
	}

	/**
	 * Plays a Facebook or SoundCloud item (TestTube+) in the same player with queue, autoplay, Back,
	 * resume position and notification working like YouTube. Not added to History, which opens
	 * videos on YouTube.
	 */
	public void playExternal(@NonNull ExternalVideo video) {
		String id = video.id();
		if (!Objects.equals(this.videoId, id)) {
			preloadTarget = null;
			preloader.cancel();
			saveCurrentProgress();
			if (Objects.equals(returningToId, id)) {
				returningToId = null;
			} else if (this.videoId != null && !this.videoId.equals(history.peekLast())) {
				history.addLast(this.videoId);
				while (history.size() > MAX_HISTORY) history.removeFirst();
			}
			removeFromQueue(this.videoId);
			failedAdaptiveCandidates.clear();
			failedClients.clear();
			recoveries = 0;
			autoplayToken++;
			currentTitle = video.title();
			currentAuthor = video.author();
			HISTORY_WRITER.execute(() -> prefs.recordPlayed(id));
		}
		externalUrls.put(id, video.pageUrl());
		watchedMarked = false;
		this.videoId = id;
		this.videoDetails = video.toDetails();
		this.streamCatalog = null;
		this.deliveries = null;
		this.playbackPlan = null;
		this.segments = List.of();
		this.subtitles = List.of();
		this.videoStream = null;
		applyPlaybackTrackMode();

		firstFrameStarted = Timing.start();
		this.player.setMediaSource(externalSource(video));
		this.player.setPlaybackParameters(new PlaybackParameters(1.0f));
		long duration = video.durationMs();
		long resumePos = prefs.getResumePosition(id);
		if (resumePos > SAFE_ZONE_MS && (duration <= 0L || resumePos < duration - watchedTailMs(duration))) {
			this.player.seekTo(resumePos);
		}
		this.player.prepare();
		this.player.setPlayWhenReady(true);
	}

	/** Reloads an external video with a fresh link at the position (old one expired). */
	public void replaceExternal(@NonNull ExternalVideo video, long positionMs) {
		if (!Objects.equals(this.videoId, video.id())) return;
		externalUrls.put(video.id(), video.pageUrl());
		this.videoDetails = video.toDetails();
		boolean playWhenReady = player.getPlayWhenReady();
		player.setMediaSource(externalSource(video));
		player.seekTo(Math.max(0L, positionMs));
		player.prepare();
		player.setPlayWhenReady(playWhenReady);
	}

	/** An external video is playing (TestTube+). */
	public boolean isExternal() {
		return ExternalIds.isExternal(videoId);
	}

	/**
	 * External file or HLS straight over HTTP, not YouTube's data source (which adds YouTube params
	 * and headers). A song's cover shows over the sound.
	 */
	@NonNull
	private static MediaSource externalSource(@NonNull ExternalVideo video) {
		DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
						.setUserAgent(video.userAgent())
						.setConnectTimeoutMs(20_000)
						.setReadTimeoutMs(30_000)
						.setAllowCrossProtocolRedirects(false);
		MediaMetadata.Builder metadata = new MediaMetadata.Builder()
						.setTitle(video.title())
						.setArtist(video.author());
		byte[] artwork = video.artwork();
		if (artwork != null) metadata.setArtworkData(artwork, MediaMetadata.PICTURE_TYPE_FRONT_COVER);
		MediaItem item = new MediaItem.Builder()
						.setUri(video.streamUrl())
						.setMediaId(video.id())
						.setMediaMetadata(metadata.build())
						.build();
		return video.hls()
						? new HlsMediaSource.Factory(http).createMediaSource(item)
						: new ProgressiveMediaSource.Factory(http).createMediaSource(item);
	}

	public void play() {
		this.player.play();
	}

	/** Reloads with fresh stream URLs at the position (every stream got rejected). */
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
		player.setMediaSource(PlaybackSourceFactory.create(sources, autoSubtitleFormat, details, plan));
		player.seekTo(Math.max(0L, positionMs));
		player.setPlaybackParameters(new PlaybackParameters(speed));
		player.prepare();
		player.setPlayWhenReady(true);
	}

	@Nullable
	public String getVideoId() {
		return videoId;
	}

	/** Chapters and likes that came after the start. */
	public void applyExtras(@NonNull PlaybackDetails details) {
		if (!Objects.equals(details.video().getId(), videoId)) return;
		videoDetails = details.video();
		segments = details.segments();
	}

	/**
	 * After a stream got rejected, tries the other streams of the same extraction. A 403 rules out
	 * the whole client (YouTube blocks per client), so its other formats are skipped too. False when
	 * nothing's left; re-extract then.
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
		// a broken HDR stream goes straight to the muxed H.264 one; another HDR variant would most
		// likely fail (or go blue) the same way
		PlaybackPlan fallback = isHdrPlan(state.plan()) ? null : PlaybackPlanner.adaptiveFallbackPlan(
						state.deliveries(),
						prefs.getPreferredQuality(),
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
	 * Spends one re-extraction from the per-video budget.
	 * @return false when the budget's gone
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
			player.setMediaSource(PlaybackSourceFactory.create(sources, autoSubtitleFormat,
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

	/** Current video is HDR (VP9.2, AV1 10-bit+). */
	private static boolean isHdrPlan(@NonNull PlaybackPlan plan) {
		for (StreamCandidate candidate : new StreamCandidate[]{plan.getVideoCandidate(), plan.getMuxedCandidate()}) {
			if (candidate != null && candidate.getVideoStream() != null
							&& PlayerUtils.isLikelyHdr(candidate.getVideoStream().getCodec())) {
				return true;
			}
		}
		return false;
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

	/** Video track on/off, audio keeps playing. */
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

	/** @param manual user asked for next (button, notification, bar); false when the video ended */
	public void skipToNext(boolean manual) {
		boolean hasQueueItems = queueRepository.hasItems();
		String watchId = watchVideoId();
		boolean hasPlaylist = tabManager.watchHasPlaylist();
		boolean queueContext = hasQueueItems;
		boolean playlistContext = !queueContext && hasPlaylist;
		if (queueContext) {
			// inside the queue follow its order; from any other video start at its first item
			// played items leave the queue, so what's in it hasn't played
			QueueItem item = queueRepository.findRelative(watchId, 1);
			if (item != null && item.getVideoUrl() != null) {
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
	 * Skips an unplayable queue/playlist video instead of failing.
	 * @return true if skipped
	 */
	public boolean skipUnavailable(@NonNull String unavailableId) {
		boolean queueContext = queueRepository.hasItems();
		boolean playlistContext = !queueContext && tabManager.watchHasPlaylist();
		if (!queueContext && !playlistContext) return false;
		skipToNext(false);
		removeFromQueue(unavailableId);
		return true;
	}

	/**
	 * Autoplay: next suggestion (extractor, YouTube's "next", a search, Home).
	 * @param manual user pressed next; false when the video ended
	 */
	private void autoplaySuggestion(@Nullable String fromId, boolean manual) {
		String sourceId = fromId != null ? fromId : videoId;
		if (sourceId == null) return;
		if (prefetchedPick != null && sourceId.equals(prefetchedFor)) {
			// already picked and preloaded, go
			Candidate pick = prefetchedPick;
			prefetchedPick = null;
			startNext(pick);
			return;
		}
		long token = ++autoplayToken;
		requestSuggestions(sourceId, manual, token);
	}

	/** A possible next video. */
	private record Candidate(@NonNull String id, @Nullable String author) {
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
				// nothing yet, retry shortly
				handler.postDelayed(() -> {
					if (token == autoplayToken) requestSuggestions(sourceId, manual, token, attempt + 1);
				}, SUGGESTION_RETRY_DELAY_MS);
				return;
			}
			playSuggestion(sourceId, found, manual);
		});
	}

	/**
	 * Next candidates from the first source that has any: extractor suggestions, YouTube's "next",
	 * a title search, then Home.
	 */
	private void gatherSuggestions(@NonNull String sourceId, long token, @NonNull Consumer<List<Candidate>> done) {
		if (ExternalIds.isExternal(sourceId)) {
			// external videos have no YouTube suggestions, search the title
			FeedClient feed = feedClient;
			if (feed == null) {
				done.accept(List.of());
				return;
			}
			searchSuggestions(feed, sourceId, token, done);
			return;
		}
		extractor.getRelatedVideos(sourceId).whenComplete((related, error) -> handler.post(() -> {
			if (token != autoplayToken) return;
			if (error != null) Log.w(TAG, "suggestions unavailable videoId=" + sourceId, error);
			List<Candidate> found = new ArrayList<>();
			if (related != null) {
				for (RelatedVideo video : related) {
					found.add(new Candidate(video.id(), video.uploaderName()));
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
				searchSuggestions(feed, sourceId, token, done);
			});
		}));
	}

	/** Search for the title (or channel), then Home. */
	private void searchSuggestions(@NonNull FeedClient feed, @NonNull String sourceId, long token,
	                               @NonNull Consumer<List<Candidate>> done) {
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
					found.add(new Candidate(item.videoId(), item.author()));
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
		// user moved on meanwhile
		if (!Objects.equals(sourceId, watchVideoId())) return;
		Candidate pick = pickSuggestion(sourceId, found);
		if (pick == null) {
			Log.i(TAG, "no suggestion videoId=" + sourceId);
			if (manual) ToastUtils.show(appContext, R.string.no_suggestions);
			return;
		}
		startNext(pick);
	}

	private void startNext(@NonNull Candidate pick) {
		recordAuthor(pick.author());
		tabManager.playInWatch(Constant.HOME_URL + "/watch?v=" + pick.id());
	}

	private void recordAuthor(@Nullable String author) {
		if (author == null || author.isBlank()) return;
		recentAuthors.remove(author);
		recentAuthors.addFirst(author);
		while (recentAuthors.size() > RECENT_AUTHORS) recentAuthors.removeLast();
	}

	/**
	 * Random pick from the top suggestions, preferring unwatched, not recently played, and another
	 * channel. Falls back to anything.
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

	/**
	 * Preload shares the connection with the current video, so it waits until that's buffered well
	 * ahead (or fully), unless the end is close.
	 */
	private boolean canPreload(long position, long duration, long remaining) {
		if (remaining <= PRELOAD_ANYWAY_MS) return true;
		long buffered = player.getBufferedPosition();
		return buffered >= duration - 1_000L || buffered - position >= PRELOAD_MIN_BUFFER_MS;
	}

	private void prefetchNext(@NonNull String sourceId) {
		if (!loopMode.skipsToNextOnEnded()) return;
		if (queueRepository.hasItems()) {
			QueueItem item = queueRepository.findRelative(watchVideoId(), 1);
			if (item != null && item.getVideoUrl() != null) preload(item.getVideoUrl());
			return;
		}
		if (tabManager.watchHasPlaylist()) return;
		gatherSuggestions(sourceId, autoplayToken, found -> rememberPrefetch(sourceId, pickSuggestion(sourceId, found)));
	}

	private void rememberPrefetch(@NonNull String sourceId, @Nullable Candidate pick) {
		if (pick == null || !sourceId.equals(prefetchedFor) || !sourceId.equals(videoId)) return;
		prefetchedPick = pick;
		preload(Constant.HOME_URL + "/watch?v=" + pick.id());
	}

	/**
	 * Extracts the next video and caches its first seconds so it starts instantly. The next play
	 * picks both up from the caches.
	 */
	private void preload(@NonNull String url) {
		// external links expire fast, they're read when they play
		if (ExternalIds.isExternalUrl(url)) return;
		String id = YoutubeExtractor.getVideoId(url);
		if (id == null || id.equals(preloadTarget) || id.equals(videoId)) return;
		preloadTarget = id;
		// details are a few KB and always fetched; the first seconds only if the cache setting allows
		extractor.getInfo(url, new ExtractionSession().setBackground(true)).whenComplete((details, error) -> {
			if (error != null || details == null || !id.equals(preloadTarget)) return;
			if (!AppSettings.videoCache().preloadsNext) return;
			UnaryOperator<PlaybackDetails> selector = planSelector;
			try {
				preloader.preload(selector != null ? selector.apply(details) : details);
			} catch (RuntimeException e) {
				Log.w(TAG, "preload skipped videoId=" + id, e);
			}
		});
	}

	/** Queue changed: if a different video is up next now, preload that one instead. */
	public void onQueueChanged() {
		handler.post(() -> {
			String current = videoId;
			if (current == null || !current.equals(prefetchedFor) || !loopMode.skipsToNextOnEnded()) return;
			if (!queueRepository.hasItems()) return;
			QueueItem item = queueRepository.findRelative(watchVideoId(), 1);
			if (item != null && item.getVideoUrl() != null) preload(item.getVideoUrl());
		});
	}

	/** How streams get picked, so the preload caches the ones that will play. */
	public void setPlanSelector(@Nullable UnaryOperator<PlaybackDetails> selector) {
		this.planSelector = selector;
	}

	public void skipToPrevious() {
		boolean hasQueueItems = queueRepository.hasItems();
		String watchId = watchVideoId();
		boolean inQueue = queueRepository.containsVideo(watchId);
		boolean hasPlaylist = tabManager.watchHasPlaylist();
		boolean queueContext = hasQueueItems;
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
			String external = externalUrls.get(previous);
			tabManager.playInWatch(external != null ? external : Constant.HOME_URL + "/watch?v=" + previous);
			return;
		}
		if (tabManager.canGoBackInWatch()) {
			tabManager.goBackInWatch();
		}
	}

	public void playRandomPlaylistItem() {
		boolean hasQueueItems = queueRepository.hasItems();
		String watchId = watchVideoId();
		boolean hasPlaylist = tabManager.watchHasPlaylist();
		boolean queueContext = hasQueueItems;
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
		boolean hasQueueItems = queueRepository.hasItems();
		String watchId = watchVideoId();
		boolean inQueue = queueRepository.containsVideo(watchId);
		boolean hasPlaylist = tabManager.watchHasPlaylist();
		boolean canGoBack = !history.isEmpty() || tabManager.canGoBackInWatch();
		boolean playlistAtHead = UrlUtils.isPlaylistFirstItemUrl(tabManager.getWatchUrl());
		boolean queueContext = hasQueueItems;
		boolean playlistContext = !queueContext && hasPlaylist;
		boolean queueAtHead = queueContext && queueRepository.findRelative(watchId, -1) == null;
		if (queueContext) {
			boolean queuePrevEnabled = inQueue && !queueAtHead;
			boolean queueBackEnabled = canGoBack && (!inQueue || queueAtHead);
			return new QueueNav(true, queuePrevEnabled, queueBackEnabled);
		}
		if (playlistContext) {
			boolean playlistPrevEnabled = !playlistAtHead || canGoBack;
			return new QueueNav(true, false, playlistPrevEnabled);
		}
		return new QueueNav(true, false, canGoBack);
	}

	@Nullable
	private String watchVideoId() {
		String watchUrl = tabManager.getWatchUrl();
		if (watchUrl == null || watchUrl.isEmpty()) {
			return videoId;
		}
		// before reading "v=", Facebook links have one too
		if (ExternalIds.isExternalUrl(watchUrl)) {
			String id = ExternalIds.idOf(watchUrl);
			return id != null ? id : videoId;
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
		// empty: fall back to the active tracks (DASH, HLS)
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

	/** Quality picked in the player, kept for the current network. */
	public void onQualitySelected(@Nullable String res) {
		if (res == null || state() == null) return;
		prefs.setPreferredQuality(res);
		switchQuality(res, true);
	}

	/**
	 * Network switched between Wi-Fi and mobile: move to that network's quality. No-op if it's
	 * already playing.
	 */
	public void applyPreferredQuality() {
		if (player.getPlaybackState() == Player.STATE_ENDED) return;
		switchQuality(prefs.getPreferredQuality(), false);
	}

	/** @param reload reload even if the same streams would play */
	private void switchQuality(@NonNull String res, boolean reload) {
		State state = state();
		if (state == null) return;
		PlaybackPlan plan = PlaybackPlanner.plan(state.deliveries(), res);
		if (isLiveMode(plan) && plan.getDelivery() != null) {
			this.playbackPlan = plan;
			applyPlaybackTrackMode();
			return;
		}
		keepAudioTrack(state.plan(), plan);
		if (!reload && sameStreams(state.plan(), plan)) return;
		this.playbackPlan = plan;
		long pos = this.player.getCurrentPosition();
		float speed = this.player.getPlaybackParameters().speed;
		boolean playWhenReady = this.player.getPlayWhenReady();
		play(new PlaybackDetails(state.video(), state.catalog(), state.deliveries(), plan, segments, subtitles));
		if (plan.getMode() != PlaybackMode.LIVE_DASH
						&& plan.getMode() != PlaybackMode.LIVE_HLS) {
			this.player.seekTo(pos);
		}
		this.player.setPlaybackParameters(new PlaybackParameters(speed));
		// paused stays paused
		this.player.setPlayWhenReady(playWhenReady);
	}

	/** New quality keeps the playing audio language. */
	private static void keepAudioTrack(@NonNull PlaybackPlan current, @NonNull PlaybackPlan next) {
		if (current.getMode() == PlaybackMode.ADAPTIVE && next.getMode() == PlaybackMode.ADAPTIVE
						&& current.getAudioCandidate() != null) {
			next.setAudioCandidate(current.getAudioCandidate());
		}
	}

	private static boolean sameStreams(@NonNull PlaybackPlan current, @NonNull PlaybackPlan next) {
		return current.getMode() == next.getMode()
						&& Objects.equals(candidateKey(current.getVideoCandidate()), candidateKey(next.getVideoCandidate()))
						&& Objects.equals(candidateKey(current.getAudioCandidate()), candidateKey(next.getAudioCandidate()))
						&& Objects.equals(candidateKey(current.getMuxedCandidate()), candidateKey(next.getMuxedCandidate()));
	}

	private void applyPlaybackTrackMode() {
		DefaultTrackSelector trackSelector = trackSelector();
		DefaultTrackSelector.Parameters.Builder builder = params(trackSelector)
						.clearOverridesOfType(C.TRACK_TYPE_VIDEO)
						.setForceHighestSupportedBitrate(false)
						.clearVideoSizeConstraints();
		PlaybackPlan plan = playbackPlan;
		int height = StringUtils.parseHeight(prefs.getPreferredQuality());
		if (plan != null && plan.getDelivery() != null && height > 0) {
			builder.setMaxVideoSize(Integer.MAX_VALUE, height);
		}
		trackSelector.setParameters(builder.build());
	}

	/** Video stream playing now, null for live and muxed playback. */
	@Nullable
	public VideoStream getVideoStream() {
		return videoStream;
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

		// no chapters: the whole video is one, named after the title
		List<StreamSegment> whole = new ArrayList<>(1);
		VideoDetails video = videoDetails;
		if (video != null) whole.add(new StreamSegment(video.getTitle() != null ? video.getTitle() : "", 0));
		return whole;
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
		plan.setAudioCandidate(PlaybackPlanner.findAudioCandidate(state.catalog().getAudioCandidates(), stream));
		player.setMediaSource(PlaybackSourceFactory.create(sources, autoSubtitleFormat,
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
		preloadTarget = null;
		preloader.release();
		this.player.release();
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
