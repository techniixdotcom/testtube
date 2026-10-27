package com.testtube.app.ui.feed;

import android.content.Context;
import android.content.res.ColorStateList;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.text.method.LinkMovementMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.ConcatAdapter;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.tabs.TabLayout;
import com.testtube.app.AppSettings;
import com.testtube.app.Constant;
import com.testtube.app.R;
import com.testtube.app.extractor.FeedItem;
import com.testtube.app.extractor.LocalSubscriptions;
import com.testtube.app.extractor.PageSource;
import com.testtube.app.filter.ContentFilters;
import com.testtube.app.net.Failure;
import com.testtube.app.util.ImageUtils;
import com.testtube.app.util.StringUtils;
import com.testtube.app.util.ToastUtils;

import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Channel and playlist pages. A channel page has a header like YouTube's (banner, picture, handle,
 * counts, follow button) and a tab per list (Videos, Live, Playlists, ...), or with full channel
 * pages switched off, a plain header and the channel's videos. Pages stack up; back returns to the
 * previous page with its tab, list and scroll position.
 */
public final class PageScreen {
	private static final int LOAD_MORE_THRESHOLD = 6;
	private static final int MAX_PAGES = 6;
	private static final int FULL_COLLAPSED_LINES = 2;
	private static final int SIMPLE_COLLAPSED_LINES = 3;
	private static final String SEPARATOR = " • ";
	/** Key of a playlist's only list. */
	private static final String PLAYLIST_LIST = "";

	/**
	 * Notified when the screen appears or disappears.
	 */
	public interface Listener {
		void onPageVisible(boolean visible);
	}

	@NonNull
	private final Handler handler = new Handler(Looper.getMainLooper());
	@NonNull
	private final PageSource source;
	@NonNull
	private final ContentFilters filters;
	@NonNull
	private final NativeBrowser.Host host;
	@NonNull
	private final Listener listener;
	@NonNull
	private final Deque<Entry> stack = new ArrayDeque<>();
	@NonNull
	private final TextView barTitle;
	@NonNull
	private final SwipeRefreshLayout refresh;
	@NonNull
	private final RecyclerView list;
	@NonNull
	private final LinearLayoutManager layoutManager;
	@NonNull
	private final TextView message;
	@NonNull
	private final HeaderAdapter header = new HeaderAdapter();
	@NonNull
	private final FeedAdapter items;
	@NonNull
	private final StatusAdapter status = new StatusAdapter();

