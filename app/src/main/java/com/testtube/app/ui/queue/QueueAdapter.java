package com.testtube.app.ui.queue;

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

import com.testtube.app.R;
import com.testtube.app.player.queue.QueueItem;
import com.testtube.app.util.ImageUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class QueueAdapter extends RecyclerView.Adapter<QueueAdapter.ViewHolder> {
	@NonNull
	private final List<QueueItem> items = new ArrayList<>();
	@NonNull
	private final Actions actions;
	@Nullable
	private String playingId;

	public QueueAdapter(@NonNull Actions actions) {
		this.actions = actions;
	}

	/**
	 * Shows a new queue state. Only rows that actually changed are rebound, so the frequent
	 * player updates (minimize, play/pause) don't reload every thumbnail.
	 */
	public void replaceItems(@NonNull List<QueueItem> newItems, @Nullable String playingId) {
		List<QueueItem> oldItems = new ArrayList<>(items);
		String oldPlayingId = this.playingId;
		List<QueueItem> fresh = new ArrayList<>(newItems.size());
		for (QueueItem item : newItems) {
			fresh.add(item.copy());
		}
		DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
			@Override
			public int getOldListSize() {
				return oldItems.size();
			}

			@Override
			public int getNewListSize() {
				return fresh.size();
			}

			@Override
			public boolean areItemsTheSame(int oldPosition, int newPosition) {
				return Objects.equals(oldItems.get(oldPosition).getVideoId(), fresh.get(newPosition).getVideoId());
			}

			@Override
			public boolean areContentsTheSame(int oldPosition, int newPosition) {
				QueueItem before = oldItems.get(oldPosition);
				QueueItem after = fresh.get(newPosition);
				return Objects.equals(before.getTitle(), after.getTitle())
								&& Objects.equals(before.getAuthor(), after.getAuthor())
								&& Objects.equals(before.getThumbnailUrl(), after.getThumbnailUrl())
								&& Objects.equals(before.getVideoUrl(), after.getVideoUrl())
								&& isPlaying(before, oldPlayingId) == isPlaying(after, playingId);
			}
		});
		items.clear();
		items.addAll(fresh);
		this.playingId = playingId;
		notifySafe(() -> diff.dispatchUpdatesTo(this));
	}

	private static boolean isPlaying(@NonNull QueueItem item, @Nullable String playingId) {
		return item.getVideoId() != null && item.getVideoId().equals(playingId);
	}

	public boolean moveItem(int from, int to) {
		if (from < 0 || from >= items.size() || to < 0 || to >= items.size() || from == to) {
			return false;
		}
		QueueItem moved = items.remove(from);
		items.add(to, moved);
		notifySafe(() -> notifyItemMoved(from, to));
		return true;
	}

	@NonNull
	public List<QueueItem> snapshotItems() {
		List<QueueItem> snapshot = new ArrayList<>(items.size());
		for (QueueItem item : items) {
			snapshot.add(item.copy());
		}
		return snapshot;
	}

	@NonNull
	@Override
	public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
		View itemView = LayoutInflater.from(parent.getContext())
						.inflate(R.layout.item_queue_entry, parent, false);
		return new ViewHolder(itemView);
	}

	@Override
	public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
		holder.bind(items.get(position), playingId, actions);
	}

	@Override
	public int getItemCount() {
		return items.size();
	}

	private void notifySafe(@NonNull Runnable task) {
		try {
			task.run();
		} catch (NullPointerException ignored) {
			// The JVM unit-test stub for RecyclerView.Adapter has no observer list until attached.
		}
	}

	public interface Actions {
		void onPlayRequested(@NonNull QueueItem item);

		void onDeleteRequested(@NonNull QueueItem item);
	}

	static final class ViewHolder extends RecyclerView.ViewHolder {
		@NonNull
		private final ImageView thumbnailView;
		@NonNull
		private final TextView titleView;
		@NonNull
		private final TextView authorView;
		@NonNull
		private final TextView playingBadgeView;
		@NonNull
		private final ImageButton deleteButton;

		ViewHolder(@NonNull View itemView) {
			super(itemView);
			thumbnailView = itemView.findViewById(R.id.queue_item_thumbnail);
			titleView = itemView.findViewById(R.id.queue_item_title);
			authorView = itemView.findViewById(R.id.queue_item_author);
			playingBadgeView = itemView.findViewById(R.id.queue_item_playing_badge);
			deleteButton = itemView.findViewById(R.id.queue_item_delete);
		}

		void bind(@NonNull QueueItem item,
		          @Nullable String playingId,
		          @NonNull Actions actions) {
			titleView.setText(item.getTitle() == null || item.getTitle().isBlank()
							? item.getVideoUrl()
							: item.getTitle());
			authorView.setText(item.getAuthor() == null || item.getAuthor().isBlank()
							? itemView.getContext().getString(R.string.queue_unknown_author)
							: item.getAuthor());
			ImageUtils.loadThumb(thumbnailView, item.getThumbnailUrl());
			boolean playing = isPlaying(item, playingId);
			itemView.setActivated(playing);
			itemView.setAlpha(1.0f);
			playingBadgeView.setVisibility(playing ? View.VISIBLE : View.GONE);
			itemView.setOnClickListener(v -> actions.onPlayRequested(item.copy()));
			deleteButton.setOnClickListener(v -> actions.onDeleteRequested(item.copy()));
		}
	}
}
