package com.testtube.app.ui.library;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.testtube.app.R;
import com.testtube.app.downloader.ui.DownloadsList;
import com.testtube.app.player.queue.QueueItem;
import com.testtube.app.playlist.LocalPlaylists;
import com.testtube.app.playlist.PlaylistDialogs;
import com.testtube.app.ui.Dialogs;
import com.testtube.app.ui.queue.QueueAdapter;
import com.testtube.app.ui.queue.QueueTouch;
import com.testtube.app.util.ImageUtils;
import com.testtube.app.util.ToastUtils;
import com.testtube.app.util.ViewUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

/**
 * Library > Playlists. The list of playlists (Watch later first, New playlist on top), and one
 * opened in place with Play all, Shuffle and rows that work like the queue's: tap plays, the cross
 * removes, long press drags.
 */
final class PlaylistsPage implements LocalPlaylists.Listener {
	/** What the page needs from the Library. */
	interface Actions {
		/** Plays a watch link, in shuffle mode or in order. */
		void play(@NonNull String url, boolean shuffle);

		boolean isWatched(@NonNull String videoId);

		/** Opened or closed a playlist, or its content changed. */
		void onStateChanged();
	}

	@NonNull
	private final Context context;
	@NonNull
	private final LocalPlaylists store;
	@NonNull
	private final Actions actions;
	@NonNull
	private final RecyclerView list;
	@NonNull
	private final View detail;
	@NonNull
	private final TextView title;
	@NonNull
	private final TextView count;
	@NonNull
	private final View playAll;
	@NonNull
	private final View shuffle;
	@NonNull
	private final RecyclerView items;
	@NonNull
	private final TextView empty;
	@NonNull
	private final PlaylistsAdapter playlistsAdapter;
	@NonNull
	private final QueueAdapter itemsAdapter;
	@Nullable
	private String openId;
	@Nullable
	private String playingId;

	PlaylistsPage(@NonNull View root, @NonNull Actions actions) {
		this.context = root.getContext();
		this.store = LocalPlaylists.of(context);
		this.actions = actions;
		list = root.findViewById(R.id.playlists_list);
		detail = root.findViewById(R.id.playlist_detail);
		title = root.findViewById(R.id.playlist_title);
		count = root.findViewById(R.id.playlist_count);
		playAll = root.findViewById(R.id.playlist_play_all);
		shuffle = root.findViewById(R.id.playlist_shuffle);
		items = root.findViewById(R.id.playlist_items);
		empty = root.findViewById(R.id.playlist_empty);
		ViewUtils.sizeCompoundDrawables(empty, DownloadsList.EMPTY_ICON_DP);

		playlistsAdapter = new PlaylistsAdapter();
		list.setLayoutManager(new LinearLayoutManager(context));
		list.setAdapter(playlistsAdapter);

		root.findViewById(R.id.playlist_back).setOnClickListener(v -> back());
		playAll.setOnClickListener(v -> play(false));
		shuffle.setOnClickListener(v -> play(true));
		itemsAdapter = new QueueAdapter(new QueueAdapter.Actions() {
			@Override
			public void onPlayRequested(@NonNull QueueItem item) {
				if (item.getVideoUrl() != null) actions.play(item.getVideoUrl(), false);
			}

			@Override
			public void onDeleteRequested(@NonNull QueueItem item) {
				String id = openId;
				if (id != null && item.getVideoId() != null) store.remove(id, item.getVideoId());
			}
		});
		items.setLayoutManager(new LinearLayoutManager(context));
		items.setAdapter(itemsAdapter);
		new ItemTouchHelper(new QueueTouch(itemsAdapter::moveItem, new QueueTouch.DragStateCallback() {
			@Override
			public void onDragStateChanged(boolean dragging) {
			}

			@Override
			public void onDragFinished() {
				String id = openId;
				if (id == null) return;
				List<String> order = new ArrayList<>();
				for (QueueItem item : itemsAdapter.snapshotItems()) {
					if (item.getVideoId() != null) order.add(item.getVideoId());
				}
				store.reorder(id, order);
			}
		})).attachToRecyclerView(items);

		store.addListener(this);
		render();
	}

	@Override
	public void onPlaylistsChanged() {
		render();
	}

	/** Watched marks may have changed meanwhile. */
	void refresh() {
		render();
	}

	void setPlayingId(@Nullable String playingId) {
		if (Objects.equals(this.playingId, playingId)) return;
		this.playingId = playingId;
		if (openId != null) render();
	}

	boolean isOpen() {
		return openId != null;
	}

	/** @return true if an open playlist was closed */
	boolean back() {
		if (openId == null) return false;
		openId = null;
		render();
		ViewUtils.fadeIn(list);
		return true;
	}

	/** The open playlist has watched videos to remove. */
	boolean hasWatched() {
		LocalPlaylists.Playlist playlist = openId != null ? store.get(openId) : null;
		if (playlist == null) return false;
		for (LocalPlaylists.Entry entry : playlist.items()) {
			if (actions.isWatched(entry.videoId())) return true;
		}
		return false;
	}

