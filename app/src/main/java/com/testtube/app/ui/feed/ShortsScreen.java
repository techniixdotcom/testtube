package com.testtube.app.ui.feed;

import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.PagerSnapHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.squareup.picasso.Callback;
import com.squareup.picasso.Picasso;
import com.testtube.app.R;
import com.testtube.app.extractor.AvatarCache;
import com.testtube.app.extractor.ExtractionSession;
import com.testtube.app.extractor.FeedClient;
import com.testtube.app.extractor.FeedItem;
import com.testtube.app.extractor.LocalSubscriptions;
import com.testtube.app.extractor.PlaybackDetails;
import com.testtube.app.extractor.VideoDetails;
import com.testtube.app.extractor.YoutubeExtractor;
import com.testtube.app.filter.ContentFilters;
import com.testtube.app.history.WatchHistory;
import com.testtube.app.net.Failure;
import com.testtube.app.player.engine.ShortsEngine;
import com.testtube.app.util.ToastUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * The Shorts tab: one Short per screen, swipe up or down for the next one. Each Short plays as
 * soon as it settles and loops; a tap pauses it.
 */
@UnstableApi
public final class ShortsScreen {
	/** Load the next page this many Shorts before the end. */
	private static final int LOAD_MORE_AT = 3;
	/** Pages that bring nothing new before giving up for now. */
	private static final int MAX_EMPTY_PAGES = 3;
	private static final long PROGRESS_INTERVAL_MS = 250L;

	@NonNull
	private final Handler handler = new Handler(Looper.getMainLooper());
	@NonNull
	private final FeedClient client;
	@NonNull
	private final YoutubeExtractor extractor;
	@NonNull
	private final ShortsEngine engine;
	@NonNull
	private final ContentFilters filters;
	@NonNull
	private final WatchHistory history;
	@NonNull
	private final Executor executor;
	@NonNull
	private final NativeBrowser.Host host;
	@NonNull
	private final RecyclerView list;
	@NonNull
	private final LinearLayoutManager layoutManager;
	@NonNull
	private final PagerSnapHelper snapHelper = new PagerSnapHelper();
	@NonNull
	private final ProgressBar loading;
	@NonNull
	private final TextView message;
	@NonNull
	private final PlayerView playerView;
	@NonNull
	private final ShortsAdapter adapter = new ShortsAdapter();
	@NonNull
	private final List<FeedItem> items = new ArrayList<>();
	@NonNull
	private final Set<String> seen = new HashSet<>();
	/** Channel, title and picture of the Shorts that were opened, by video id. */
	@NonNull
	private final Map<String, VideoDetails> details = new HashMap<>();
	@NonNull
	private final Runnable updateProgress = this::updateProgress;

	@Nullable
	private FeedClient.Call call;
	@Nullable
	private Object next;
	private boolean exhausted;
	private int emptyPages;

	/** Adapter position of the Short on screen. */
	private int current = RecyclerView.NO_POSITION;
	/** Video id the engine is playing (or loading), null when nothing was started. */
	@Nullable
	private String startedId;
	/** Bumped for every start, so late extractions for an earlier Short are dropped. */
	private long startToken;
	private boolean retried;
	private boolean firstFrameShown;
	private boolean active;
	@Nullable
	private Runnable onStartedPlaying;