	public PageScreen(@NonNull View root,
	                  @NonNull PageSource source,
	                  @NonNull ContentFilters filters,
	                  @NonNull NativeBrowser.Host host,
	                  @NonNull Listener listener) {
		this.source = source;
		this.filters = filters;
		this.host = host;
		this.listener = listener;
		ImageButton back = root.findViewById(R.id.page_back);
		barTitle = root.findViewById(R.id.page_bar_title);
		refresh = root.findViewById(R.id.page_refresh);
		list = root.findViewById(R.id.page_list);
		message = root.findViewById(R.id.page_message);
		items = new FeedAdapter(filters, new FeedAdapter.Listener() {
			@Override
			public void onOpen(@NonNull FeedItem item) {
				if (item.kind() != FeedItem.Kind.VIDEO) {
					host.openPage(item.url());
					return;
				}
				Entry entry = stack.peekLast();
				String playlistId = entry != null && entry.header != null ? entry.header.playlistId() : null;
				if (playlistId != null && item.videoId() != null) {
					int index = entry.state().items.indexOf(item);
					host.openVideo(item.withUrl(Constant.HOME_URL + "/watch?v=" + item.videoId()
									+ "&list=" + playlistId + "&index=" + (Math.max(0, index) + 1)));
				} else {
					host.openVideo(item);
				}
			}

			@Override
			public void onOpenAuthor(@NonNull FeedItem item) {
				Entry entry = stack.peekLast();
				// On a channel page the author is the page itself.
				if (entry != null && entry.kind == PageSource.Kind.CHANNEL) return;
				if (item.authorUrl() != null) host.openPage(item.authorUrl());
			}

			@Override
			public void onFollowAuthor(@NonNull FeedItem item) {
				host.followChannel(item);
			}

			@Override
			public void onMenu(@NonNull FeedItem item) {
				if (item.kind() == FeedItem.Kind.VIDEO) host.showMenu(item);
			}
		});
		layoutManager = new LinearLayoutManager(root.getContext());
		list.setLayoutManager(layoutManager);
		list.setAdapter(new ConcatAdapter(header, items, status));
		list.setItemViewCacheSize(6);
		// Swipe a video right to add it to the queue, as on Home; the channel is already open here
		new ItemTouchHelper(new QueueSwipe(root.getContext(), new QueueSwipe.Target() {
			@Nullable
			@Override
			public FeedItem itemFor(@NonNull RecyclerView.ViewHolder holder) {
				FeedItem item = items.itemAt(holder);
				return item != null && item.kind() == FeedItem.Kind.VIDEO && item.videoId() != null ? item : null;
			}

			@Override
			public void enqueue(@NonNull FeedItem item) {
				host.enqueue(item);
			}

			@Override
			public void follow(@NonNull FeedItem item) {
			}

			@Override
			public boolean canFollow() {
				return false;
			}
		})).attachToRecyclerView(list);
		// The header rebinds when a tab changes; a cross-fade would make it blink
		if (list.getItemAnimator() instanceof SimpleItemAnimator animator) {
			animator.setSupportsChangeAnimations(false);
		}
		list.addOnScrollListener(new RecyclerView.OnScrollListener() {
			@Override
			public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
				if (dy <= 0) return;
				int last = layoutManager.findLastVisibleItemPosition();
				if (last >= header.getItemCount() + items.getItemCount() - LOAD_MORE_THRESHOLD) loadMore();
			}
		});
		back.setOnClickListener(v -> back());
		refresh.setColorSchemeResources(R.color.accent);
		refresh.setProgressBackgroundColorSchemeResource(R.color.surface_container_high);
		refresh.setOnRefreshListener(() -> {
			Entry entry = stack.peekLast();
			if (entry != null) loadPage(entry);
			else refresh.setRefreshing(false);
		});
		message.setOnClickListener(v -> {
			Entry entry = stack.peekLast();
			if (entry != null && entry.header == null && entry.pageError) loadPage(entry);
		});
	}

	public boolean isVisible() {
		return !stack.isEmpty();
	}

	/**
	 * Opens a channel or playlist on top of the current page.
	 */
	public void open(@NonNull PageSource.Kind kind, @NonNull String url) {
		Entry current = stack.peekLast();
		if (current != null && current.url.equals(url)) {
			listener.onPageVisible(true);
			return;
		}
		saveScroll();
		Entry entry = new Entry(kind, url);
		stack.offerLast(entry);
		while (stack.size() > MAX_PAGES) {
			Entry dropped = stack.pollFirst();
			if (dropped != null) dropped.cancel();
		}
		render(entry, true);
		loadPage(entry);
		listener.onPageVisible(true);
	}

	/**
	 * @return true when a page was closed
	 */
	public boolean back() {
		Entry top = stack.pollLast();
		if (top == null) return false;
		top.cancel();
		Entry previous = stack.peekLast();
		if (previous != null) {
			render(previous, false);
		} else {
			listener.onPageVisible(false);
		}
		return true;
	}

	public void clear() {
		if (stack.isEmpty()) return;
		for (Entry entry : stack) entry.cancel();
		stack.clear();
		items.submit(new ArrayList<>());
		status.show(Status.NONE);
		refresh.setRefreshing(false);
		listener.onPageVisible(false);
	}

	public void onFiltersChanged() {
		Entry entry = stack.peekLast();
		if (entry != null) renderList(entry);
		items.refreshStates();
	}

	/** A channel was followed or unfollowed somewhere else. */
	public void onFollowChanged() {
		if (!stack.isEmpty()) header.notifyItemChanged(0);
	}

	private void saveScroll() {
		Entry current = stack.peekLast();
		if (current != null) current.scroll = layoutManager.onSaveInstanceState();
	}

	private void render(@NonNull Entry entry, boolean top) {
		barTitle.setText(entry.header != null ? entry.header.title() : null);
		items.setCompactRows(entry.kind == PageSource.Kind.CHANNEL);
		header.notifyItemChanged(0);
		renderList(entry);
		refresh.setRefreshing(entry.pageCall != null);
		if (top) {
			list.scrollToPosition(0);
		} else if (entry.scroll != null) {
			// Back on this page: restore the old position once, later loads keep the current one
			layoutManager.onRestoreInstanceState(entry.scroll);
			entry.scroll = null;
		}
		if (entry.header == null && entry.pageError && entry.pageCall == null) {
			message.setText(entry.pageFailure.feedMessage);
			message.setVisibility(View.VISIBLE);
		} else {
			message.setVisibility(View.GONE);
		}
	}

	private void renderList(@NonNull Entry entry) {
		ListState state = entry.state();
		List<FeedItem> shown = visible(entry, state.items);
		items.submit(shown);
		Status next;
		if (entry.header == null) {
			// Page still loading or failed, the refresh spinner or the error message covers it
			next = Status.NONE;
		} else if (state.call != null) {
			next = Status.LOADING;
		} else if (state.error) {
			next = Status.ERROR;
		} else if (state.loaded && shown.isEmpty()) {
			next = Status.EMPTY;
		} else {
			next = Status.NONE;
		}
		status.show(next);
	}

	private void loadPage(@NonNull Entry entry) {
		if (entry.pageCall != null) entry.pageCall.cancel(false);
		// On a reload, stay on the tab that was open
		String keep = entry.header != null ? entry.tab : null;
		CompletableFuture<PageSource.Result> call = source.open(entry.kind, entry.url);
		entry.pageCall = call;
		entry.pageError = false;
		if (entry == stack.peekLast()) {
			refresh.setRefreshing(true);
			message.setVisibility(View.GONE);
		}
		call.whenComplete((result, error) -> handler.post(() -> {
			if (entry.pageCall != call) return;
			entry.pageCall = null;
			if (error != null || result == null || result.header() == null) {
				if (!call.isCancelled() && !Failure.isCancellation(error)) {
					entry.pageError = true;
					entry.pageFailure = Failure.report(error);
					// Refresh failed, the old content stays on screen
					if (entry.header != null && entry == stack.peekLast()) {
						ToastUtils.show(list.getContext(), entry.pageFailure.feedMessage);
					}
				}
			} else {
				for (ListState state : entry.lists.values()) state.cancel();
				entry.lists.clear();
				entry.header = result.header();
				String first = result.tab() != null ? result.tab() : PLAYLIST_LIST;
				ListState state = new ListState();
				state.replace(result.items(), result.next());
				entry.lists.put(first, state);
				entry.tab = first;
				if (keep != null && !keep.equals(first) && tabOf(entry.header, keep) != null) {
					entry.tab = keep;
					loadTab(entry, keep);
				}
			}
			if (entry == stack.peekLast()) render(entry, false);
		}));
	}

	private void loadTab(@NonNull Entry entry, @NonNull String tabId) {
		PageSource.Tab tab = tabOf(entry.header, tabId);
		if (tab == null) return;
		ListState state = entry.lists.computeIfAbsent(tabId, key -> new ListState());
		state.cancel();
		CompletableFuture<PageSource.Result> call = source.openTab(tab);
		state.call = call;
		state.error = false;
		call.whenComplete((result, error) -> handler.post(() -> {
			if (state.call != call) return;
			state.call = null;
			if (error != null || result == null) {
				if (!call.isCancelled()) state.error = true;
			} else {
				state.replace(result.items(), result.next());
			}
			if (entry == stack.peekLast() && tabId.equals(entry.tab)) renderList(entry);
		}));
		if (entry == stack.peekLast() && tabId.equals(entry.tab)) renderList(entry);
	}

	private void loadMore() {
		Entry entry = stack.peekLast();
		if (entry == null || entry.header == null) return;
		String tabId = entry.tab;
		ListState state = entry.state();
		PageSource.Cursor cursor = state.next;
		if (state.call != null || cursor == null || state.error || !state.loaded) return;
		CompletableFuture<PageSource.Result> call = source.more(cursor);
		state.call = call;
		call.whenComplete((result, error) -> handler.post(() -> {
			if (state.call != call) return;
			state.call = null;
			if (error != null || result == null) {
				if (!call.isCancelled()) state.error = true;
			} else {
				state.append(result.items(), result.next());
			}
			if (entry == stack.peekLast() && tabId.equals(entry.tab)) renderList(entry);
		}));
		renderList(entry);
	}

	private void retry() {
		Entry entry = stack.peekLast();
		if (entry == null || entry.header == null) return;
		ListState state = entry.state();
		if (!state.error || state.call != null) return;
		state.error = false;
		if (state.loaded && state.next != null) {
			loadMore();
		} else if (tabOf(entry.header, entry.tab) != null) {
			loadTab(entry, entry.tab);
		} else {
			loadPage(entry);
		}
	}

	private void selectTab(@NonNull Entry entry, @NonNull String tabId) {
		if (tabId.equals(entry.tab) || entry.header == null) return;
		entry.tab = tabId;
		ListState state = entry.state();
		if (state.call == null && (!state.loaded || (state.error && state.items.isEmpty()))) {
			loadTab(entry, tabId);
		} else {
			renderList(entry);
		}
	}

	@Nullable
	private static PageSource.Tab tabOf(@Nullable PageSource.Header header, @NonNull String tabId) {
		if (header == null) return null;
		for (PageSource.Tab tab : header.tabs()) {
			if (tab.id().equals(tabId)) return tab;
		}
		return null;
	}

	private static boolean isVideoTab(@NonNull String tabId) {
		return tabId.equals(ChannelTabs.VIDEOS) || tabId.equals(ChannelTabs.SHORTS)
						|| tabId.equals(ChannelTabs.LIVESTREAMS);
	}

	@StringRes
	private static int tabLabel(@NonNull String tabId) {
		return switch (tabId) {
			case ChannelTabs.SHORTS -> R.string.page_tab_shorts;
			case ChannelTabs.LIVESTREAMS -> R.string.page_tab_live;
			case ChannelTabs.PODCASTS -> R.string.page_tab_podcasts;
			case ChannelTabs.COURSES -> R.string.page_tab_courses;
			case ChannelTabs.ALBUMS -> R.string.page_tab_releases;
			default -> R.string.page_tab_videos;
		};
	}

	/**
	 * The rows to show. A channel's Videos, Shorts and Live tabs show only videos (no playlists in
	 * between), newest first. Podcasts, Courses, Releases and playlist pages keep YouTube's order.
	 */
	@NonNull
	private List<FeedItem> visible(@NonNull Entry entry, @NonNull List<FeedItem> source) {
		boolean channel = entry.kind == PageSource.Kind.CHANNEL && isVideoTab(entry.tab);
		List<FeedItem> out = new ArrayList<>(source.size());
		boolean shorts = AppSettings.showShorts();
		for (FeedItem item : source) {
			if (channel && item.kind() != FeedItem.Kind.VIDEO) continue;
			if (item.shortForm() && !shorts) continue;
			if (item.author() != null && filters.isChannelBlocked(item.author(), item.authorUrl())) continue;
			out.add(item);
		}
		if (channel) FeedItem.sortByUploadDate(out);
		return out;
	}


	private static final class ListState {
		@NonNull
		final List<FeedItem> items = new ArrayList<>();
		@NonNull
		final Set<String> seen = new HashSet<>();
		@Nullable
		PageSource.Cursor next;
		@Nullable
		CompletableFuture<PageSource.Result> call;
		boolean loaded;
		boolean error;

		void replace(@NonNull List<FeedItem> rows, @Nullable PageSource.Cursor cursor) {
			items.clear();
			seen.clear();
			append(rows, cursor);
		}

		void append(@NonNull List<FeedItem> rows, @Nullable PageSource.Cursor cursor) {
			for (FeedItem item : rows) {
				if (seen.add(item.url())) items.add(item);
			}
			next = cursor;
			loaded = true;
			error = false;
		}

		void cancel() {
			CompletableFuture<PageSource.Result> current = call;
			call = null;
			if (current != null) current.cancel(false);
		}
	}

	private static final class Entry {
		@NonNull
		final PageSource.Kind kind;
		@NonNull
		final String url;
		/** Channel page with banner, follow button and tabs; fixed when the page opens. */
		final boolean fullPage;
		@NonNull
		final Map<String, ListState> lists = new HashMap<>();
		@NonNull
		String tab = PLAYLIST_LIST;
		@Nullable
		PageSource.Header header;
		@Nullable
		CompletableFuture<PageSource.Result> pageCall;
		@Nullable
		Parcelable scroll;
		boolean pageError;
		/** Why the page failed to load, for the message. */
		@NonNull
		Failure pageFailure = Failure.OTHER;
		boolean expanded;

		Entry(@NonNull PageSource.Kind kind, @NonNull String url) {
			this.kind = kind;
			this.url = url;
			this.fullPage = kind == PageSource.Kind.CHANNEL && AppSettings.fullChannelPages();
		}

		/** The list of the selected tab. */
		@NonNull
		ListState state() {
			return lists.computeIfAbsent(tab, key -> new ListState());
		}

		void cancel() {
			CompletableFuture<PageSource.Result> current = pageCall;
			pageCall = null;
			if (current != null) current.cancel(false);
			for (ListState state : lists.values()) state.cancel();
		}
	}

	private enum Status {
		NONE,
		LOADING,
		EMPTY,
		ERROR
	}

	private final class StatusAdapter extends RecyclerView.Adapter<StatusHolder> {
		@NonNull
		private Status current = Status.NONE;

		void show(@NonNull Status next) {
			if (next == current) return;
			Status previous = current;
			current = next;
			if (previous == Status.NONE) {
				notifyItemInserted(0);
			} else if (next == Status.NONE) {
				notifyItemRemoved(0);
			} else {
				notifyItemChanged(0);
			}
		}

		@Override
		public int getItemCount() {
			return current == Status.NONE ? 0 : 1;
		}

		@NonNull
		@Override
		public StatusHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
			return new StatusHolder(LayoutInflater.from(parent.getContext())
							.inflate(R.layout.item_page_status, parent, false));
		}

		@Override
		public void onBindViewHolder(@NonNull StatusHolder holder, int position) {
			holder.bind(current);
		}
	}

	private final class StatusHolder extends RecyclerView.ViewHolder {
		@NonNull
		private final ProgressBar progress;
		@NonNull
		private final TextView text;

		StatusHolder(@NonNull View itemView) {
			super(itemView);
			progress = itemView.findViewById(R.id.page_status_progress);
			text = itemView.findViewById(R.id.page_status_text);
		}

		void bind(@NonNull Status status) {
			progress.setVisibility(status == Status.LOADING ? View.VISIBLE : View.GONE);
			text.setVisibility(status == Status.LOADING ? View.GONE : View.VISIBLE);
			text.setText(status == Status.ERROR ? R.string.page_tab_error : R.string.feed_empty);
			if (status == Status.ERROR) {
				text.setOnClickListener(v -> retry());
			} else {
				text.setOnClickListener(null);
				text.setClickable(false);
			}
		}
	}

	private final class HeaderAdapter extends RecyclerView.Adapter<HeaderHolder> {
		private static final int TYPE_FULL = 0;
		private static final int TYPE_SIMPLE = 1;

		@Override
		public int getItemCount() {
			return 1;
		}

		@Override
		public int getItemViewType(int position) {
			Entry entry = stack.peekLast();
			return entry != null && entry.fullPage ? TYPE_FULL : TYPE_SIMPLE;
		}

		@NonNull
		@Override
		public HeaderHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
			int layout = viewType == TYPE_FULL ? R.layout.item_page_header : R.layout.item_page_header_simple;
			return new HeaderHolder(LayoutInflater.from(parent.getContext()).inflate(layout, parent, false));
		}

		@Override
		public void onBindViewHolder(@NonNull HeaderHolder holder, int position) {
			holder.bind(stack.peekLast());
		}

		@Override
		public void onViewRecycled(@NonNull HeaderHolder holder) {
			holder.cancelImages();
		}
	}

	/**
	 * Binds both header layouts. The views that only the full channel page has (banner, handle,
	 * follow button, tabs) are null in the simple one.
	 */
	private final class HeaderHolder extends RecyclerView.ViewHolder {
		@NonNull
		private final ImageView avatar;
		@NonNull
		private final TextView title;
		@NonNull
		private final TextView subtitle;
		@NonNull
		private final TextView description;
		@NonNull
		private final View playAll;
		@Nullable
		private final View bannerFrame;
		@Nullable
		private final ImageView banner;
		@Nullable
		private final TextView handle;
		@Nullable
		private final MaterialButton follow;
		@Nullable
		private final View tabsFrame;
		@Nullable
		private final TabLayout tabs;
		@NonNull
		private List<String> tabIds = new ArrayList<>();
		/** Set while a tab is selected from code, so the listener ignores it. */
		private boolean bindingTabs;
		@Nullable
		private String bannerUrl;
		@Nullable
		private String avatarUrl;

		HeaderHolder(@NonNull View itemView) {
			super(itemView);
			avatar = itemView.findViewById(R.id.page_avatar);
			title = itemView.findViewById(R.id.page_title);
			subtitle = itemView.findViewById(R.id.page_subtitle);
			description = itemView.findViewById(R.id.page_description);
			playAll = itemView.findViewById(R.id.page_play_all);
			bannerFrame = itemView.findViewById(R.id.page_banner_frame);
			banner = itemView.findViewById(R.id.page_banner);
			handle = itemView.findViewById(R.id.page_handle);
			follow = itemView.findViewById(R.id.page_follow);
			tabsFrame = itemView.findViewById(R.id.page_tabs_frame);
			tabs = itemView.findViewById(R.id.page_tabs);
			if (tabs != null) {
				tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
					@Override
					public void onTabSelected(TabLayout.Tab tab) {
						if (bindingTabs) return;
						Entry entry = stack.peekLast();
						if (entry != null && tab.getTag() instanceof String id) selectTab(entry, id);
					}

					@Override
					public void onTabUnselected(TabLayout.Tab tab) {
					}

					@Override
					public void onTabReselected(TabLayout.Tab tab) {
					}
				});
			}
		}

		void bind(@Nullable Entry entry) {
			PageSource.Header h = entry != null ? entry.header : null;
			itemView.setVisibility(h != null ? View.VISIBLE : View.GONE);
			if (entry == null || h == null) {
				cancelImages();
				avatar.setImageResource(R.drawable.bg_avatar_placeholder);
				if (banner != null) banner.setImageDrawable(null);
				return;
			}
			Context context = itemView.getContext();
			title.setText(h.title());
			subtitle.setText(subtitleOf(context, h, entry.fullPage));
			bindAvatar(h);
			bindDescription(entry, h);
			bindPlayAll(entry, h);
			if (!entry.fullPage) return;
			bindVerified(context, h);
			bindHandle(h);
			bindBanner(h);
			bindFollow(h);
			bindTabs(entry, h);
		}

		void cancelImages() {
			ImageUtils.clear(avatar);
			if (banner != null) ImageUtils.clear(banner);
			avatarUrl = null;
			bannerUrl = null;
		}

		@NonNull
		private String subtitleOf(@NonNull Context context, @NonNull PageSource.Header h, boolean full) {
			List<String> parts = new ArrayList<>(2);
			if (h.kind() == PageSource.Kind.CHANNEL) {
				// The full page uses YouTube's own wording, which also has the video count
				if (full && !h.stats().isEmpty()) {
					parts.addAll(h.stats());
				} else if (h.count() >= 0) {
					parts.add(context.getString(R.string.feed_subscribers, StringUtils.compactCount(h.count())));
				}
			} else {
				if (h.subtitle() != null && !h.subtitle().isBlank()) parts.add(h.subtitle());
				if (h.count() >= 0) parts.add(context.getString(R.string.feed_videos, StringUtils.compactCount(h.count())));
			}
			return String.join(SEPARATOR, parts);
		}

		private void bindVerified(@NonNull Context context, @NonNull PageSource.Header h) {
			if (h.verified()) {
				title.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_verified, 0);
				title.setContentDescription(h.title() + ", " + context.getString(R.string.page_verified));
			} else {
				title.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0);
				title.setContentDescription(null);
			}
		}

		private void bindHandle(@NonNull PageSource.Header h) {
			if (handle == null) return;
			boolean hasHandle = h.handle() != null && !h.handle().isBlank();
			handle.setVisibility(hasHandle ? View.VISIBLE : View.GONE);
			handle.setText(hasHandle ? h.handle() : null);
		}

		private void bindAvatar(@NonNull PageSource.Header h) {
			String next = h.avatarUrl() != null && !h.avatarUrl().isBlank() ? h.avatarUrl() : null;
			avatar.setVisibility(next != null ? View.VISIBLE : View.GONE);
			if (next == null) {
				ImageUtils.clear(avatar);
				avatar.setImageResource(R.drawable.bg_avatar_placeholder);
				avatarUrl = null;
			} else if (!next.equals(avatarUrl)) {
				avatarUrl = next;
				ImageUtils.load(avatar, next, R.drawable.bg_avatar_placeholder);
			}
		}

		private void bindBanner(@NonNull PageSource.Header h) {
			if (banner == null || bannerFrame == null) return;
			String next = h.bannerUrl() != null && !h.bannerUrl().isBlank() ? h.bannerUrl() : null;
			bannerFrame.setVisibility(next != null ? View.VISIBLE : View.GONE);
			if (next == null) {
				ImageUtils.clear(banner);
				banner.setImageDrawable(null);
				bannerUrl = null;
			} else if (!next.equals(bannerUrl)) {
				bannerUrl = next;
				ImageUtils.load(banner, next, R.drawable.bg_avatar_placeholder);
			}
		}

		private void bindDescription(@NonNull Entry entry, @NonNull PageSource.Header h) {
			int collapsed = entry.fullPage ? FULL_COLLAPSED_LINES : SIMPLE_COLLAPSED_LINES;
			boolean hasText = DescriptionText.apply(description, h.description());
			description.setVisibility(hasText ? View.VISIBLE : View.GONE);
			applyExpanded(entry.expanded, collapsed);
			description.setOnClickListener(v -> {
				entry.expanded = !entry.expanded;
				applyExpanded(entry.expanded, collapsed);
			});
		}

		private void applyExpanded(boolean expanded, int collapsed) {
			description.setMaxLines(expanded ? Integer.MAX_VALUE : collapsed);
			// While collapsed, a tap expands the text instead of opening a link
			description.setMovementMethod(expanded ? LinkMovementMethod.getInstance() : null);
			description.setClickable(true);
		}

		private void bindFollow(@NonNull PageSource.Header h) {
			if (follow == null) return;
			MaterialButton button = follow;
			String channelId = h.kind() == PageSource.Kind.CHANNEL ? LocalSubscriptions.channelIdOf(h.channelId()) : null;
			if (channelId == null) {
				button.setVisibility(View.GONE);
				button.setOnClickListener(null);
				return;
			}
			LocalSubscriptions subscriptions = LocalSubscriptions.get();
			button.setVisibility(View.VISIBLE);
			styleFollow(button, subscriptions.isFollowing(channelId));
			button.setOnClickListener(v -> {
				if (subscriptions.isFollowing(channelId)) {
					subscriptions.remove(channelId);
				} else {
					subscriptions.add(new LocalSubscriptions.Channel(channelId, h.title()));
					ToastUtils.show(v.getContext(), R.string.subs_followed);
				}
				styleFollow(button, subscriptions.isFollowing(channelId));
				host.onFollowChanged();
			});
		}

		/** Light pill to follow, grey one once followed (like YouTube's subscribe button). */
		private void styleFollow(@NonNull MaterialButton button, boolean following) {
			Context context = button.getContext();
			button.setText(following ? R.string.watch_following : R.string.watch_follow);
			button.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(context,
							following ? R.color.surface_container_highest : R.color.inverse_surface)));
			button.setTextColor(ContextCompat.getColor(context, following ? R.color.on_surface : R.color.inverse_on_surface));
		}

		private void bindPlayAll(@NonNull Entry entry, @NonNull PageSource.Header h) {
			ListState playlistRows = entry.lists.get(PLAYLIST_LIST);
			List<FeedItem> rows = playlistRows != null ? playlistRows.items : List.of();
			boolean playlist = h.kind() == PageSource.Kind.PLAYLIST && h.playlistId() != null && !rows.isEmpty();
			playAll.setVisibility(playlist ? View.VISIBLE : View.GONE);
			playAll.setOnClickListener(playlist ? v -> {
				List<FeedItem> shown = visible(entry, rows);
				if (shown.isEmpty()) return;
				FeedItem first = shown.get(0);
				if (first.videoId() == null) return;
				host.openVideo(first.withUrl(Constant.HOME_URL + "/watch?v=" + first.videoId()
								+ "&list=" + h.playlistId() + "&index=" + (rows.indexOf(first) + 1)));
			} : null);
		}

		private void bindTabs(@NonNull Entry entry, @NonNull PageSource.Header h) {
			if (tabs == null || tabsFrame == null) return;
			List<String> ids = new ArrayList<>(h.tabs().size());
			for (PageSource.Tab tab : h.tabs()) ids.add(tab.id());
			tabsFrame.setVisibility(ids.isEmpty() ? View.GONE : View.VISIBLE);
			bindingTabs = true;
			try {
				if (!ids.equals(tabIds)) {
					tabs.removeAllTabs();
					for (String id : ids) {
						tabs.addTab(tabs.newTab().setText(tabLabel(id)).setTag(id), false);
					}
					tabIds = ids;
				}
				for (int i = 0; i < tabs.getTabCount(); i++) {
					TabLayout.Tab tab = tabs.getTabAt(i);
					if (tab != null && entry.tab.equals(tab.getTag())) {
						if (!tab.isSelected()) tab.select();
						break;
					}
				}
			} finally {
				bindingTabs = false;
			}
		}
	}
}