	void confirmRemoveWatched() {
		String id = openId;
		LocalPlaylists.Playlist playlist = id != null ? store.get(id) : null;
		if (playlist == null) return;
		Dialogs.builder(context)
						.setTitle(R.string.playlist_remove_watched)
						.setMessage(context.getString(R.string.playlist_remove_watched_confirm, playlist.name(context)))
						.setNegativeButton(R.string.cancel, null)
						.setPositiveButton(R.string.clear, (d, which) -> {
							Predicate<String> watched = actions::isWatched;
							int removed = store.removeWatched(id, watched);
							ToastUtils.show(context, context.getResources()
											.getQuantityString(R.plurals.playlist_removed_watched, removed, removed));
						})
						.show();
	}

	void release() {
		store.removeListener(this);
	}

	private void open(@NonNull String id) {
		openId = id;
		render();
		items.scrollToPosition(0);
		ViewUtils.fadeIn(detail);
	}

	private void play(boolean shuffled) {
		LocalPlaylists.Playlist playlist = openId != null ? store.get(openId) : null;
		if (playlist == null || playlist.size() == 0) return;
		int index = shuffled ? ThreadLocalRandom.current().nextInt(playlist.size()) : 0;
		actions.play(playlist.urlAt(index), shuffled);
	}

	private void render() {
		LocalPlaylists.Playlist playlist = openId != null ? store.get(openId) : null;
		// deleted from its own row menu or by an import: back to the list
		if (openId != null && playlist == null) openId = null;
		list.setVisibility(playlist == null ? View.VISIBLE : View.GONE);
		detail.setVisibility(playlist == null ? View.GONE : View.VISIBLE);
		if (playlist == null) {
			playlistsAdapter.submit(store.all());
			itemsAdapter.replaceItems(List.of(), null);
		} else {
			title.setText(playlist.name(context));
			count.setText(countText(context, playlist.size()));
			List<LocalPlaylists.Entry> entries = playlist.items();
			List<QueueItem> rows = new ArrayList<>(entries.size());
			for (int i = 0; i < entries.size(); i++) {
				LocalPlaylists.Entry entry = entries.get(i);
				rows.add(new QueueItem(entry.videoId(), playlist.urlAt(i), entry.title(), entry.author(), entry.thumbnailUrl()));
			}
			itemsAdapter.replaceItems(rows, playingId);
			boolean hasItems = !rows.isEmpty();
			items.setVisibility(hasItems ? View.VISIBLE : View.GONE);
			empty.setVisibility(hasItems ? View.GONE : View.VISIBLE);
			playAll.setEnabled(hasItems);
			shuffle.setEnabled(rows.size() > 1);
		}
		actions.onStateChanged();
	}

	@NonNull
	private static String countText(@NonNull Context context, int size) {
		return context.getResources().getQuantityString(R.plurals.playlist_count, size, size);
	}

	/** New playlist on top, then the playlists. */
	private final class PlaylistsAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
		private static final int TYPE_NEW = 0;
		private static final int TYPE_PLAYLIST = 1;
		@NonNull
		private List<LocalPlaylists.Playlist> data = new ArrayList<>();

		@SuppressLint("NotifyDataSetChanged")
		void submit(@NonNull List<LocalPlaylists.Playlist> playlists) {
			data = playlists;
			// a few dozen rows at most, and any change can rename, reorder or recount them
			notifyDataSetChanged();
		}

		@Override
		public int getItemViewType(int position) {
			return position == 0 ? TYPE_NEW : TYPE_PLAYLIST;
		}

		@Override
		public int getItemCount() {
			return data.size() + 1;
		}

		@NonNull
		@Override
		public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
			LayoutInflater inflater = LayoutInflater.from(parent.getContext());
			if (viewType == TYPE_NEW) {
				TextView row = (TextView) inflater.inflate(R.layout.item_playlist_new, parent, false);
				ViewUtils.sizeCompoundDrawables(row, 24f);
				row.setOnClickListener(v -> PlaylistDialogs.askName(context, R.string.playlist_new, null, store::create));
				return new RecyclerView.ViewHolder(row) {
				};
			}
			return new PlaylistHolder(inflater.inflate(R.layout.item_queue_entry, parent, false));
		}

		@Override
		public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
			if (holder instanceof PlaylistHolder row) row.bind(data.get(position - 1));
		}
	}

	private final class PlaylistHolder extends RecyclerView.ViewHolder {
		@NonNull
		private final ImageView thumbnail;
		@NonNull
		private final TextView name;
		@NonNull
		private final TextView size;
		@NonNull
		private final ImageButton more;

		PlaylistHolder(@NonNull View itemView) {
			super(itemView);
			thumbnail = itemView.findViewById(R.id.queue_item_thumbnail);
			name = itemView.findViewById(R.id.queue_item_title);
			size = itemView.findViewById(R.id.queue_item_author);
			more = itemView.findViewById(R.id.queue_item_delete);
			more.setImageResource(R.drawable.ic_more);
			more.setContentDescription(context.getString(R.string.playlist_options));
		}

		void bind(@NonNull LocalPlaylists.Playlist playlist) {
			name.setText(playlist.name(context));
			size.setText(countText(context, playlist.size()));
			ImageUtils.loadThumb(thumbnail, playlist.firstThumbnailUrl());
			itemView.setOnClickListener(v -> open(playlist.id()));
			// Watch later can't be renamed or deleted
			more.setVisibility(playlist.isWatchLater() ? View.INVISIBLE : View.VISIBLE);
			more.setOnClickListener(v -> PlaylistDialogs.options(context, playlist));
		}
	}
}