	public ShortsScreen(@NonNull View root,
	                    @NonNull FeedClient client,
	                    @NonNull YoutubeExtractor extractor,
	                    @NonNull ShortsEngine engine,
	                    @NonNull ContentFilters filters,
	                    @NonNull WatchHistory history,
	                    @NonNull Executor executor,
	                    @NonNull NativeBrowser.Host host) {
		this.client = client;
		this.extractor = extractor;
		this.engine = engine;
		this.filters = filters;
		this.history = history;
		this.executor = executor;
		this.host = host;
		list = root.findViewById(R.id.shorts_list);
		loading = root.findViewById(R.id.shorts_loading);
		message = root.findViewById(R.id.shorts_message);
		// Inflated against a plain FrameLayout like the slots it moves between; the list can't
		// make layout params before it has a layout manager
		playerView = (PlayerView) LayoutInflater.from(root.getContext())
						.inflate(R.layout.view_shorts_player, new FrameLayout(root.getContext()), false);
		playerView.setPlayer(engine.player());
		engine.player().addListener(new Player.Listener() {
			@Override
			public void onVideoSizeChanged(@NonNull VideoSize size) {
				// Fill the screen with vertical Shorts; the odd landscape one is shown whole
				boolean landscape = size.width > size.height;
				playerView.setResizeMode(landscape
								? AspectRatioFrameLayout.RESIZE_MODE_FIT : AspectRatioFrameLayout.RESIZE_MODE_ZOOM);
			}
		});
		engine.setListener(new ShortsEngine.Listener() {
			@Override
			public void onFirstFrame() {
				firstFrameShown = true;
				ShortHolder holder = holderAt(current);
				if (holder != null) holder.showVideo();
			}

			@Override
			public void onPlayingChanged(boolean playing) {
				ShortHolder holder = holderAt(current);
				if (holder != null) holder.paused.setVisibility(engine.isPlaying() ? View.GONE : View.VISIBLE);
				if (playing && onStartedPlaying != null) onStartedPlaying.run();
			}

			@Override
			public void onError(@NonNull PlaybackException error) {
				onPlaybackFailed();
			}
		});

		layoutManager = new LinearLayoutManager(root.getContext(), RecyclerView.VERTICAL, false);
		list.setLayoutManager(layoutManager);
		list.setAdapter(adapter);
		list.setItemAnimator(null);
		snapHelper.attachToRecyclerView(list);
		list.addOnScrollListener(new RecyclerView.OnScrollListener() {
			@Override
			public void onScrollStateChanged(@NonNull RecyclerView view, int state) {
				if (state == RecyclerView.SCROLL_STATE_IDLE) settle();
			}
		});
		message.setOnClickListener(v -> {
			exhausted = false;
			emptyPages = 0;
			load();
		});
	}

	/**
	 * The tab came into view (true) or was left, covered or the app went to the background
	 * (false). Only an active screen plays.
	 */
	public void setActive(boolean active) {
		if (this.active == active) return;
		this.active = active;
		if (!active) {
			engine.pause();
			handler.removeCallbacks(updateProgress);
			return;
		}
		if (items.isEmpty()) {
			if (call == null) load();
			return;
		}
		if (current == RecyclerView.NO_POSITION) {
			list.post(this::settle);
		} else if (Objects.equals(startedId, items.get(current).videoId())) {
			engine.resume();
			startProgress();
		} else {
			start(false);
		}
	}

	/** Runs every time a Short starts or resumes. */
	public void setOnStartedPlaying(@Nullable Runnable listener) {
		onStartedPlaying = listener;
	}

	/** Pauses the Short on screen, which stays paused until it is tapped. */
	public void pause() {
		if (active) engine.pause();
	}

	public void release() {
		onStartedPlaying = null;
		handler.removeCallbacksAndMessages(null);
		if (call != null) call.cancel();
		call = null;
		startToken++;
		engine.setListener(null);
		playerView.setPlayer(null);
		engine.release();
	}

