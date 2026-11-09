package com.testtube.app.ui.library;

import android.transition.ChangeBounds;
import android.transition.TransitionManager;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.util.UnstableApi;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.tencent.mmkv.MMKV;
import com.testtube.app.Constant;
import com.testtube.app.R;
import com.testtube.app.downloader.ui.DownloadsList;
import com.testtube.app.history.LocalHistoryController;
import com.testtube.app.history.WatchHistory;
import com.testtube.app.player.queue.QueueItem;
import com.testtube.app.ui.Dialogs;
import com.testtube.app.ui.queue.QueueAdapter;
import com.testtube.app.ui.queue.QueueTouch;
import com.testtube.app.util.ViewUtils;

import java.util.List;

/**
 * Library tab: Queue, Playlists, History and Downloads as tabs along the top, only the open one
 * labelled so four fit. The button at the end acts on the open tab. Remembers the last tab.
 */
@UnstableApi
public final class LibraryScreen {
	/** Saved by ordinal, so new pages go at the end whatever their place on screen. */
	public enum Page {QUEUE, HISTORY, DOWNLOADS, PLAYLISTS}

	/** What the Library needs from the activity. */
	public interface Host {
		void openVideo(@NonNull String url);

		/** Plays a playlist's watch link, shuffled or in order. */
		void playPlaylist(@NonNull String url, boolean shuffle);

		boolean isWatched(@NonNull String videoId);

		void importPlaylists();

		void exportPlaylists();

		void removeQueueItem(@NonNull String videoId);

		void moveQueue(@NonNull List<QueueItem> order);

		void clearQueue();
	}

	private static final String KEY_PAGE = "library_page";
	/** Width share of the open tab, the others only show their icon. */
	private static final float OPEN_TAB_WEIGHT = 3f;
	private static final float DISABLED_ALPHA = 0.38f;
	private static final Page[] PAGES = Page.values();

	@NonNull
	private final AppCompatActivity activity;
	@NonNull
	private final Host host;
	@NonNull
	private final ViewGroup tabRow;
	@NonNull
	private final ViewGroup[] tabs;
	@NonNull
	private final View[] pages;
	@NonNull
	private final ImageButton action;
	@NonNull
	private final LocalHistoryController history;
	@NonNull
	private final PlaylistsPage playlists;
	@NonNull
	private final QueueAdapter queueAdapter;
	@NonNull
	private final RecyclerView queueList;
	@NonNull
	private final TextView queueEmpty;
	@Nullable
	private DownloadsList downloads;
	@NonNull
	private Page page;
	private boolean visible;
	private boolean queueIsEmpty = true;

	public LibraryScreen(@NonNull AppCompatActivity activity,
	                     @NonNull View root,
	                     @NonNull WatchHistory watchHistory,
	                     @NonNull Host host) {
		this.activity = activity;
		this.host = host;
		tabs = new ViewGroup[]{
						root.findViewById(R.id.library_tab_queue),
						root.findViewById(R.id.library_tab_history),
						root.findViewById(R.id.library_tab_downloads),
						root.findViewById(R.id.library_tab_playlists)};
		pages = new View[]{
						root.findViewById(R.id.library_page_queue),
						root.findViewById(R.id.library_page_history),
						root.findViewById(R.id.library_page_downloads),
						root.findViewById(R.id.library_page_playlists)};
		tabRow = (ViewGroup) tabs[0].getParent();
		action = root.findViewById(R.id.library_clear);
		action.setOnClickListener(v -> onAction());
		for (Page each : PAGES) {
			tabs[each.ordinal()].setOnClickListener(v -> select(each));
		}
		ViewUtils.sizeCompoundDrawables(root.findViewById(R.id.history_empty), DownloadsList.EMPTY_ICON_DP);

		history = new LocalHistoryController(pages[Page.HISTORY.ordinal()], watchHistory,
						videoId -> host.openVideo(Constant.HOME_URL + "/watch?v=" + videoId), this::updateAction);
		playlists = new PlaylistsPage(pages[Page.PLAYLISTS.ordinal()], new PlaylistsPage.Actions() {
			@Override
			public void play(@NonNull String url, boolean shuffle) {
				host.playPlaylist(url, shuffle);
			}

			@Override
			public boolean isWatched(@NonNull String videoId) {
				return host.isWatched(videoId);
			}

			@Override
			public void onStateChanged() {
				if (page == Page.PLAYLISTS) updateAction();
			}
		});

		queueEmpty = root.findViewById(R.id.panel_queue_empty);
		ViewUtils.sizeCompoundDrawables(queueEmpty, DownloadsList.EMPTY_ICON_DP);
		queueList = root.findViewById(R.id.panel_queue_list);
		queueAdapter = new QueueAdapter(new QueueAdapter.Actions() {
			@Override
			public void onPlayRequested(@NonNull QueueItem item) {
				if (item.getVideoUrl() != null) host.openVideo(item.getVideoUrl());
			}

			@Override
			public void onDeleteRequested(@NonNull QueueItem item) {
				if (item.getVideoId() != null) host.removeQueueItem(item.getVideoId());
			}
		});
		queueList.setLayoutManager(new LinearLayoutManager(activity));
		queueList.setAdapter(queueAdapter);
		new ItemTouchHelper(new QueueTouch(queueAdapter::moveItem, new QueueTouch.DragStateCallback() {
			@Override
			public void onDragStateChanged(boolean dragging) {
			}

			@Override
			public void onDragFinished() {
				host.moveQueue(queueAdapter.snapshotItems());
			}
		})).attachToRecyclerView(queueList);

		int saved = MMKV.defaultMMKV().decodeInt(KEY_PAGE, Page.HISTORY.ordinal());
		page = PAGES[saved >= 0 && saved < PAGES.length ? saved : Page.HISTORY.ordinal()];
		showPage(false);
	}

