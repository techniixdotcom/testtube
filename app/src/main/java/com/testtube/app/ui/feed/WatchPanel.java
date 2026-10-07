package com.testtube.app.ui.feed;

import android.content.Context;
import android.icu.text.CompactDecimalFormat;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateUtils;
import android.text.method.LinkMovementMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.annotation.SuppressLint;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import org.schabi.newpipe.extractor.Page;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;
import androidx.recyclerview.widget.ConcatAdapter;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.tabs.TabLayout;
import com.squareup.picasso.Picasso;
import com.testtube.app.Constant;
import com.testtube.app.R;
import com.testtube.app.browser.TabManager;
import com.testtube.app.extractor.FeedClient;
import com.testtube.app.extractor.LocalSubscriptions;
import com.testtube.app.extractor.FeedItem;
import com.testtube.app.extractor.RelatedVideo;
import com.testtube.app.extractor.VideoDetails;
import com.testtube.app.extractor.YoutubeExtractor;
import com.testtube.app.filter.ContentFilters;
import com.testtube.app.util.ToastUtils;
import com.testtube.app.util.UrlUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import android.webkit.WebResourceRequest;
import android.net.Uri;

/**
 * Native watch screen below the player: title, channel, description and what plays next (the
 * playlist when the video is part of one, the suggestions otherwise).
 */
@UnstableApi
public final class WatchPanel {
	private static final String SEPARATOR = " • ";
	private static final int COLLAPSED_LINES = 3;

	@NonNull
	private final Handler handler = new Handler(Looper.getMainLooper());
	@NonNull
	private final YoutubeExtractor extractor;
	@NonNull
	private final TabManager tabManager;
	@NonNull
	private final ContentFilters filters;
	@NonNull
	private final NativeBrowser.Host host;
	@NonNull
	private final FeedClient feedClient;
	@NonNull
	private final RecyclerView list;
	@NonNull
	private final View chatPanel;
	@NonNull
	private final FrameLayout chatHost;
	@Nullable
	private WebView chatWeb;
	@Nullable
	private String chatVideoId;
	private boolean chatTab;
	private boolean live;
	@NonNull
	private final TabLayout tabs;
	@NonNull
	private final View chatHint;
	@NonNull
	private final RecyclerView commentsList;
	@NonNull
	private final TextView commentsMessage;
	@NonNull
	private final CommentAdapter comments = new CommentAdapter();
	@Nullable
	private String commentsVideoId;
	@Nullable
	private Page commentsNext;
	private boolean commentsLoading;
	private long commentsToken;
	@Nullable
	private String note;
	@NonNull
	private final HeaderAdapter header = new HeaderAdapter();
	@NonNull
	private final FeedAdapter items;
	@Nullable
	private String url;
	@Nullable
	private String videoId;
	@Nullable
	private VideoDetails details;
	@Nullable
	private String section;
	private boolean expanded;
	private long token;