	private void load() {
		if (call != null || exhausted) return;
		if (items.isEmpty()) {
			loading.setVisibility(View.VISIBLE);
			message.setVisibility(View.GONE);
		}
		FeedClient.Call request = client.shorts(next);
		call = request;
		request.result.whenComplete((page, error) -> handler.post(() -> {
			if (call != request) return;
			call = null;
			loading.setVisibility(View.GONE);
			if (error != null || page == null) {
				if (items.isEmpty()) {
					// Say why when it's something the person can act on (offline, YouTube's pause)
					Failure failure = error != null ? Failure.report(error) : Failure.OTHER;
					showMessage(failure == Failure.OFFLINE || failure == Failure.SLOW_DOWN || failure == Failure.TIMEOUT
									? failure.message : R.string.shorts_load_failed);
				}
				return;
			}
			next = page.next();
			exhausted = next == null;
			int before = items.size();
			for (FeedItem item : page.items()) {
				if (isShown(item) && seen.add(item.videoId())) items.add(item);
			}
			int added = items.size() - before;
			if (added > 0) {
				emptyPages = 0;
				adapter.notifyItemRangeInserted(before, added);
				if (current == RecyclerView.NO_POSITION && active) list.post(this::settle);
			} else if (!exhausted && ++emptyPages < MAX_EMPTY_PAGES) {
				load();
				return;
			}
			if (items.isEmpty()) showMessage(R.string.shorts_empty);
		}));
	}

	private boolean isShown(@NonNull FeedItem item) {
		return item.shortForm() && item.videoId() != null
						&& !filters.isChannelBlocked(item.author(), item.authorUrl());
	}

	private void showMessage(int text) {
		message.setText(text);
		message.setVisibility(View.VISIBLE);
	}

	/** Plays the Short the list snapped to. */
	private void settle() {
		View snapped = snapHelper.findSnapView(layoutManager);
		if (snapped == null) return;
		int position = layoutManager.getPosition(snapped);
		if (position == RecyclerView.NO_POSITION || position == current) return;
		ShortHolder previous = holderAt(current);
		if (previous != null) previous.showCover();
		current = position;
		if (position >= items.size() - LOAD_MORE_AT) load();
		if (active) start(false);
	}

	/**
	 * Extracts the current Short and plays it.
	 *
	 * @param refresh true to skip the cached extraction (its stream URLs were rejected)
	 */
	private void start(boolean refresh) {
		if (current == RecyclerView.NO_POSITION || current >= items.size()) return;
		FeedItem item = items.get(current);
		String videoId = item.videoId();
		long token = ++startToken;
		if (!refresh) retried = false;
		firstFrameShown = false;
		startedId = videoId;
		engine.stop();
		handler.removeCallbacks(updateProgress);
		detachPlayer();
		ShortHolder holder = holderAt(current);
		if (holder != null) holder.showLoading();
		CompletableFuture<PlaybackDetails> extraction = extractor.getInfo(item.url(), null, refresh);
		extraction.whenComplete((result, error) -> handler.post(() -> {
			if (token != startToken) return;
			ShortHolder target = holderAt(current);
			if (error != null || result == null) {
				if (target != null) target.showError();
				return;
			}
			details.put(videoId, result.video());
			if (target != null) {
				target.bindDetails(result.video());
				target.attachPlayer(playerView);
			}
			try {
				engine.play(result);
			} catch (IllegalArgumentException e) {
				if (target != null) target.showError();
				return;
			}
			if (!active) engine.pause();
			else startProgress();
			record(result.video());
			prefetch(current + 1);
		}));
	}

	/** Stream URLs can expire; try once more with a fresh extraction before giving up. */
	private void onPlaybackFailed() {
		if (!retried) {
			retried = true;
			start(true);
			return;
		}
		ShortHolder holder = holderAt(current);
		if (holder != null) holder.showError();
	}

	/** Extracts the next Short ahead of time, so swiping to it starts right away. */
	private void prefetch(int position) {
		if (position < 0 || position >= items.size()) return;
		extractor.getInfo(items.get(position).url(), new ExtractionSession().setBackground(true));
	}

	private void record(@NonNull VideoDetails video) {
		String id = video.getId();
		String title = video.getTitle();
		String author = video.getAuthor();
		String thumbnail = video.getThumbnailUrl();
		executor.execute(() -> history.record(id, title, author, thumbnail));
	}

	private void togglePlayback() {
		if (startedId == null) return;
		if (engine.isPlaying()) engine.pause();
		else engine.resume();
	}

