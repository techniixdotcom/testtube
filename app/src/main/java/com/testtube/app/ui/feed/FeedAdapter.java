package com.testtube.app.ui.feed;

import android.content.Context;
import android.icu.text.CompactDecimalFormat;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.squareup.picasso.Picasso;
import com.testtube.app.R;
import com.testtube.app.extractor.FeedItem;
import com.testtube.app.filter.ContentFilters;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.ColorMatrix;

/**
 * Rows of the native Home, Subscriptions and Search screens.
 */
final class FeedAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
	private static final int TYPE_VIDEO = 0;
	private static final int TYPE_ENTITY = 1;
	private static final float WATCHED_ALPHA = 0.3f;
	private static final ColorMatrixColorFilter GREY = greyFilter();

	// Watched videos lose their colour and most of their brightness, so they stand out at a glance.
	@NonNull
	private static ColorMatrixColorFilter greyFilter() {
		ColorMatrix matrix = new ColorMatrix();
		matrix.setSaturation(0f);
		return new ColorMatrixColorFilter(matrix);
	}
	private static final String SEPARATOR = " • ";

	interface Listener {
		void onOpen(@NonNull FeedItem item);

		void onOpenAuthor(@NonNull FeedItem item);

		void onMenu(@NonNull FeedItem item);
	}

	@NonNull
	private final ContentFilters filters;
	@NonNull
	private final Listener listener;
	@NonNull
	private final Map<String, Long> ids = new HashMap<>();
	@NonNull
	private List<FeedItem> items = new ArrayList<>();
	@Nullable
	private CompactDecimalFormat compact;
	@Nullable
	private Locale compactLocale;

	FeedAdapter(@NonNull ContentFilters filters, @NonNull Listener listener) {
		this.filters = filters;
		this.listener = listener;
		setHasStableIds(true);
	}

	/**
	 * Shows a new list. Appending to the current list only inserts the new rows.
	 */
	void submit(@NonNull List<FeedItem> next) {
		List<FeedItem> previous = items;
		items = next;
		boolean appended = next.size() >= previous.size();
		for (int i = 0; appended && i < previous.size(); i++) {
			if (!previous.get(i).url().equals(next.get(i).url())) appended = false;
		}
		if (appended && next.size() > previous.size()) {
			notifyItemRangeInserted(previous.size(), next.size() - previous.size());
		} else if (!appended) {
			notifyDataSetChanged();
		}
	}

	/**
	 * The video shown in this row, or null for other rows (channels, playlists, the header).
	 */
	@Nullable
	FeedItem itemAt(@NonNull RecyclerView.ViewHolder holder) {
		if (!(holder instanceof VideoHolder) || holder.getBindingAdapter() != this) return null;
		int position = holder.getBindingAdapterPosition();
		return position >= 0 && position < items.size() ? items.get(position) : null;
	}

	void refreshStates() {
		notifyItemRangeChanged(0, items.size());
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
		return items.get(position).kind() == FeedItem.Kind.VIDEO ? TYPE_VIDEO : TYPE_ENTITY;
	}

	@NonNull
	@Override
	public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
		LayoutInflater inflater = LayoutInflater.from(parent.getContext());
		if (viewType == TYPE_VIDEO) {
			return new VideoHolder(inflater.inflate(R.layout.item_feed_video, parent, false));
		}
		return new EntityHolder(inflater.inflate(R.layout.item_feed_entity, parent, false));
	}

	@Override
	public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
		FeedItem item = items.get(position);
		if (holder instanceof VideoHolder video) {
			bindVideo(video, item);
		} else if (holder instanceof EntityHolder entity) {
			bindEntity(entity, item);
		}
	}

	@Override
	public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
		if (holder instanceof VideoHolder video) Picasso.get().cancelRequest(video.thumbnail);
		if (holder instanceof EntityHolder entity) Picasso.get().cancelRequest(entity.thumbnail);
	}

	private void bindVideo(@NonNull VideoHolder holder, @NonNull FeedItem item) {
		Context context = holder.itemView.getContext();
		holder.title.setText(item.title());
		List<String> meta = new ArrayList<>(3);
		if (item.author() != null) meta.add(item.author());
		if (!item.live() && item.count() >= 0) {
			meta.add(context.getString(R.string.feed_views, compact(item.count())));
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
		} else {
			holder.duration.setVisibility(View.GONE);
		}
		boolean watched = filters.isWatched(item.videoId());
		holder.itemView.setAlpha(watched ? WATCHED_ALPHA : 1f);
		holder.thumbnail.setColorFilter(watched ? GREY : null);
		loadImage(holder.thumbnail, item.thumbnailUrl(), R.drawable.thumb_clear);
		holder.itemView.setOnClickListener(v -> listener.onOpen(item));
		holder.itemView.setOnLongClickListener(v -> {
			listener.onMenu(item);
			return true;
		});
		holder.more.setOnClickListener(v -> listener.onMenu(item));
		if (item.authorUrl() != null) {
			holder.meta.setOnClickListener(v -> listener.onOpenAuthor(item));
		} else {
			holder.meta.setOnClickListener(null);
			holder.meta.setClickable(false);
		}
	}

	private void bindEntity(@NonNull EntityHolder holder, @NonNull FeedItem item) {
		Context context = holder.itemView.getContext();
		holder.title.setText(item.title());
		List<String> meta = new ArrayList<>(2);
		if (item.kind() == FeedItem.Kind.CHANNEL) {
			meta.add(context.getString(R.string.feed_channel));
			if (item.count() >= 0) meta.add(context.getString(R.string.feed_subscribers, compact(item.count())));
		} else {
			meta.add(item.author() != null ? item.author() : context.getString(R.string.type_playlist));
			if (item.count() >= 0) meta.add(context.getString(R.string.feed_videos, compact(item.count())));
		}
		holder.meta.setText(String.join(SEPARATOR, meta));
		loadImage(holder.thumbnail, item.thumbnailUrl(), R.drawable.bg_thumbnail_placeholder);
		holder.itemView.setOnClickListener(v -> listener.onOpen(item));
	}

	private static void loadImage(@NonNull ImageView view, @Nullable String url, int placeholder) {
		if (url == null || url.isBlank()) {
			Picasso.get().cancelRequest(view);
			view.setImageResource(placeholder);
			return;
		}
		Picasso.get()
						.load(url)
						.fit()
						.centerCrop()
						.placeholder(placeholder)
						.error(placeholder)
						.into(view);
	}

	@NonNull
	private String compact(long value) {
		Locale locale = Locale.getDefault();
		if (compact == null || !locale.equals(compactLocale)) {
			compact = CompactDecimalFormat.getInstance(locale, CompactDecimalFormat.CompactStyle.SHORT);
			compactLocale = locale;
		}
		return compact.format(value);
	}

	private static final class VideoHolder extends RecyclerView.ViewHolder {
		@NonNull
		final ImageView thumbnail;
		@NonNull
		final TextView duration;
		@NonNull
		final TextView title;
		@NonNull
		final TextView meta;
		@NonNull
		final ImageButton more;

		VideoHolder(@NonNull View itemView) {
			super(itemView);
			thumbnail = itemView.findViewById(R.id.feed_thumbnail);
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
