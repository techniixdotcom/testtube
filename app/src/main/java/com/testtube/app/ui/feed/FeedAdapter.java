package com.testtube.app.ui.feed;

import android.content.Context;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.testtube.app.AppSettings;
import com.testtube.app.R;
import com.testtube.app.extractor.AvatarCache;
import com.testtube.app.extractor.FeedItem;
import com.testtube.app.extractor.Originals;
import com.testtube.app.filter.ContentFilters;
import com.testtube.app.util.ImageUtils;
import com.testtube.app.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class FeedAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
	private static final int TYPE_VIDEO = 0;
	private static final int TYPE_ENTITY = 1;
	private static final int TYPE_VIDEO_COMPACT = 2;
	private static final String SEPARATOR = " • ";
	private static final Object AVATAR_PAYLOAD = new Object();
	private static final Object ORIGINAL_PAYLOAD = new Object();

	/** Rows ahead of the one being shown whose pictures start loading already. */
	private static final int PREFETCH_AHEAD = 4;
	/** Rows of a freshly loaded list whose pictures start loading right away. */
	private static final int PREFETCH_ON_LOAD = 8;
	private static final int MAX_PREFETCHED = 400;

	/** How dark the darkest setting makes the images; a little stays visible. */
	private static final float DARKEST = 0.1f;
	private static final float FAINTEST_TEXT = 0.35f;

	@Nullable
	private static ColorMatrixColorFilter watchedFilter;
	private static int watchedFilterKey = -1;

	/**
	 * Black and white, darkened by the setting: the lowest is plain greyscale, the highest almost
	 * black. Kept until the setting changes, since every watched row uses the same one.
	 */
	@NonNull
	private static ColorMatrixColorFilter watchedFilter(float darkness) {
		int key = Math.round(darkness * 1000f);
		ColorMatrixColorFilter filter = watchedFilter;
		if (filter != null && watchedFilterKey == key) return filter;
		ColorMatrix matrix = new ColorMatrix();
		matrix.setSaturation(0f);
		float brightness = 1f - (1f - DARKEST) * darkness;
		ColorMatrix darken = new ColorMatrix();
		darken.setScale(brightness, brightness, brightness, 1f);
		matrix.postConcat(darken);
		filter = new ColorMatrixColorFilter(matrix);
		watchedFilter = filter;
		watchedFilterKey = key;
		return filter;
	}

	interface Listener {
		void onOpen(@NonNull FeedItem item);

		void onOpenAuthor(@NonNull FeedItem item);

		/** Long press on the channel picture. */
		void onFollowAuthor(@NonNull FeedItem item);

		void onMenu(@NonNull FeedItem item);
	}

	@NonNull
	private final ContentFilters filters;
	@NonNull
	private final Listener listener;
	@NonNull
	private final Map<String, Long> ids = new HashMap<>();
	@NonNull
	private final AvatarCache avatars = AvatarCache.get();
	@NonNull
	private final AvatarCache.Listener avatarListener = this::onAvatarKnown;
	@NonNull
	private final Originals originals = Originals.get();
	@NonNull
	private final Originals.Listener originalsListener = this::onOriginalKnown;
	@NonNull
	private List<FeedItem> items = new ArrayList<>();
	private boolean showAvatars;
	private boolean compactRows;
	/** Pictures already asked for ahead of time, so scrolling doesn't ask again and again. */
	@NonNull
	private final Set<String> prefetched = new HashSet<>();
	/** From the list the adapter is shown in; null until then. */
	@Nullable
	private Context context;
	private int attached;

	FeedAdapter(@NonNull ContentFilters filters, @NonNull Listener listener) {
		this.filters = filters;
		this.listener = listener;
		setHasStableIds(true);
	}

	/**
	 * Shows a new list. Appending only inserts the new rows; anything else is diffed, so rows
	 * slotting in above (Subscriptions sorting by date) don't make the list jump.
	 */
	void submit(@NonNull List<FeedItem> next) {
		avatars.rememberAll(next);
		List<FeedItem> previous = items;
		boolean appended = next.size() >= previous.size();
		for (int i = 0; appended && i < previous.size(); i++) {
			if (!previous.get(i).url().equals(next.get(i).url())) appended = false;
		}
		if (appended) {
			items = next;
			if (next.size() > previous.size()) notifyItemRangeInserted(previous.size(), next.size() - previous.size());
			if (previous.isEmpty()) prefetch(0, PREFETCH_ON_LOAD);
			return;
		}
		DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
			@Override
			public int getOldListSize() {
				return previous.size();
			}

			@Override
			public int getNewListSize() {
				return next.size();
			}

			@Override
			public boolean areItemsTheSame(int oldPosition, int newPosition) {
				return previous.get(oldPosition).url().equals(next.get(newPosition).url());
			}

			@Override
			public boolean areContentsTheSame(int oldPosition, int newPosition) {
				return previous.get(oldPosition).equals(next.get(newPosition));
			}
		});
		items = next;
		diff.dispatchUpdatesTo(this);
		prefetch(0, PREFETCH_ON_LOAD);
	}

	/**
	 * Uploader images next to the titles. Off by default; Home and the suggestions under the
	 * player turn them on.
	 */
	void setShowAvatars(boolean show) {
		if (showAvatars == show) return;
		showAvatars = show;
		notifyItemRangeChanged(0, items.size());
	}

	/**
	 * Small thumbnails with the title and details next to them, as on channel pages, instead of
	 * full-width thumbnails.
	 */
	void setCompactRows(boolean compact) {
		if (compactRows == compact) return;
		compactRows = compact;
		notifyDataSetChanged();
	}

	/**
	 * The video shown in this row, or null for other rows (channels, playlists, the header).
	 */
	@Nullable
	FeedItem itemAt(@NonNull RecyclerView.ViewHolder holder) {
		if (!(holder instanceof VideoHolder) || holder.getBindingAdapter() != this) return null;
		int position = holder.getBindingAdapterPosition();
		return position >= 0 && position < items.size() ? originals.apply(items.get(position)) : null;
	}

	void refreshStates() {
		notifyItemRangeChanged(0, items.size());
	}

	@Override
	public void onAttachedToRecyclerView(@NonNull RecyclerView recyclerView) {
		super.onAttachedToRecyclerView(recyclerView);
		context = recyclerView.getContext();
		if (attached++ == 0) {
			avatars.addListener(avatarListener);
			originals.addListener(originalsListener);
		}
	}

	@Override
	public void onDetachedFromRecyclerView(@NonNull RecyclerView recyclerView) {
		super.onDetachedFromRecyclerView(recyclerView);
		if (--attached <= 0) {
			attached = 0;
			avatars.removeListener(avatarListener);
			originals.removeListener(originalsListener);
		}
	}

	private void onAvatarKnown(@NonNull String key) {
		if (!showAvatars) return;
		for (int i = 0; i < items.size(); i++) {
			FeedItem item = items.get(i);
			if (item.kind() == FeedItem.Kind.VIDEO && item.authorAvatarUrl() == null
							&& key.equals(AvatarCache.key(item.authorUrl()))) {
				notifyItemChanged(i, AVATAR_PAYLOAD);
			}
		}
	}

	private void onOriginalKnown(@NonNull String videoId) {
		for (int i = 0; i < items.size(); i++) {
			if (videoId.equals(items.get(i).videoId())) notifyItemChanged(i, ORIGINAL_PAYLOAD);
		}
	}

	@Override
	public long getItemId(int position) {
		String url = items.get(position).url();
		Long id = ids.get(url);
		if (id == null) {
			id = (long) ids.size();
			ids.put(url, id);
		}
		return id;
	}

	@Override
	public int getItemCount() {
		return items.size();
	}

	@Override
	public int getItemViewType(int position) {
		if (items.get(position).kind() != FeedItem.Kind.VIDEO) return TYPE_ENTITY;
		return compactRows ? TYPE_VIDEO_COMPACT : TYPE_VIDEO;
	}

	@NonNull
	@Override
	public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
		LayoutInflater inflater = LayoutInflater.from(parent.getContext());
		if (viewType == TYPE_VIDEO) {
			return new VideoHolder(inflater.inflate(R.layout.item_feed_video, parent, false));
		}
		if (viewType == TYPE_VIDEO_COMPACT) {
			return new VideoHolder(inflater.inflate(R.layout.item_feed_video_compact, parent, false));
		}
		return new EntityHolder(inflater.inflate(R.layout.item_feed_entity, parent, false));
	}

	@Override
	public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
		prefetch(position + 1, PREFETCH_AHEAD);
		FeedItem item = items.get(position);
		if (holder instanceof VideoHolder video) {
			bindVideo(video, item);
		} else if (holder instanceof EntityHolder entity) {
			bindEntity(entity, item);
		}
	}

	@Override
	public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position,
	                             @NonNull List<Object> payloads) {
		if (!payloads.isEmpty() && holder instanceof VideoHolder video) {
			boolean partial = true;
			for (Object payload : payloads) {
				if (payload != AVATAR_PAYLOAD && payload != ORIGINAL_PAYLOAD) {
					partial = false;
					break;
				}
			}
			if (partial) {
				FeedItem item = items.get(position);
				if (payloads.contains(AVATAR_PAYLOAD)) bindAvatar(video, item);
				// Whole row, so taps and the menu also get the original names
				if (payloads.contains(ORIGINAL_PAYLOAD)) bindVideo(video, item);
				return;
			}
		}
		onBindViewHolder(holder, position);
	}

	@Override
	public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
		if (holder instanceof VideoHolder video) {
			ImageUtils.clear(video.thumbnail);
			ImageUtils.clear(video.avatar);
			video.avatarUrl = null;
		}
		if (holder instanceof EntityHolder entity) ImageUtils.clear(entity.thumbnail);
	}

	private void bindVideo(@NonNull VideoHolder holder, @NonNull FeedItem listed) {
		FeedItem item = originals.apply(listed);
		// Not known yet: looked up, and the row updates when it arrives
		if (originals.find(listed.videoId()) == null) originals.request(listed.videoId());
		Context context = holder.itemView.getContext();
		holder.title.setText(item.title());
		List<String> meta = new ArrayList<>(3);
		if (item.author() != null) meta.add(item.author());
		if (!item.live() && item.count() >= 0) {
			meta.add(context.getString(R.string.feed_views, StringUtils.compactCount(item.count())));
		}
		if (!item.live() && item.published() != null) meta.add(item.published());
		holder.meta.setText(String.join(SEPARATOR, meta));
		if (item.live()) {
			holder.duration.setVisibility(View.VISIBLE);
			holder.duration.setText(R.string.feed_live);
			holder.duration.setBackgroundResource(R.drawable.bg_live_badge);
		} else if (item.durationSeconds() > 0) {
			holder.duration.setVisibility(View.VISIBLE);
			holder.duration.setText(DateUtils.formatElapsedTime(item.durationSeconds()));
			holder.duration.setBackgroundResource(R.drawable.bg_duration_badge);
		} else if (item.shortForm()) {
			holder.duration.setVisibility(View.VISIBLE);
			holder.duration.setText(R.string.feed_shorts);
			holder.duration.setBackgroundResource(R.drawable.bg_duration_badge);
		} else {
			holder.duration.setVisibility(View.GONE);
		}
		boolean watched = AppSettings.greyOutWatched() && filters.isWatched(item.videoId());
		// On the children, not the row: RecyclerView's change animation resets the row's alpha
		float darkness = watched ? AppSettings.watchedDarkness() : 0f;
		ColorMatrixColorFilter filter = watched ? watchedFilter(darkness) : null;
		holder.thumbnail.setColorFilter(filter);
		holder.avatar.setColorFilter(filter);
		float textAlpha = 1f - (1f - FAINTEST_TEXT) * darkness;
		holder.title.setAlpha(textAlpha);
		holder.meta.setAlpha(textAlpha);
		holder.duration.setAlpha(textAlpha);
		ImageUtils.load(holder.thumbnail, item.thumbnailUrl(), R.drawable.thumb_clear);
		bindAvatar(holder, item);
		holder.itemView.setOnClickListener(v -> listener.onOpen(item));
		holder.itemView.setOnLongClickListener(v -> {
			listener.onMenu(item);
			return true;
		});
		holder.more.setOnClickListener(v -> listener.onMenu(item));
		if (item.authorUrl() != null) {
			View.OnClickListener openAuthor = v -> listener.onOpenAuthor(item);
			holder.meta.setOnClickListener(openAuthor);
			holder.avatar.setOnClickListener(openAuthor);
			holder.avatar.setOnLongClickListener(v -> {
				listener.onFollowAuthor(item);
				return true;
			});
		} else {
			holder.meta.setOnClickListener(null);
			holder.meta.setClickable(false);
			holder.avatar.setOnClickListener(null);
			holder.avatar.setOnLongClickListener(null);
			holder.avatar.setClickable(false);
			holder.avatar.setLongClickable(false);
		}
	}

	private void bindAvatar(@NonNull VideoHolder holder, @NonNull FeedItem item) {
		if (!showAvatars) {
			holder.avatar.setVisibility(View.GONE);
			ImageUtils.clear(holder.avatar);
			holder.avatarUrl = null;
			return;
		}
		holder.avatar.setVisibility(View.VISIBLE);
		String url = AvatarCache.rowSize(item.authorAvatarUrl());
		if (url == null) url = avatars.find(item.authorUrl());
		if (url == null) {
			avatars.request(item.authorUrl());
			ImageUtils.clear(holder.avatar);
			holder.avatar.setImageResource(R.drawable.bg_avatar_placeholder);
			holder.avatarUrl = null;
			return;
		}
		// Already showing (or loading) this image, don't flicker
		if (Objects.equals(url, holder.avatarUrl)) return;
		holder.avatarUrl = url;
		ImageUtils.load(holder.avatar, url, R.drawable.bg_avatar_placeholder);
	}

	private void bindEntity(@NonNull EntityHolder holder, @NonNull FeedItem item) {
		Context context = holder.itemView.getContext();
		holder.title.setText(item.title());
		List<String> meta = new ArrayList<>(2);
		if (item.kind() == FeedItem.Kind.CHANNEL) {
			meta.add(context.getString(R.string.feed_channel));
			if (item.count() >= 0) meta.add(context.getString(R.string.feed_subscribers, StringUtils.compactCount(item.count())));
		} else {
			meta.add(item.author() != null ? item.author() : context.getString(R.string.type_playlist));
			if (item.count() >= 0) meta.add(context.getString(R.string.feed_videos, StringUtils.compactCount(item.count())));
		}
		holder.meta.setText(String.join(SEPARATOR, meta));
		ImageUtils.load(holder.thumbnail, item.thumbnailUrl(), R.drawable.bg_thumbnail_placeholder);
		holder.itemView.setOnClickListener(v -> listener.onOpen(item));
	}

	/** Starts downloading the pictures of rows that aren't on screen yet. */
	private void prefetch(int from, int count) {
		int end = Math.min(items.size(), from + count);
		for (int i = Math.max(0, from); i < end; i++) {
			FeedItem item = items.get(i);
			fetch(item.thumbnailUrl());
			if (showAvatars && item.kind() == FeedItem.Kind.VIDEO) {
				String avatar = AvatarCache.rowSize(item.authorAvatarUrl());
				fetch(avatar != null ? avatar : avatars.find(item.authorUrl()));
			}
		}
	}

	private void fetch(@Nullable String url) {
		Context current = context;
		if (current == null || url == null || url.isBlank() || !prefetched.add(url)) return;
		if (prefetched.size() > MAX_PREFETCHED) prefetched.clear();
		ImageUtils.prefetch(current, url);
	}

	private static final class VideoHolder extends RecyclerView.ViewHolder {
		@NonNull
		final ImageView thumbnail;
		@NonNull
		final ImageView avatar;
		@NonNull
		final TextView duration;
		@NonNull
		final TextView title;
		@NonNull
		final TextView meta;
		@NonNull
		final ImageButton more;
		/** Image shown in {@link #avatar}, null for the placeholder. */
		@Nullable
		String avatarUrl;

		VideoHolder(@NonNull View itemView) {
			super(itemView);
			thumbnail = itemView.findViewById(R.id.feed_thumbnail);
			avatar = itemView.findViewById(R.id.feed_avatar);
			duration = itemView.findViewById(R.id.feed_duration);
			title = itemView.findViewById(R.id.feed_title);
			meta = itemView.findViewById(R.id.feed_meta);
			more = itemView.findViewById(R.id.feed_more);
		}
	}

	private static final class EntityHolder extends RecyclerView.ViewHolder {
		@NonNull
		final ImageView thumbnail;
		@NonNull
		final TextView title;
		@NonNull
		final TextView meta;

		EntityHolder(@NonNull View itemView) {
			super(itemView);
			thumbnail = itemView.findViewById(R.id.entity_thumbnail);
			title = itemView.findViewById(R.id.entity_title);
			meta = itemView.findViewById(R.id.entity_meta);
		}
	}
}
