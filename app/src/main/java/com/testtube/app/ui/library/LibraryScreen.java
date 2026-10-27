package com.testtube.app.ui.library;

import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.util.UnstableApi;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.tencent.mmkv.MMKV;
import com.testtube.app.Constant;
import com.testtube.app.R;
import com.testtube.app.downloader.ui.DownloadsList;
import com.testtube.app.history.LocalHistoryController;
import com.testtube.app.history.WatchHistory;
import com.testtube.app.player.queue.QueueItem;
import com.testtube.app.ui.queue.QueueAdapter;
import com.testtube.app.ui.queue.QueueTouch;
import com.testtube.app.util.ViewUtils;

import java.util.List;

/**
 * The Library tab: Queue, History and Downloads as browser-style tabs along the top, with one bin
 * that clears whichever tab is open. The last open tab is remembered.
 */
@UnstableApi
public final class LibraryScreen {
	public enum Page {QUEUE, HISTORY, DOWNLOADS}

	/** What the Library asks of the activity. */
	public interface Host {
		void openVideo(@NonNull String url);

		void removeQueueItem(@NonNull String videoId);

		void moveQueue(@NonNull List<QueueItem> order);

		void clearQueue();
	}

	private static final String KEY_PAGE = "library_page";
	private static final float TAB_ICON_DP = 18f;
	private static final float DISABLED_ALPHA = 0.38f;
	private static final Page[] PAGES = Page.values();

	@NonNull
	private final AppCompatActivity activity;
	@NonNull
	private final Host host;
	@NonNull
	private final TextView[] tabs;
	@NonNull
	private final View[] pages;
	@NonNull
	private final ImageButton clear;
	@NonNull
	private final LocalHistoryController history;
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
		tabs = new TextView[]{
						root.findViewById(R.id.library_tab_queue),
						root.findViewById(R.id.library_tab_history),
						root.findViewById(R.id.library_tab_downloads)};
		pages = new View[]{
						root.findViewById(R.id.library_page_queue),
						root.findViewById(R.id.library_page_history),
						root.findViewById(R.id.library_page_downloads)};
		clear = root.findViewById(R.id.library_clear);
		clear.setOnClickListener(v -> confirmClear());
		for (Page each : PAGES) {
			TextView tab = tabs[each.ordinal()];
			ViewUtils.sizeCompoundDrawables(tab, TAB_ICON_DP);
			tab.setOnClickListener(v -> select(each));
		}
		ViewUtils.sizeCompoundDrawables(root.findViewById(R.id.history_empty), DownloadsList.EMPTY_ICON_DP);

		history = new LocalHistoryController(pages[Page.HISTORY.ordinal()], watchHistory,
						videoId -> host.openVideo(Constant.HOME_URL + "/watch?v=" + videoId), this::updateClear);

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
	 * The Library was shown (again) or left. Showing it refreshes the open tab, since videos may
	 * have been watched meanwhile; only a visible Library follows downloads.
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
		queueIsEmpty = items.isEmpty();
		queueEmpty.setVisibility(queueIsEmpty ? View.VISIBLE : View.GONE);
		queueList.setVisibility(queueIsEmpty ? View.GONE : View.VISIBLE);
		if (page == Page.QUEUE) updateClear();
	}

	public void release() {
		if (downloads != null) downloads.release();
	}

	private void showPage(boolean animate) {
		for (Page each : PAGES) {
			boolean selected = each == page;
			tabs[each.ordinal()].setSelected(selected);
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
						downloads = new DownloadsList(activity, pages[Page.DOWNLOADS.ordinal()], null, this::updateClear);
					}
					downloads.start();
				}
				case QUEUE -> {
				}
			}
		}
		updateClear();
	}

	private void updateClear() {
		boolean empty = switch (page) {
			case QUEUE -> queueIsEmpty;
			case HISTORY -> history.isEmpty();
			case DOWNLOADS -> downloads == null || downloads.isEmpty();
		};
		clear.setEnabled(!empty);
		clear.setAlpha(empty ? DISABLED_ALPHA : 1f);
		clear.setContentDescription(activity.getString(switch (page) {
			case QUEUE -> R.string.clear_queue;
			case HISTORY -> R.string.watch_history_clear;
			case DOWNLOADS -> R.string.downloads_clear;
		}));
	}

	private void confirmClear() {
		switch (page) {
			case QUEUE -> new MaterialAlertDialogBuilder(activity)
							.setTitle(R.string.clear_queue)
							.setMessage(R.string.clear_queue_confirmation)
							.setNegativeButton(R.string.cancel, null)
							.setPositiveButton(R.string.clear, (d, which) -> host.clearQueue())
							.show();
			case HISTORY -> history.confirmClear();
			case DOWNLOADS -> {
				if (downloads != null) downloads.confirmClear();
			}
		}
	}
}