	public WatchPanel(@NonNull View root,
	                  @NonNull YoutubeExtractor extractor,
	                  @NonNull FeedClient feedClient,
	                  @NonNull TabManager tabManager,
	                  @NonNull ContentFilters filters,
	                  @NonNull NativeBrowser.Host host) {
		this.extractor = extractor;
		this.feedClient = feedClient;
		this.tabManager = tabManager;
		this.filters = filters;
		this.host = host;
		this.list = root.findViewById(R.id.watch_list);
		chatPanel = root.findViewById(R.id.watch_chat);
		chatHost = root.findViewById(R.id.watch_chat_host);
		tabs = root.findViewById(R.id.watch_tabs);
		chatHint = root.findViewById(R.id.watch_chat_hint);
		commentsMessage = root.findViewById(R.id.watch_comments_message);
		commentsList = root.findViewById(R.id.watch_comments);
		LinearLayoutManager commentsLayout = new LinearLayoutManager(root.getContext());
		commentsList.setLayoutManager(commentsLayout);
		commentsList.setAdapter(comments);
		commentsList.addOnScrollListener(new RecyclerView.OnScrollListener() {
			@Override
			public void onScrolled(@NonNull RecyclerView view, int dx, int dy) {
				if (dy > 0 && commentsLayout.findLastVisibleItemPosition() >= comments.getItemCount() - 5) {
					loadComments(false);
				}
			}
		});
		tabs.addTab(tabs.newTab().setText(R.string.watch_tab_description));
		tabs.addTab(tabs.newTab().setText(R.string.watch_tab_comments));
		tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
			@Override
			public void onTabSelected(TabLayout.Tab tab) {
				chatTab = tab.getPosition() == 1;
				applyTab();
			}

			@Override
			public void onTabUnselected(TabLayout.Tab tab) {
			}

			@Override
			public void onTabReselected(TabLayout.Tab tab) {
			}
		});
		items = new FeedAdapter(filters, new FeedAdapter.Listener() {
			@Override
			public void onOpen(@NonNull FeedItem item) {
				if (item.kind() == FeedItem.Kind.VIDEO) host.openVideo(item);
				else host.openPage(item.url());
			}

			@Override
			public void onOpenAuthor(@NonNull FeedItem item) {
				if (item.authorUrl() != null) host.openPage(item.authorUrl());
			}

			@Override
			public void onMenu(@NonNull FeedItem item) {
				if (item.kind() == FeedItem.Kind.VIDEO) host.showMenu(item);
			}
		});
		list.setLayoutManager(new LinearLayoutManager(root.getContext()));
		list.setAdapter(new ConcatAdapter(header, items));
		new ItemTouchHelper(new QueueSwipe(root.getContext(), new QueueSwipe.Target() {
			@Nullable
			@Override
			public FeedItem itemFor(@NonNull RecyclerView.ViewHolder holder) {
				return items.itemAt(holder);
			}

			@Override
			public void enqueue(@NonNull FeedItem item) {
				host.enqueue(item);
			}

			@Override
			public void follow(@NonNull FeedItem item) {
				host.follow(item);
			}
		})).attachToRecyclerView(list);
		list.setItemViewCacheSize(4);
	}

	/**
	 * The watch screen shows another video (or closed when url is null).
	 */
	public void show(@Nullable String url) {
		if (Objects.equals(this.url, url)) return;
		String id = YoutubeExtractor.getVideoId(url);
		boolean sameVideo = id != null && id.equals(videoId);
		String previousList = this.url != null ? UrlUtils.getQueryParameter(this.url, "list") : null;
		String list = url != null ? UrlUtils.getQueryParameter(url, "list") : null;
		this.url = url;
		if (!sameVideo) {
			videoId = id;
			details = null;
			expanded = false;
			header.notifyItemChanged(0);
		}
		long current = ++token;
		if (url == null || id == null) {
			items.submit(new ArrayList<>());
			releaseChat();
			return;
		}
		if (!sameVideo) {
			note = null;
			chatVideoId = null;
			live = false;
			resetComments();
			applyTab();
		}
		if (!sameVideo || !Objects.equals(previousList, list)) {
			if (!Objects.equals(previousList, list)) items.submit(new ArrayList<>());
			loadNext(current, id, list != null);
		}
		this.list.scrollToPosition(0);
	}

	/**
	 * The player finished loading a video.
	 */
	public void onDetails(@NonNull VideoDetails details) {
		if (!Objects.equals(details.getId(), videoId)) return;
		this.details = details;
		header.notifyItemChanged(0);
		if (live != details.isLive()) {
			live = details.isLive();
			chatVideoId = null;
			resetComments();
			applyTab();
		}
	}

	/**
	 * Blocked channels or watched marks changed.
	 */
	public void onFiltersChanged() {
		items.refreshStates();
	}

	private void loadNext(long current, @NonNull String id, boolean playlist) {
		if (playlist) {
			tabManager.loadPlaylist(result -> {
				if (current != token) return;
				if (result != null && !result.items().isEmpty()) {
					section = result.title();
					header.notifyItemChanged(0);
					items.submit(visible(result.items()));
				} else {
					loadRelated(current, id);
				}
			});
			return;
		}
		loadRelated(current, id);
	}

	private void loadRelated(long current, @NonNull String id) {
		section = null;
		header.notifyItemChanged(0);
		extractor.getRelatedVideos(id).whenComplete((related, error) -> handler.post(() -> {
			if (current != token) return;
			List<FeedItem> out = new ArrayList<>();
			if (related != null) {
				for (RelatedVideo video : related) {
					if (video.title() == null) continue;
					out.add(new FeedItem(FeedItem.Kind.VIDEO, Constant.HOME_URL + "/watch?v=" + video.id(), video.id(),
									video.title(), video.uploaderName(), FeedClient.mobile(video.uploaderUrl()),
									FeedItem.thumbnailFor(video.id()), video.durationSeconds(),
									video.viewCount(), video.published(), false));
				}
			}
			if (!out.isEmpty()) {
				items.submit(out);
				return;
			}
			loadRelatedFallback(current, id);
		}));
	}

	/**
	 * The extractor had no suggestions: ask YouTube's "next" endpoint directly.
	 */
	private void loadRelatedFallback(long current, @NonNull String id) {
		FeedClient.Call call = feedClient.related(id);
		call.result.whenComplete((page, error) -> handler.post(() -> {
			if (current != token) return;
			List<FeedItem> out = page != null ? visible(page.items()) : new ArrayList<>();
			note = out.isEmpty() ? list.getContext().getString(R.string.watch_no_suggestions) : null;
			header.notifyItemChanged(0);
			items.submit(out);
		}));
	}

	private void applyTab() {
		TabLayout.Tab second = tabs.getTabAt(1);
		if (second != null) second.setText(live ? R.string.watch_tab_chat : R.string.watch_tab_comments);
		list.setVisibility(chatTab ? View.GONE : View.VISIBLE);
		chatPanel.setVisibility(chatTab ? View.VISIBLE : View.GONE);
		chatHint.setVisibility(live ? View.VISIBLE : View.GONE);
		chatHost.setVisibility(live ? View.VISIBLE : View.GONE);
		commentsList.setVisibility(live ? View.GONE : View.VISIBLE);
		if (!chatTab) return;
		if (live) loadChat();
		else loadComments(true);
	}

	private void resetComments() {
		commentsToken++;
		commentsVideoId = null;
		commentsNext = null;
		commentsLoading = false;
		comments.submit(new ArrayList<>());
		commentsMessage.setVisibility(View.GONE);
	}

	private void loadComments(boolean first) {
		String id = videoId;
		if (id == null || live || commentsLoading) return;
		if (first && id.equals(commentsVideoId)) return;
		if (!first && commentsNext == null) return;
		commentsVideoId = id;
		commentsLoading = true;
		long current = commentsToken;
		Page page = first ? null : commentsNext;
		feedClient.comments(id, page).whenComplete((result, error) -> handler.post(() -> {
			if (current != commentsToken) return;
			commentsLoading = false;
			if (error != null || result == null) {
				if (first) commentsVideoId = null;
				return;
			}
			commentsNext = result.next();
			comments.add(result.items());
			Context context = commentsList.getContext();
			if (result.disabled()) {
				commentsMessage.setText(context.getString(R.string.watch_comments_disabled));
			} else if (comments.getItemCount() == 0) {
				commentsMessage.setText(context.getString(R.string.watch_comments_empty));
			}
			commentsMessage.setVisibility(comments.getItemCount() == 0 && (result.disabled() || !result.items().isEmpty()
							|| result.next() == null) ? View.VISIBLE : View.GONE);
		}));
	}

	@SuppressLint("SetJavaScriptEnabled")
	private void loadChat() {
		String id = videoId;
		if (id == null || id.equals(chatVideoId)) return;
		if (chatWeb == null) {
			WebView web = new WebView(chatHost.getContext());
			WebSettings settings = web.getSettings();
			settings.setJavaScriptEnabled(true);
			settings.setDomStorageEnabled(true);
			settings.setUserAgentString(Constant.USER_AGENT);
			settings.setAllowFileAccess(false);
			settings.setAllowContentAccess(false);
			settings.setGeolocationEnabled(false);
			settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
			// The chat only ever shows YouTube pages: any other link or scheme is ignored.
			web.setWebViewClient(new WebViewClient() {
				@Override
				public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
					Uri uri = request.getUrl();
					String host = uri.getHost();
					boolean youtube = "https".equals(uri.getScheme()) && host != null
									&& (host.equals("youtube.com") || host.endsWith(".youtube.com"));
					return !youtube;
				}
			});
			chatHost.addView(web, new FrameLayout.LayoutParams(
							ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
			chatWeb = web;
		}
		chatVideoId = id;
		chatWeb.loadUrl("https://www.youtube.com/live_chat?is_popout=1&v=" + id);
	}

	private void releaseChat() {
		chatVideoId = null;
		if (chatWeb != null) chatWeb.loadUrl("about:blank");
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

	private static final class CommentAdapter extends RecyclerView.Adapter<CommentHolder> {
		@NonNull
		private final List<FeedClient.Comment> data = new ArrayList<>();

		void submit(@NonNull List<FeedClient.Comment> items) {
			data.clear();
			data.addAll(items);
			notifyDataSetChanged();
		}

		void add(@NonNull List<FeedClient.Comment> items) {
			int start = data.size();
			data.addAll(items);
			notifyItemRangeInserted(start, items.size());
		}

		@Override
		public int getItemCount() {
			return data.size();
		}

		@NonNull
		@Override
		public CommentHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
			return new CommentHolder(LayoutInflater.from(parent.getContext())
							.inflate(R.layout.item_comment, parent, false));
		}

		@Override
		public void onBindViewHolder(@NonNull CommentHolder holder, int position) {
			FeedClient.Comment comment = data.get(position);
			List<String> parts = new ArrayList<>(3);
			parts.add(comment.author());
			if (comment.published() != null && !comment.published().isBlank()) parts.add(comment.published());
			Context context = holder.itemView.getContext();
			if (comment.likes() > 0) parts.add(context.getString(R.string.watch_comment_likes, comment.likes()));
			if (comment.pinned()) parts.add(context.getString(R.string.watch_comment_pinned));
			holder.meta.setText(String.join(SEPARATOR, parts));
			holder.text.setText(comment.text());
		}
	}

	private static final class CommentHolder extends RecyclerView.ViewHolder {
		@NonNull
		final TextView meta;
		@NonNull
		final TextView text;

		CommentHolder(@NonNull View itemView) {
			super(itemView);
			meta = itemView.findViewById(R.id.comment_meta);
			text = itemView.findViewById(R.id.comment_text);
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
							.inflate(R.layout.item_watch_header, parent, false));
		}

		@Override
		public void onBindViewHolder(@NonNull HeaderHolder holder, int position) {
			holder.bind();
		}
	}

	private final class HeaderHolder extends RecyclerView.ViewHolder {
		@NonNull
		private final TextView title;
		@NonNull
		private final TextView meta;
		@NonNull
		private final View channel;
		@NonNull
		private final ImageView avatar;
		@NonNull
		private final TextView author;
		@NonNull
		private final TextView votes;
		@NonNull
		private final TextView follow;
		@NonNull
		private final TextView description;
		@NonNull
		private final TextView sectionView;

		HeaderHolder(@NonNull View itemView) {
			super(itemView);
			title = itemView.findViewById(R.id.watch_title);
			meta = itemView.findViewById(R.id.watch_meta);
			channel = itemView.findViewById(R.id.watch_channel);
			avatar = itemView.findViewById(R.id.watch_avatar);
			author = itemView.findViewById(R.id.watch_author);
			votes = itemView.findViewById(R.id.watch_votes);
			follow = itemView.findViewById(R.id.watch_follow);
			description = itemView.findViewById(R.id.watch_description);
			sectionView = itemView.findViewById(R.id.watch_section);
			description.setOnClickListener(v -> {
				expanded = !expanded;
				applyExpanded();
			});
		}

		void bind() {
			Context context = itemView.getContext();
			VideoDetails d = details;
			title.setText(d != null && d.getTitle() != null ? d.getTitle() : "");
			List<String> parts = new ArrayList<>(2);
			if (d != null && d.getViewCount() >= 0) parts.add(context.getString(R.string.feed_views, compact(d.getViewCount())));
			if (d != null && d.getUploadDate() != null) {
				parts.add(DateUtils.getRelativeTimeSpanString(d.getUploadDate().getTime(),
								System.currentTimeMillis(), DateUtils.DAY_IN_MILLIS).toString());
			}
			meta.setText(String.join(SEPARATOR, parts));
			author.setText(d != null && d.getAuthor() != null ? d.getAuthor() : "");
			List<String> voteParts = new ArrayList<>(2);
			if (d != null && d.getLikeCount() > 0) voteParts.add(context.getString(R.string.watch_likes, compact(d.getLikeCount())));
			if (d != null && d.getDislikeCount() > 0) voteParts.add(context.getString(R.string.watch_dislikes, compact(d.getDislikeCount())));
			votes.setText(String.join(SEPARATOR, voteParts));
			String avatarUrl = d != null ? d.getUploaderAvatarUrl() : null;
			if (avatarUrl != null && !avatarUrl.isBlank()) {
				Picasso.get().load(avatarUrl).fit().centerCrop().into(avatar);
			} else {
				Picasso.get().cancelRequest(avatar);
				avatar.setImageDrawable(null);
			}
			String channelUrl = d != null ? FeedClient.mobile(d.getUploaderUrl()) : null;
			channel.setOnClickListener(channelUrl != null ? v -> host.openPage(channelUrl) : null);
			channel.setClickable(channelUrl != null);
			bindFollow(d);
			boolean hasText = DescriptionText.apply(description, d != null ? d.getDescription() : null);
			description.setVisibility(hasText ? View.VISIBLE : View.GONE);
			applyExpanded();
			sectionView.setText(note != null ? note
							: section != null && !section.isBlank() ? section : context.getString(R.string.watch_up_next));
		}

		/**
		 * Follow the channel without an account: its videos then show up under Subscriptions.
		 */
		private void bindFollow(@Nullable VideoDetails d) {
			String channelId = d != null ? LocalSubscriptions.channelIdOf(d.getUploaderUrl()) : null;
			if (channelId == null) {
				follow.setVisibility(View.GONE);
				return;
			}
			LocalSubscriptions subscriptions = LocalSubscriptions.get();
			String name = d.getAuthor() != null ? d.getAuthor() : "";
			follow.setVisibility(View.VISIBLE);
			follow.setText(subscriptions.isFollowing(channelId) ? R.string.watch_following : R.string.watch_follow);
			follow.setOnClickListener(v -> {
				if (subscriptions.isFollowing(channelId)) {
					subscriptions.remove(channelId);
				} else {
					subscriptions.add(new LocalSubscriptions.Channel(channelId, name));
					ToastUtils.show(v.getContext(), R.string.subs_followed);
				}
				follow.setText(subscriptions.isFollowing(channelId) ? R.string.watch_following : R.string.watch_follow);
				host.onFollowChanged();
			});
		}

		private void applyExpanded() {
			description.setMaxLines(expanded ? Integer.MAX_VALUE : COLLAPSED_LINES);
			// Links only react once the text is expanded, so a tap on the collapsed text expands it.
			description.setMovementMethod(expanded ? LinkMovementMethod.getInstance() : null);
			description.setClickable(true);
		}
	}
}