	/**
	 * Library shown again or left. Showing refreshes the open tab (things got watched meanwhile);
	 * only a visible Library follows downloads.
	 */
	public void setVisible(boolean visible) {
		this.visible = visible;
		if (visible) {
			refreshPage();
		} else if (downloads != null) {
			downloads.stop();
		}
	}

	public void select(@NonNull Page page) {
		if (this.page == page) return;
		this.page = page;
		MMKV.defaultMMKV().encode(KEY_PAGE, page.ordinal());
		showPage(true);
	}

	public void renderQueue(@NonNull List<QueueItem> items, @Nullable String playingId) {
		queueAdapter.replaceItems(items, playingId);
		playlists.setPlayingId(playingId);
		queueIsEmpty = items.isEmpty();
		queueEmpty.setVisibility(queueIsEmpty ? View.VISIBLE : View.GONE);
		queueList.setVisibility(queueIsEmpty ? View.GONE : View.VISIBLE);
		if (page == Page.QUEUE) updateAction();
	}

	/** @return true if Back closed an open playlist */
	public boolean back() {
		return page == Page.PLAYLISTS && playlists.back();
	}

	public void release() {
		playlists.release();
		if (downloads != null) downloads.release();
	}

	private void showPage(boolean animate) {
		if (animate) TransitionManager.beginDelayedTransition(tabRow, new ChangeBounds().setDuration(150));
		for (Page each : PAGES) {
			boolean selected = each == page;
			ViewGroup tab = tabs[each.ordinal()];
			tab.setSelected(selected);
			// four labels don't fit a phone, so only the open tab has one
			tab.getChildAt(1).setVisibility(selected ? View.VISIBLE : View.GONE);
			LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) tab.getLayoutParams();
			params.weight = selected ? OPEN_TAB_WEIGHT : 1f;
			tab.setLayoutParams(params);
			View view = pages[each.ordinal()];
			view.setVisibility(selected ? View.VISIBLE : View.GONE);
			if (selected && animate) ViewUtils.fadeIn(view);
		}
		refreshPage();
	}

	private void refreshPage() {
		if (page != Page.DOWNLOADS && downloads != null) downloads.stop();
		if (visible) {
			switch (page) {
				case HISTORY -> history.reload();
				case DOWNLOADS -> {
					if (downloads == null) {
						downloads = new DownloadsList(activity, pages[Page.DOWNLOADS.ordinal()], null, this::updateAction);
					}
					downloads.start();
				}
				case PLAYLISTS -> playlists.refresh();
				case QUEUE -> {
				}
			}
		}
		updateAction();
	}

	/** Bin for the open tab; on Playlists import/export, or removing watched videos in an open one. */
	private void updateAction() {
		boolean playlistMenu = page == Page.PLAYLISTS && !playlists.isOpen();
		action.setImageResource(playlistMenu ? R.drawable.ic_more : R.drawable.ic_delete);
		boolean enabled = switch (page) {
			case QUEUE -> !queueIsEmpty;
			case HISTORY -> !history.isEmpty();
			case DOWNLOADS -> downloads != null && !downloads.isEmpty();
			case PLAYLISTS -> playlistMenu || playlists.hasWatched();
		};
		action.setEnabled(enabled);
		action.setAlpha(enabled ? 1f : DISABLED_ALPHA);
		action.setContentDescription(activity.getString(switch (page) {
			case QUEUE -> R.string.clear_queue;
			case HISTORY -> R.string.watch_history_clear;
			case DOWNLOADS -> R.string.downloads_clear;
			case PLAYLISTS -> playlistMenu ? R.string.playlist_import_export : R.string.playlist_remove_watched;
		}));
	}

	private void onAction() {
		switch (page) {
			case QUEUE -> Dialogs.builder(activity)
							.setTitle(R.string.clear_queue)
							.setMessage(R.string.clear_queue_confirmation)
							.setNegativeButton(R.string.cancel, null)
							.setPositiveButton(R.string.clear, (d, which) -> host.clearQueue())
							.show();
			case HISTORY -> history.confirmClear();
			case DOWNLOADS -> {
				if (downloads != null) downloads.confirmClear();
			}
			case PLAYLISTS -> {
				if (playlists.isOpen()) {
					playlists.confirmRemoveWatched();
				} else {
					Dialogs.builder(activity)
									.setTitle(R.string.playlist_import_export)
									.setItems(new String[]{activity.getString(R.string.playlist_import),
													activity.getString(R.string.playlist_export)}, (d, which) -> {
										if (which == 0) host.importPlaylists();
										else host.exportPlaylists();
									})
									.show();
				}
			}
		}
	}
}