	private void startProgress() {
		handler.removeCallbacks(updateProgress);
		handler.post(updateProgress);
	}

	private void updateProgress() {
		ShortHolder holder = holderAt(current);
		if (holder != null) holder.progress.setProgress(Math.round(engine.progress() * 1000));
		if (active) handler.postDelayed(updateProgress, PROGRESS_INTERVAL_MS);
	}

	private void detachPlayer() {
		ViewParent parent = playerView.getParent();
		if (parent instanceof ViewGroup group) group.removeView(playerView);
	}

	@Nullable
	private ShortHolder holderAt(int position) {
		if (position == RecyclerView.NO_POSITION) return null;
		RecyclerView.ViewHolder holder = list.findViewHolderForAdapterPosition(position);
		return holder instanceof ShortHolder shortHolder ? shortHolder : null;
	}

	private final class ShortsAdapter extends RecyclerView.Adapter<ShortHolder> {
		@NonNull
		@Override
		public ShortHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
			return new ShortHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_short, parent, false));
		}

		@Override
		public void onBindViewHolder(@NonNull ShortHolder holder, int position) {
			holder.bind(items.get(position), position);
		}

		@Override
		public int getItemCount() {
			return items.size();
		}

		@Override
		public void onViewRecycled(@NonNull ShortHolder holder) {
			Picasso.get().cancelRequest(holder.thumbnail);
			Picasso.get().cancelRequest(holder.avatar);
			if (holder.slot.indexOfChild(playerView) >= 0) holder.slot.removeView(playerView);
		}
	}

	private final class ShortHolder extends RecyclerView.ViewHolder {
		@NonNull
		final ImageView thumbnail;
		@NonNull
		final FrameLayout slot;
		@NonNull
		final ProgressBar buffering;
		@NonNull
		final ImageView paused;
		@NonNull
		final TextView error;
		@NonNull
		final View channel;
		@NonNull
		final ImageView avatar;
		@NonNull
		final TextView author;
		@NonNull
		final TextView title;
		@NonNull
		final TextView follow;
		@NonNull
		final ImageButton more;
		@NonNull
		final ProgressBar progress;

		ShortHolder(@NonNull View itemView) {
			super(itemView);
			thumbnail = itemView.findViewById(R.id.short_thumbnail);
			slot = itemView.findViewById(R.id.short_player_slot);
			buffering = itemView.findViewById(R.id.short_buffering);
			paused = itemView.findViewById(R.id.short_paused);
			error = itemView.findViewById(R.id.short_error);
			channel = itemView.findViewById(R.id.short_channel);
			avatar = itemView.findViewById(R.id.short_avatar);
			author = itemView.findViewById(R.id.short_author);
			title = itemView.findViewById(R.id.short_title);
			follow = itemView.findViewById(R.id.short_follow);
			more = itemView.findViewById(R.id.short_more);
			progress = itemView.findViewById(R.id.short_progress);
			itemView.setOnClickListener(v -> {
				if (getBindingAdapterPosition() == current) togglePlayback();
			});
		}

		void bind(@NonNull FeedItem item, int position) {
			title.setText(item.title());
			loadThumbnail(item.videoId());
			more.setOnClickListener(v -> host.showMenu(item));
			VideoDetails known = details.get(item.videoId());
			if (known != null) {
				bindDetails(known);
			} else {
				bindChannel(item.author(), item.authorUrl(), item.authorAvatarUrl());
			}
			boolean playingHere = position == current && Objects.equals(startedId, item.videoId());
			if (playingHere) {
				attachPlayer(playerView);
				if (firstFrameShown) showVideo();
				else showLoading();
				paused.setVisibility(engine.isPlaying() ? View.GONE : View.VISIBLE);
			} else {
				showCover();
			}
		}

		/** The vertical thumbnail of a Short, or the regular one when there is none. */
		private void loadThumbnail(@Nullable String videoId) {
			if (videoId == null) {
				thumbnail.setImageDrawable(null);
				return;
			}
			Picasso.get().load("https://i.ytimg.com/vi/" + videoId + "/oar2.jpg").into(thumbnail, new Callback() {
				@Override
				public void onSuccess() {
				}

				@Override
				public void onError(Exception e) {
					Picasso.get().load("https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg").into(thumbnail);
				}
			});
		}

		void bindDetails(@NonNull VideoDetails video) {
			if (video.getTitle() != null) title.setText(video.getTitle());
			bindChannel(video.getAuthor(), FeedClient.mobile(video.getUploaderUrl()), video.getUploaderAvatarUrl());
		}

		private void bindChannel(@Nullable String name, @Nullable String url, @Nullable String avatarUrl) {
			author.setText(name != null ? name : "");
			channel.setVisibility(name != null && !name.isBlank() ? View.VISIBLE : View.INVISIBLE);
			channel.setOnClickListener(url != null ? v -> host.openPage(url) : null);
			String image = avatarUrl != null ? AvatarCache.rowSize(avatarUrl) : AvatarCache.get().find(url);
			if (image != null) {
				Picasso.get().load(image).fit().centerCrop()
								.placeholder(R.drawable.bg_avatar_placeholder)
								.error(R.drawable.bg_avatar_placeholder)
								.into(avatar);
			} else {
				Picasso.get().cancelRequest(avatar);
				avatar.setImageResource(R.drawable.bg_avatar_placeholder);
			}
			bindFollow(name, LocalSubscriptions.channelIdOf(url));
		}

		/** Follows the channel locally, so its videos show up in Subscriptions. */
		private void bindFollow(@Nullable String name, @Nullable String channelId) {
			if (channelId == null) {
				follow.setVisibility(View.GONE);
				follow.setOnClickListener(null);
				return;
			}
			LocalSubscriptions subscriptions = LocalSubscriptions.get();
			follow.setVisibility(View.VISIBLE);
			follow.setText(subscriptions.isFollowing(channelId) ? R.string.watch_following : R.string.watch_follow);
			follow.setOnClickListener(v -> {
				if (subscriptions.isFollowing(channelId)) {
					subscriptions.remove(channelId);
				} else {
					subscriptions.add(new LocalSubscriptions.Channel(channelId, name != null ? name : ""));
					ToastUtils.show(v.getContext(), R.string.subs_followed);
				}
				follow.setText(subscriptions.isFollowing(channelId) ? R.string.watch_following : R.string.watch_follow);
				host.onFollowChanged();
			});
		}

		void attachPlayer(@NonNull PlayerView player) {
			if (slot.indexOfChild(player) >= 0) return;
			ViewParent parent = player.getParent();
			if (parent instanceof ViewGroup group) group.removeView(player);
			slot.addView(player, new FrameLayout.LayoutParams(
							ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
		}

		/** Thumbnail only, as for every Short that isn't playing. */
		void showCover() {
			thumbnail.setVisibility(View.VISIBLE);
			buffering.setVisibility(View.GONE);
			paused.setVisibility(View.GONE);
			error.setVisibility(View.GONE);
			progress.setProgress(0);
			if (slot.indexOfChild(playerView) >= 0) slot.removeView(playerView);
		}

		void showLoading() {
			thumbnail.setVisibility(View.VISIBLE);
			buffering.setVisibility(View.VISIBLE);
			paused.setVisibility(View.GONE);
			error.setVisibility(View.GONE);
		}

		void showVideo() {
			thumbnail.setVisibility(View.INVISIBLE);
			buffering.setVisibility(View.GONE);
			error.setVisibility(View.GONE);
		}

		void showError() {
			thumbnail.setVisibility(View.VISIBLE);
			buffering.setVisibility(View.GONE);
			paused.setVisibility(View.GONE);
			error.setVisibility(View.VISIBLE);
		}
	}
}
