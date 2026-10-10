package com.testtube.app.ui.feed;

import android.content.Context;
import android.icu.text.CompactDecimalFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.text.method.LinkMovementMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.ConcatAdapter;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.squareup.picasso.Picasso;
import com.testtube.app.Constant;
import com.testtube.app.R;
import com.testtube.app.extractor.FeedItem;
import com.testtube.app.extractor.PageSource;
import com.testtube.app.filter.ContentFilters;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Native channel and playlist pages. Pages stack up as the user opens them; back returns to the
 * previous one with its list and scroll position intact.
 */
public final class PageScreen {
	private static final int LOAD_MORE_THRESHOLD = 6;
	private static final int MAX_PAGES = 6;
	private static final int COLLAPSED_LINES = 3;
	private static final String SEPARATOR = " • ";

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
					int index = entry.items.indexOf(item);
					host.openVideo(new FeedItem(item.kind(), Constant.HOME_URL + "/watch?v=" + item.videoId()
									+ "&list=" + playlistId + "&index=" + (Math.max(0, index) + 1), item.videoId(),
									item.title(), item.author(), item.authorUrl(), item.thumbnailUrl(),
									item.durationSeconds(), item.count(), item.published(), item.live()));
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
			public void onMenu(@NonNull FeedItem item) {
				if (item.kind() == FeedItem.Kind.VIDEO) host.showMenu(item);
			}
		});
		layoutManager = new LinearLayoutManager(root.getContext());
		list.setLayoutManager(layoutManager);
		list.setAdapter(new ConcatAdapter(header, items));
		list.setItemViewCacheSize(6);
		list.addOnScrollListener(new RecyclerView.OnScrollListener() {
			@Override
			public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
				if (dy <= 0) return;
				int last = layoutManager.findLastVisibleItemPosition();
				if (last >= header.getItemCount() + items.getItemCount() - LOAD_MORE_THRESHOLD) loadMore();
			}
		});
		back.setOnClickListener(v -> back());
		refresh.setColorSchemeResources(R.color.yt_red);
		refresh.setOnRefreshListener(() -> {
			Entry entry = stack.peekLast();
			if (entry != null) load(entry, true);
			else refresh.setRefreshing(false);
		});
		message.setOnClickListener(v -> {
			Entry entry = stack.peekLast();
			if (entry != null && entry.error) load(entry, !entry.loaded);
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
		load(entry, true);
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
		listener.onPageVisible(false);
	}

	public void onFiltersChanged() {
		Entry entry = stack.peekLast();
		if (entry != null) items.submit(visible(entry.items));
		items.refreshStates();
	}

	private void saveScroll() {
		Entry current = stack.peekLast();
		if (current != null) current.scroll = layoutManager.onSaveInstanceState();
	}

	private void render(@NonNull Entry entry, boolean top) {
		barTitle.setText(entry.header != null ? entry.header.title() : null);
		header.notifyItemChanged(0);
		items.submit(visible(entry.items));
		refresh.setRefreshing(entry.call != null && entry.refreshing);
		if (top || entry.scroll == null) {
			list.scrollToPosition(0);
		} else {
			layoutManager.onRestoreInstanceState(entry.scroll);
		}
		boolean empty = entry.items.isEmpty();
		if (!empty || entry.call != null || (!entry.error && !entry.loaded)) {
			message.setVisibility(View.GONE);
		} else {
			message.setText(entry.error ? R.string.feed_error : R.string.feed_empty);
			message.setVisibility(View.VISIBLE);
		}
	}

	private void loadMore() {
		Entry entry = stack.peekLast();
		if (entry == null || entry.call != null || entry.next == null || entry.error) return;
		load(entry, false);
	}

	private void load(@NonNull Entry entry, boolean fresh) {
		if (entry.call != null) {
			if (!fresh) return;
			entry.call.cancel(false);
		}
		PageSource.Cursor cursor = fresh ? null : entry.next;
		if (!fresh && cursor == null) return;
		CompletableFuture<PageSource.Result> call = fresh ? source.open(entry.kind, entry.url) : source.more(cursor);
		entry.call = call;
		entry.refreshing = fresh;
		entry.error = false;
		if (entry == stack.peekLast() && fresh) refresh.setRefreshing(true);
		call.whenComplete((result, error) -> handler.post(() -> {
			if (entry.call != call) return;
			entry.call = null;
			if (error != null || result == null) {
				if (!call.isCancelled()) entry.error = true;
			} else {
				if (fresh) {
					entry.items.clear();
					entry.seen.clear();
					entry.header = result.header();
				}
				for (FeedItem item : result.items()) {
					if (entry.seen.add(item.url())) entry.items.add(item);
				}
				entry.next = result.next();
				entry.loaded = true;
			}
			if (entry == stack.peekLast()) render(entry, false);
		}));
	}

	@NonNull
	private List<FeedItem> visible(@NonNull List<FeedItem> source) {
		List<FeedItem> out = new ArrayList<>(source.size());
		for (FeedItem item : source) {
			if (item.author() != null && filters.isChannelBlocked(item.author(), item.authorUrl())) continue;
			out.add(item);
		}
		return out;
	}

	@NonNull
	private static String compact(long value) {
		return CompactDecimalFormat.getInstance(Locale.getDefault(), CompactDecimalFormat.CompactStyle.SHORT)
						.format(value);
	}

	private static final class Entry {
		@NonNull
		final PageSource.Kind kind;
		@NonNull
		final String url;
		@NonNull
		final List<FeedItem> items = new ArrayList<>();
		@NonNull
		final Set<String> seen = new HashSet<>();
		@Nullable
		PageSource.Header header;
		@Nullable
		PageSource.Cursor next;
		@Nullable
		CompletableFuture<PageSource.Result> call;
		@Nullable
		Parcelable scroll;
		boolean loaded;
		boolean refreshing;
		boolean error;
		boolean expanded;

		Entry(@NonNull PageSource.Kind kind, @NonNull String url) {
			this.kind = kind;
			this.url = url;
		}

		void cancel() {
			CompletableFuture<PageSource.Result> current = call;
			call = null;
			if (current != null) current.cancel(false);
		}
	}

	private final class HeaderAdapter extends RecyclerView.Adapter<HeaderHolder> {
		@Override
		public int getItemCount() {
			return 1;
		}

		@NonNull
		@Override
		public HeaderHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
			return new HeaderHolder(LayoutInflater.from(parent.getContext())
							.inflate(R.layout.item_page_header, parent, false));
		}

		@Override
		public void onBindViewHolder(@NonNull HeaderHolder holder, int position) {
			holder.bind(stack.peekLast());
		}
	}

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

		HeaderHolder(@NonNull View itemView) {
			super(itemView);
			avatar = itemView.findViewById(R.id.page_avatar);
			title = itemView.findViewById(R.id.page_title);
			subtitle = itemView.findViewById(R.id.page_subtitle);
			description = itemView.findViewById(R.id.page_description);
			playAll = itemView.findViewById(R.id.page_play_all);
		}

		void bind(@Nullable Entry entry) {
			Context context = itemView.getContext();
			PageSource.Header h = entry != null ? entry.header : null;
			itemView.setVisibility(h != null ? View.VISIBLE : View.GONE);
			if (entry == null || h == null) return;
			title.setText(h.title());
			List<String> parts = new ArrayList<>(2);
			if (h.kind() == PageSource.Kind.CHANNEL) {
				if (h.count() >= 0) parts.add(context.getString(R.string.feed_subscribers, compact(h.count())));
			} else {
				if (h.subtitle() != null && !h.subtitle().isBlank()) parts.add(h.subtitle());
				if (h.count() >= 0) parts.add(context.getString(R.string.feed_videos, compact(h.count())));
			}
			subtitle.setText(String.join(SEPARATOR, parts));
			boolean hasAvatar = h.avatarUrl() != null && !h.avatarUrl().isBlank();
			avatar.setVisibility(hasAvatar ? View.VISIBLE : View.GONE);
			if (hasAvatar) {
				Picasso.get().load(h.avatarUrl()).fit().centerCrop().into(avatar);
			} else {
				Picasso.get().cancelRequest(avatar);
			}
			boolean hasText = DescriptionText.apply(description, h.description());
			description.setVisibility(hasText ? View.VISIBLE : View.GONE);
			description.setMaxLines(entry.expanded ? Integer.MAX_VALUE : COLLAPSED_LINES);
			description.setMovementMethod(entry.expanded ? LinkMovementMethod.getInstance() : null);
			description.setOnClickListener(v -> {
				entry.expanded = !entry.expanded;
				description.setMaxLines(entry.expanded ? Integer.MAX_VALUE : COLLAPSED_LINES);
				description.setMovementMethod(entry.expanded ? LinkMovementMethod.getInstance() : null);
			});
			boolean playlist = h.kind() == PageSource.Kind.PLAYLIST && h.playlistId() != null && !entry.items.isEmpty();
			playAll.setVisibility(playlist ? View.VISIBLE : View.GONE);
			playAll.setOnClickListener(playlist ? v -> {
				List<FeedItem> shown = visible(entry.items);
				if (shown.isEmpty()) return;
				FeedItem first = shown.get(0);
				host.openVideo(new FeedItem(first.kind(), Constant.HOME_URL + "/watch?v=" + first.videoId()
								+ "&list=" + h.playlistId() + "&index=" + (entry.items.indexOf(first) + 1), first.videoId(),
								first.title(), first.author(), first.authorUrl(), first.thumbnailUrl(),
								first.durationSeconds(), first.count(), first.published(), first.live()));
			} : null);
		}
	}
}
