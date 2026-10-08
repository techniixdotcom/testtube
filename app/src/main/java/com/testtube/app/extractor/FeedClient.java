package com.testtube.app.extractor;

import android.text.Html;
import android.text.format.DateUtils;
import android.util.Log;
import android.util.Xml;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonWriter;
import com.tencent.mmkv.MMKV;
import com.testtube.app.Constant;

import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ListExtractor;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.channel.ChannelInfo;
import org.schabi.newpipe.extractor.channel.ChannelInfoItem;
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo;
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs;
import org.schabi.newpipe.extractor.comments.CommentsExtractor;
import org.schabi.newpipe.extractor.comments.CommentsInfoItem;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.localization.ContentCountry;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem;
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItemExtractor;
import org.schabi.newpipe.extractor.search.SearchExtractor;
import org.schabi.newpipe.extractor.services.youtube.InnertubeClientRequestInfo;
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper;
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeMixOrPlaylistLockupInfoItemExtractor;
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamInfoItemExtractor;
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamInfoItemLockupExtractor;
import org.schabi.newpipe.extractor.stream.Description;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamInfoItemExtractor;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Home, subscriptions and search straight from YouTube (with the account cookies), turned into
 * {@link FeedItem}s. Shorts, posts and ads are filtered out.
 */
public final class FeedClient {
	private static final int MAX_DEPTH = 48;
	private static final String LOCKUP_VIDEO = "LOCKUP_CONTENT_TYPE_VIDEO";
	private static final String LOCKUP_PLAYLIST = "LOCKUP_CONTENT_TYPE_PLAYLIST";
	private static final String LOCKUP_MIX = "LOCKUP_CONTENT_TYPE_MIX";
	// ad slots, Premium upsells, nudges, banners
	private static final Pattern PROMO_KEY = Pattern.compile(
					"^ad[A-Z]|Ad[A-Z]|(?i:promo|upsell|nudge|masthead|banner|sponsor|searchPyv)");

	// sections that never contain feed videos, or only stuff we hide
	private static final Set<String> SKIPPED = Set.of(
					"responseContext", "topbar", "header", "frameworkUpdates", "sidebar",
					"reelShelfRenderer", "reelItemRenderer", "shortsLockupViewModel",
					"adSlotRenderer", "promotedSparklesWebRenderer", "promotedVideoRenderer",
					"backstagePostThreadRenderer", "backstagePostRenderer", "postRenderer",
					"sharedPostRenderer", "chipCloudRenderer", "feedFilterChipBarRenderer",
					"statementBannerRenderer", "brandVideoShelfRenderer", "inlineSurveyRenderer",
					"mastheadRenderer", "bannerPromoRenderer", "primetimePromoRenderer",
					"emergencyOnebox", "clarificationRenderer", "infoPanelContainerRenderer");

	public enum Feed {
		HOME("FEwhat_to_watch"),
		SUBSCRIPTIONS("FEsubscriptions");

		@NonNull
		final String browseId;

		Feed(@NonNull String browseId) {
			this.browseId = browseId;
		}
	}

	/**
	 * @param next token for the next page, or null at the end
	 */
	public record FeedPage(@NonNull List<FeedItem> items, @Nullable Object next, boolean signedIn) {
	}

	public record Comment(@NonNull String author, @Nullable String avatarUrl, @NonNull String text,
	                      @Nullable String published, int likes, boolean pinned) {
	}

	/**
	 * @param next token for the next page, or null at the end
	 * @param disabled true when the video has comments turned off
	 */
	public record CommentPage(@NonNull List<Comment> items, @Nullable Page next, boolean disabled) {
	}

	@NonNull
	public CompletableFuture<CommentPage> comments(@NonNull String videoId, @Nullable Page page) {
		CompletableFuture<CommentPage> result = new CompletableFuture<>();
		try {
			executor.execute(() -> {
				try {
					AuthContext context = auth.current(true);
					ExtractionSession session = new ExtractionSession(context);
					result.complete(downloader.withExtractionSession(() -> loadComments(videoId, page), session));
				} catch (Throwable error) {
					result.completeExceptionally(error);
				}
			});
		} catch (RuntimeException rejected) {
			result.completeExceptionally(new CompletionException(rejected));
		}
		return result;
	}

	@NonNull
	private static CommentPage loadComments(@NonNull String videoId, @Nullable Page page)
					throws IOException, ExtractionException {
		CommentsExtractor extractor = ServiceList.YouTube.getCommentsExtractor(watchUrl(videoId));
		ListExtractor.InfoItemsPage<CommentsInfoItem> result;
		if (page == null) {
			extractor.fetchPage();
			if (extractor.isCommentsDisabled()) return new CommentPage(new ArrayList<>(), null, true);
			result = extractor.getInitialPage();
		} else {
			result = extractor.getPage(page);
		}
		List<Comment> out = new ArrayList<>();
		for (CommentsInfoItem item : result.getItems()) {
			Description text = item.getCommentText();
			String content = text == null ? "" : text.content();
			if (text != null && text.type() == Description.Type.HTML) {
				content = Html.fromHtml(content, Html.FROM_HTML_MODE_COMPACT).toString().trim();
			}
			if (content.isBlank()) continue;
			out.add(new Comment(item.getUploaderName() == null ? "" : item.getUploaderName(),
							image(item.getUploaderAvatars()), content, item.getTextualUploadDate(),
							item.getLikeCount(), item.isPinned()));
		}
		return new CommentPage(out, result.hasNextPage() ? result.getNextPage() : null, false);
	}

	public static final class Call {
		@NonNull
		public final CompletableFuture<FeedPage> result = new CompletableFuture<>();
		@Nullable
		private volatile ExtractionSession session;
		private volatile boolean cancelled;

		public void cancel() {
			cancelled = true;
			ExtractionSession current = session;
			if (current != null) current.cancel();
			result.cancel(false);
		}
	}

	@FunctionalInterface
	private interface Load {
		@NonNull
		FeedPage run(boolean signedIn) throws IOException, ExtractionException;
	}

	@NonNull
	private final DownloaderImpl downloader;
	@NonNull
	private final NativeAuth auth;
	@NonNull
	private final Executor executor;

	public FeedClient(@NonNull DownloaderImpl downloader, @NonNull NativeAuth auth, @NonNull Executor executor) {
		this.downloader = downloader;
		this.auth = auth;
		this.executor = executor;
	}

	public boolean isSignedIn() {
		return auth.isSignedIn();
	}

	/**
	 * @param continuation token from the previous page, or null for the first page
	 */
	@NonNull
	public Call browse(@NonNull Feed feed, @Nullable String continuation) {
		return run(signedIn -> {
			if (feed == Feed.HOME) return homePage(continuation, signedIn);
			FeedPage page = browsePage(feed, continuation, null);
			FeedItem.sortNewestFirst(page.items());
			if (signedIn) rememberSubscribed(page.items());
			return page;
		});
	}

	/**
	 * Home is for discovery: no channels you already follow, at most a couple of videos per channel,
	 * and topped up with related videos when there isn't enough left.
	 */
	@NonNull
	private FeedPage homePage(@Nullable String continuation, boolean signedIn)
					throws IOException, ExtractionException {
		Set<String> known = knownChannels();
		if (continuation != null && continuation.startsWith(DISCOVER_PREFIX)) {
			// out of YouTube pages, keep going via related videos
			List<String> seeds = List.of(continuation.substring(DISCOVER_PREFIX.length()).split(","));
			List<FeedItem> more = discover(known, seeds);
			return new FeedPage(more, more.isEmpty() ? null : discoverToken(more), signedIn);
		}
		FeedPage page = browsePage(Feed.HOME, continuation, null);
		if (page.items().isEmpty() && !signedIn && continuation == null) {
			// signed out, Home can come back empty without a visitor id - retry with one
			String visitor = visitorData();
			if (visitor != null) page = browsePage(Feed.HOME, null, visitor);
		}
		List<FeedItem> items = curate(page.items(), known);
		Object next = page.next();
		for (int i = 0; i < MAX_EXTRA_PAGES && items.size() < HOME_MIN_PAGE && next instanceof String token; i++) {
			FeedPage more = browsePage(Feed.HOME, token, null);
			items.addAll(curate(more.items(), known));
			next = more.next();
		}
		if (continuation == null && items.size() < HOME_MIN_PAGE) {
			Set<String> urls = new HashSet<>();
			for (FeedItem item : items) urls.add(item.url());
			for (FeedItem item : discover(known, List.of())) {
				if (urls.add(item.url())) items.add(item);
			}
			Collections.shuffle(items);
		}
		// Home never ends: after YouTube's last page we carry on with related videos.
		return new FeedPage(items, next != null ? next : discoverToken(items), page.signedIn());
	}

	// continues Home from a few random videos of the current page
	@NonNull
	private static String discoverToken(@NonNull List<FeedItem> shown) {
		List<String> ids = new ArrayList<>();
		for (FeedItem item : shown) {
			if (item.videoId() != null) ids.add(item.videoId());
		}
		Collections.shuffle(ids);
		return DISCOVER_PREFIX + String.join(",", ids.subList(0, Math.min(ids.size(), DISCOVER_SEEDS)));
	}

	/** Drops followed channels and caps everything else at two videos per channel. */
	@NonNull
	private static List<FeedItem> curate(@NonNull List<FeedItem> items, @NonNull Set<String> known) {
		List<FeedItem> out = new ArrayList<>();
		Map<String, Integer> perChannel = new HashMap<>();
		for (FeedItem item : items) {
			if (isKnown(item, known)) continue;
			String channel = item.author() == null ? "" : item.author();
			if (!channel.isEmpty() && perChannel.merge(channel, 1, Integer::sum) > MAX_PER_CHANNEL) continue;
			out.add(item);
		}
		return out;
	}

	private static boolean isKnown(@NonNull FeedItem item, @NonNull Set<String> known) {
		String id = LocalSubscriptions.channelIdOf(item.authorUrl());
		if (id != null && known.contains(id)) return true;
		return item.author() != null && known.contains(item.author().trim().toLowerCase(Locale.ROOT));
	}

	// followed channels + account subscriptions seen so far (ids and names)
	@NonNull
	private static Set<String> knownChannels() {
		Set<String> known = new HashSet<>();
		for (LocalSubscriptions.Channel channel : LocalSubscriptions.get().all()) {
			known.add(channel.id());
			if (!channel.name().isBlank()) known.add(channel.name().trim().toLowerCase(Locale.ROOT));
		}
		Set<String> saved = MMKV.defaultMMKV().decodeStringSet(KEY_SUBSCRIBED, null);
		if (saved != null) known.addAll(saved);
		return known;
	}

	private static void rememberSubscribed(@NonNull List<FeedItem> items) {
		MMKV store = MMKV.defaultMMKV();
		Set<String> saved = new HashSet<>();
		Set<String> old = store.decodeStringSet(KEY_SUBSCRIBED, null);
		if (old != null) saved.addAll(old);
		for (FeedItem item : items) {
			String id = LocalSubscriptions.channelIdOf(item.authorUrl());
			if (id != null) saved.add(id);
			if (item.author() != null && !item.author().isBlank()) {
				saved.add(item.author().trim().toLowerCase(Locale.ROOT));
			}
		}
		if (saved.size() <= MAX_REMEMBERED) store.encode(KEY_SUBSCRIBED, saved);
	}

	@NonNull
	private static FeedPage browsePage(@NonNull Feed feed, @Nullable String continuation,
	                                   @Nullable String visitorData) throws IOException, ExtractionException {
		Localization localization = NewPipe.getPreferredLocalization();
		ContentCountry country = NewPipe.getPreferredContentCountry();
		JsonObject root = YoutubeParsingHelper.prepareDesktopJsonBuilder(localization, country)
						.value(continuation == null ? "browseId" : "continuation",
										continuation == null ? feed.browseId : continuation)
						.done();
		if (visitorData != null) {
			JsonObject context = root.getObject("context");
			JsonObject client = context == null ? null : context.getObject("client");
			if (client != null) client.put("visitorData", visitorData);
		}
		byte[] body = JsonWriter.string(root).getBytes(StandardCharsets.UTF_8);
		JsonObject response = YoutubeParsingHelper.getJsonPostResponse("browse", body, localization);
		Parser parser = new Parser();
		parser.walk(response, 0);
		return new FeedPage(parser.items, parser.continuation, false);
	}

	@Nullable
	private static String visitorData() {
		try {
			InnertubeClientRequestInfo info = InnertubeClientRequestInfo.ofWebClient();
			info.clientInfo.clientVersion = YoutubeParsingHelper.getClientVersion();
			return YoutubeParsingHelper.getVisitorDataFromInnertube(info, Localization.DEFAULT,
							ContentCountry.DEFAULT, YoutubeParsingHelper.getYouTubeHeaders(),
							YoutubeParsingHelper.YOUTUBEI_V1_URL, null, false);
		} catch (Exception e) {
			return null;
		}
	}

	private static final List<String> GENERAL_QUERIES = List.of("trending", "popular today", "new this week",
					"most watched", "documentary", "music", "gaming", "science", "how it works", "travel",
					"cooking", "comedy", "tech review", "history", "sports highlights", "podcast");
	private static final String KEY_SUBSCRIBED = "subscribed_channels";
	private static final int MAX_REMEMBERED = 1500;
	private static final int MAX_PER_CHANNEL = 2;
	private static final int HOME_MIN_PAGE = 6;
	private static final int MAX_EXTRA_PAGES = 2;
	// marks the endless (related videos) part of Home, followed by the ids of the last page
	private static final String DISCOVER_PREFIX = "testtube:discover:";
	private static final int DISCOVER_SEEDS = 3;
	private static final int DISCOVER_TARGET = 40;
	private static final String TAG = "FeedClient";
	// first page waits this long for channel feeds, slower ones show up on the next load
	private static final long FEED_WAIT_MS = 9_000L;
	private static final int RELATED_LOOKUPS = 4;
	private static final long EARLY_WAIT_MS = 2_500L;
	private static final int EARLY_MIN_VIDEOS = 20;
	// time budget for the extractor fallback when a channel's feed failed
	private static final long FALLBACK_WAIT_MS = 8_000L;
	private static final int MAX_FALLBACK_CHANNELS = 6;
	private static final long CHANNEL_CACHE_MS = 3 * 60_000L;
	private static final long OLDER_BUDGET_MS = 25_000L;
	private static final int MAX_LOCAL_VIDEOS = 120;
	private static final ExecutorService FEED_POOL = Executors.newFixedThreadPool(12, task -> {
		Thread thread = new Thread(task, "channel-feed");
		thread.setDaemon(true);
		return thread;
	});

	// Random recent videos (for related lookups) and titles (for searches), so Home changes
	// every time but stays relevant.
	public record Taste(@NonNull List<String> videoIds, @NonNull List<String> titles) {
	}

	@NonNull
	private volatile Supplier<Taste> taste = () -> new Taste(List.of(), List.of());

	public void setTaste(@NonNull Supplier<Taste> taste) {
		this.taste = taste;
	}

	/**
	 * Related videos for the seeds (or recent history), without followed channels, shuffled.
	 * Used when YouTube's Home is empty, mostly followed channels, or ran out.
	 */
	@NonNull
	private List<FeedItem> discover(@NonNull Set<String> known, @NonNull List<String> seeds) {
		List<FeedItem> pool = new ArrayList<>();
		Taste current;
		try {
			current = taste.get();
		} catch (RuntimeException e) {
			current = new Taste(List.of(), List.of());
		}
		List<String> sources = seeds.isEmpty() || seeds.get(0).isEmpty() ? current.videoIds() : seeds;
		for (String videoId : sources) {
			try {
				pool.addAll(relatedItems(videoId));
			} catch (IOException | ExtractionException ignored) {
				// one failed lookup shouldn't empty Home
			}
		}
		List<String> queries = new ArrayList<>();
		for (String title : current.titles()) queries.add(firstWords(title, 5));
		if (pool.size() < DISCOVER_TARGET) {
			List<String> general = new ArrayList<>(GENERAL_QUERIES);
			Collections.shuffle(general);
			queries.addAll(general.subList(0, 3));
		}
		for (String query : queries) {
			try {
				pool.addAll(searchVideos(query));
			} catch (IOException | ExtractionException ignored) {
				// same
			}
			if (pool.size() >= DISCOVER_TARGET * 2) break;
		}
		Collections.shuffle(pool);
		List<FeedItem> out = curate(pool, known);
		return out.size() > DISCOVER_TARGET ? out.subList(0, DISCOVER_TARGET) : out;
	}

	@NonNull
	private static String firstWords(@NonNull String text, int count) {
		String[] words = text.trim().split("\\s+");
		return String.join(" ", Arrays.copyOf(words, Math.min(words.length, count)));
	}

	@NonNull
	private static List<FeedItem> searchVideos(@NonNull String query) throws IOException, ExtractionException {
		SearchExtractor extractor = ServiceList.YouTube.getSearchExtractor(query);
		extractor.fetchPage();
		List<FeedItem> out = new ArrayList<>();
		for (InfoItem item : extractor.getInitialPage().getItems()) {
			FeedItem converted = convert(item);
			if (converted != null && converted.kind() == FeedItem.Kind.VIDEO) out.add(converted);
		}
		return out;
	}

	private record RssVideo(@NonNull String id, @NonNull String title, @NonNull String author,
	                        @NonNull String channelId, long views, long time) {
	}

	// continuation per followed channel; missing means nothing older left
	private record LocalCursor(@NonNull Map<String, ChannelCursor> channels) {
	}

	private record ChannelCursor(@Nullable ListLinkHandler tab, @Nullable Page page, @NonNull String name) {
	}

	/**
	 * Latest uploads of locally followed channels, newest first. Next pages go further back.
	 *
	 * @param continuation {@code next} from the previous page, or null
	 */
	@NonNull
	public Call localSubscriptions(@NonNull List<LocalSubscriptions.Channel> channels,
	                               @Nullable Object continuation) {
		return localSubscriptions(channels, continuation, null);
	}

	/**
	 * Same as {@link #localSubscriptions(List, Object)}, but {@code partial} gets the first page's
	 * videos as each channel responds (on a worker thread).
	 */
	@NonNull
	public Call localSubscriptions(@NonNull List<LocalSubscriptions.Channel> channels,
	                               @Nullable Object continuation,
	                               @Nullable Consumer<List<FeedItem>> partial) {
		return run(signedIn -> continuation instanceof LocalCursor cursor
						? olderLocalVideos(cursor)
						: loadLocalFeed(channels, partial));
	}

	private record CachedChannel(long loadedAt, @NonNull List<RssVideo> videos) {
	}

	// per-channel cache so reopening Subscriptions doesn't refetch everything
	private static final Map<String, CachedChannel> CHANNEL_CACHE = new ConcurrentHashMap<>();

	/** Fetches one channel's feed and caches it, even if the caller stopped waiting. */
	@NonNull
	private static List<RssVideo> channelVideos(@NonNull LocalSubscriptions.Channel channel)
					throws IOException, XmlPullParserException {
		List<RssVideo> videos = fetchChannelFeed(channel);
		if (!videos.isEmpty()) CHANNEL_CACHE.put(channel.id(), new CachedChannel(System.currentTimeMillis(), videos));
		return videos;
	}

	@NonNull
	private static FeedPage loadLocalFeed(@NonNull List<LocalSubscriptions.Channel> channels,
	                                      @Nullable Consumer<List<FeedItem>> partial) throws IOException {
		long start = System.currentTimeMillis();
		List<RssVideo> all = new ArrayList<>();
		List<RssVideo> cachedNow = new ArrayList<>();
		ExecutorCompletionService<List<RssVideo>> done = new ExecutorCompletionService<>(FEED_POOL);
		Map<Future<List<RssVideo>>, LocalSubscriptions.Channel> pending = new HashMap<>();
		for (LocalSubscriptions.Channel channel : channels) {
			CachedChannel cached = CHANNEL_CACHE.get(channel.id());
			if (cached != null && start - cached.loadedAt() < CHANNEL_CACHE_MS) cachedNow.addAll(cached.videos());
			else pending.put(done.submit(() -> channelVideos(channel)), channel);
		}
		all.addAll(cachedNow);
		if (partial != null && !cachedNow.isEmpty()) partial.accept(toFeedItems(cachedNow, Integer.MAX_VALUE));
		// push videos to the page as each channel responds
		List<LocalSubscriptions.Channel> failed = new ArrayList<>();
		while (!pending.isEmpty()) {
			long now = System.currentTimeMillis();
			long limit = all.size() >= EARLY_MIN_VIDEOS ? Math.max(start + EARLY_WAIT_MS, now) : start + FEED_WAIT_MS;
			List<RssVideo> batch = new ArrayList<>();
			try {
				Future<List<RssVideo>> first = done.poll(Math.max(0L, limit - now), TimeUnit.MILLISECONDS);
				// too slow, the rest keeps loading in the background and shows up next time
				if (first == null) break;
				for (Future<List<RssVideo>> ready = first; ready != null; ready = done.poll()) {
					LocalSubscriptions.Channel channel = pending.remove(ready);
					try {
						List<RssVideo> videos = ready.get();
						if (videos.isEmpty() && channel != null) failed.add(channel);
						else batch.addAll(videos);
					} catch (ExecutionException e) {
						Log.w(TAG, "channel feed failed", e);
						if (channel != null) failed.add(channel);
					}
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}
			all.addAll(batch);
			if (partial != null && !batch.isEmpty()) partial.accept(toFeedItems(batch, Integer.MAX_VALUE));
		}
		// channels whose feed failed or was empty go through the extractor, in parallel
		if (!failed.isEmpty()) {
			List<Future<List<RssVideo>>> fallbacks = new ArrayList<>();
			for (LocalSubscriptions.Channel channel : failed.subList(0, Math.min(failed.size(), MAX_FALLBACK_CHANNELS))) {
				fallbacks.add(FEED_POOL.submit(() -> videosFromExtractor(channel)));
			}
			long until = System.currentTimeMillis() + FALLBACK_WAIT_MS;
			for (Future<List<RssVideo>> fallback : fallbacks) {
				try {
					all.addAll(fallback.get(Math.max(1L, until - System.currentTimeMillis()), TimeUnit.MILLISECONDS));
				} catch (InterruptedException | ExecutionException | TimeoutException e) {
					Log.w(TAG, "extractor fallback failed", e);
				}
			}
		}
		// We follow channels but couldn't read any of them. An error is better than an empty list.
		if (all.isEmpty() && !channels.isEmpty()) throw new IOException("no followed channel could be loaded");
		Map<String, ChannelCursor> older = new LinkedHashMap<>();
		for (LocalSubscriptions.Channel channel : channels) {
			older.put(channel.id(), new ChannelCursor(null, null, channel.name()));
		}
		return new FeedPage(toFeedItems(all, MAX_LOCAL_VIDEOS), new LocalCursor(older), true);
	}

	@NonNull
	private static List<FeedItem> toFeedItems(@NonNull List<RssVideo> videos, int limit) {
		videos.sort((a, b) -> Long.compare(b.time(), a.time()));
		long now = System.currentTimeMillis();
		List<FeedItem> items = new ArrayList<>();
		for (RssVideo video : videos) {
			if (items.size() >= limit) break;
			items.add(new FeedItem(FeedItem.Kind.VIDEO, watchUrl(video.id()), video.id(), video.title(),
							video.author(), mobile("https://www.youtube.com/channel/" + video.channelId()),
							FeedItem.thumbnailFor(video.id()), -1, video.views(),
							DateUtils.getRelativeTimeSpanString(video.time(), now, DateUtils.MINUTE_IN_MILLIS,
											DateUtils.FORMAT_ABBREV_RELATIVE).toString(), false));
		}
		return items;
	}

	private record OlderRound(@NonNull String channelId, @Nullable ChannelCursor next,
	                          @NonNull List<RssVideo> videos) {
	}

	@NonNull
	private static FeedPage olderLocalVideos(@NonNull LocalCursor cursor) {
		Map<String, Future<OlderRound>> pending = new LinkedHashMap<>();
		for (Map.Entry<String, ChannelCursor> entry : cursor.channels().entrySet()) {
			pending.put(entry.getKey(), FEED_POOL.submit(() -> olderRound(entry.getKey(), entry.getValue())));
		}
		long deadline = System.currentTimeMillis() + OLDER_BUDGET_MS;
		Map<String, ChannelCursor> next = new LinkedHashMap<>();
		List<RssVideo> videos = new ArrayList<>();
		for (Map.Entry<String, Future<OlderRound>> entry : pending.entrySet()) {
			try {
				OlderRound round = entry.getValue().get(Math.max(1L, deadline - System.currentTimeMillis()),
								TimeUnit.MILLISECONDS);
				videos.addAll(round.videos());
				if (round.next() != null) next.put(round.channelId(), round.next());
			} catch (TimeoutException e) {
				// too slow, leave the channel where it was and retry on the next page
				entry.getValue().cancel(true);
				next.put(entry.getKey(), cursor.channels().get(entry.getKey()));
			} catch (InterruptedException | ExecutionException e) {
				Log.w(TAG, "older uploads failed id=" + entry.getKey(), e);
			}
		}
		return new FeedPage(toFeedItems(videos, Integer.MAX_VALUE), next.isEmpty() ? null : new LocalCursor(next), true);
	}

	@NonNull
	private static OlderRound olderRound(@NonNull String channelId, @NonNull ChannelCursor cursor)
					throws IOException, ExtractionException {
		ListLinkHandler tab = cursor.tab();
		String name = cursor.name();
		List<InfoItem> items;
		Page nextPage;
		if (tab == null) {
			ChannelInfo info = ChannelInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/channel/" + channelId);
			name = info.getName();
			for (ListLinkHandler candidate : info.getTabs()) {
				if (candidate.getContentFilters().contains(ChannelTabs.VIDEOS)) tab = candidate;
			}
			if (tab == null) return new OlderRound(channelId, null, List.of());
			ChannelTabInfo first = ChannelTabInfo.getInfo(ServiceList.YouTube, tab);
			items = first.getRelatedItems();
			nextPage = first.getNextPage();
		} else if (cursor.page() != null) {
			ListExtractor.InfoItemsPage<InfoItem> more = ChannelTabInfo.getMoreItems(ServiceList.YouTube, tab, cursor.page());
			items = more.getItems();
			nextPage = more.getNextPage();
		} else {
			return new OlderRound(channelId, null, List.of());
		}
		long now = System.currentTimeMillis();
		int index = 0;
		List<RssVideo> videos = new ArrayList<>();
		for (InfoItem item : items) {
			if (!(item instanceof StreamInfoItem stream) || stream.isShortFormContent()) continue;
			String id = YoutubeExtractor.getVideoId(stream.getUrl());
			if (id == null) continue;
			long time = stream.getUploadDate() != null
							? stream.getUploadDate().offsetDateTime().toInstant().toEpochMilli()
							: now - (index++) * DateUtils.HOUR_IN_MILLIS;
			videos.add(new RssVideo(id, stream.getName(), name, channelId, stream.getViewCount(), time));
		}
		ChannelCursor next = nextPage != null && Page.isValid(nextPage) ? new ChannelCursor(tab, nextPage, name) : null;
		return new OlderRound(channelId, next, videos);
	}

	@NonNull
	private static List<RssVideo> videosFromExtractor(@NonNull LocalSubscriptions.Channel channel)
					throws IOException, ExtractionException {
		ChannelInfo info = ChannelInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/channel/" + channel.id());
		List<RssVideo> out = new ArrayList<>();
		for (ListLinkHandler tab : info.getTabs()) {
			if (!tab.getContentFilters().contains(ChannelTabs.VIDEOS)) continue;
			long now = System.currentTimeMillis();
			int index = 0;
			for (InfoItem item : ChannelTabInfo.getInfo(ServiceList.YouTube, tab).getRelatedItems()) {
				if (!(item instanceof StreamInfoItem stream) || stream.isShortFormContent()) continue;
				String id = YoutubeExtractor.getVideoId(stream.getUrl());
				if (id == null) continue;
				// no exact date, keep the channel's own order
				long time = stream.getUploadDate() != null
								? stream.getUploadDate().offsetDateTime().toInstant().toEpochMilli()
								: now - (index++) * DateUtils.HOUR_IN_MILLIS;
				out.add(new RssVideo(id, stream.getName(), info.getName(), channel.id(), stream.getViewCount(), time));
				if (out.size() >= 10) break;
			}
			break;
		}
		LocalSubscriptions.get().rename(channel.id(), info.getName());
		return out;
	}

	@NonNull
	private static List<RssVideo> fetchChannelFeed(@NonNull LocalSubscriptions.Channel channel)
					throws IOException, XmlPullParserException {
		URL url = new URL("https://www.youtube.com/feeds/videos.xml?channel_id=" + channel.id());
		HttpURLConnection connection = (HttpURLConnection) url.openConnection();
		connection.setConnectTimeout(5_000);
		connection.setReadTimeout(7_000);
		connection.setRequestProperty("User-Agent", Constant.USER_AGENT);
		try (InputStream stream = connection.getInputStream()) {
			return parseChannelFeed(stream, channel);
		} finally {
			connection.disconnect();
		}
	}

	@NonNull
	private static List<RssVideo> parseChannelFeed(@NonNull InputStream stream,
	                                               @NonNull LocalSubscriptions.Channel channel)
					throws IOException, XmlPullParserException {
		XmlPullParser parser = Xml.newPullParser();
		parser.setInput(stream, null);
		List<RssVideo> out = new ArrayList<>();
		String feedAuthor = channel.name();
		boolean inEntry = false;
		boolean inAuthor = false;
		String id = null, title = null, author = null, published = null;
		long views = -1;
		for (int event = parser.getEventType(); event != XmlPullParser.END_DOCUMENT; event = parser.next()) {
			if (event == XmlPullParser.START_TAG) {
				String name = parser.getName();
				if ("entry".equals(name)) {
					inEntry = true;
					id = title = author = published = null;
					views = -1;
				} else if ("author".equals(name)) {
					inAuthor = true;
				} else if ("name".equals(name) && inAuthor) {
					String text = parser.nextText();
					if (inEntry) author = text;
					else feedAuthor = text;
				} else if (inEntry) {
					switch (name) {
						case "yt:videoId" -> id = parser.nextText();
						case "title" -> title = parser.nextText();
						case "published" -> published = parser.nextText();
						case "media:statistics" -> {
							String count = parser.getAttributeValue(null, "views");
							if (count != null) {
								try {
									views = Long.parseLong(count);
								} catch (NumberFormatException ignored) {
									views = -1;
								}
							}
						}
						default -> {
						}
					}
				}
			} else if (event == XmlPullParser.END_TAG) {
				String name = parser.getName();
				if ("author".equals(name)) {
					inAuthor = false;
				} else if ("entry".equals(name)) {
					inEntry = false;
					if (id != null && title != null && published != null) {
						try {
							long time = OffsetDateTime.parse(published).toInstant().toEpochMilli();
							out.add(new RssVideo(id, title, author != null ? author : feedAuthor, channel.id(),
											views, time));
						} catch (RuntimeException ignored) {
							// skip entries with unreadable dates
						}
					}
				}
			}
		}
		LocalSubscriptions.get().rename(channel.id(), feedAuthor);
		return out;
	}

	@NonNull
	public CompletableFuture<LocalSubscriptions.Channel> resolveChannel(@NonNull String input) {
		CompletableFuture<LocalSubscriptions.Channel> result = new CompletableFuture<>();
		String id = LocalSubscriptions.channelIdOf(input);
		if (id != null) {
			result.complete(new LocalSubscriptions.Channel(id, ""));
			return result;
		}
		try {
			executor.execute(() -> {
				try {
					AuthContext context = auth.current(true);
					ExtractionSession session = new ExtractionSession(context);
					result.complete(downloader.withExtractionSession(() -> lookupChannel(input), session));
				} catch (Throwable error) {
					result.completeExceptionally(error);
				}
			});
		} catch (RuntimeException rejected) {
			result.completeExceptionally(new CompletionException(rejected));
		}
		return result;
	}

	@NonNull
	private static LocalSubscriptions.Channel lookupChannel(@NonNull String input)
					throws IOException, ExtractionException {
		String url = input.trim();
		if (url.startsWith("@")) url = "https://www.youtube.com/" + url;
		else if (!url.contains("/") && !url.contains(".")) url = "https://www.youtube.com/@" + url;
		else if (!url.startsWith("http")) url = "https://" + url;
		ChannelInfo info = ChannelInfo.getInfo(ServiceList.YouTube, url);
		return new LocalSubscriptions.Channel(info.getId(), info.getName() == null ? "" : info.getName());
	}

	@NonNull
	public Call related(@NonNull String videoId) {
		return run(signedIn -> new FeedPage(relatedItems(videoId), null, signedIn));
	}

	/**
	 * More suggestions for the watch list: related videos of a few random ones already in it,
	 * minus duplicates, so the list never runs dry.
	 */
	@NonNull
	public Call moreRelated(@NonNull List<String> seedIds, @NonNull Set<String> exclude) {
		return run(signedIn -> {
			List<String> seeds = new ArrayList<>(seedIds);
			Collections.shuffle(seeds);
			List<Future<List<FeedItem>>> lookups = new ArrayList<>();
			for (String seed : seeds.subList(0, Math.min(seeds.size(), RELATED_LOOKUPS))) {
				lookups.add(FEED_POOL.submit(() -> relatedItems(seed)));
			}
			Set<String> seen = new HashSet<>(exclude);
			List<FeedItem> out = new ArrayList<>();
			long until = System.currentTimeMillis() + FEED_WAIT_MS;
			for (Future<List<FeedItem>> lookup : lookups) {
				try {
					for (FeedItem item : lookup.get(Math.max(1L, until - System.currentTimeMillis()), TimeUnit.MILLISECONDS)) {
						if (item.videoId() != null && seen.add(item.videoId())) out.add(item);
					}
				} catch (InterruptedException | ExecutionException | TimeoutException e) {
					Log.w(TAG, "more suggestions failed", e);
				}
			}
			Collections.shuffle(out);
			return new FeedPage(out, null, signedIn);
		});
	}

	@NonNull
	private static List<FeedItem> relatedItems(@NonNull String videoId) throws IOException, ExtractionException {
		Localization localization = NewPipe.getPreferredLocalization();
		ContentCountry country = NewPipe.getPreferredContentCountry();
		byte[] body = JsonWriter.string(YoutubeParsingHelper.prepareDesktopJsonBuilder(localization, country)
						.value("videoId", videoId).done()).getBytes(StandardCharsets.UTF_8);
		JsonObject response = YoutubeParsingHelper.getJsonPostResponse("next", body, localization);
		Parser parser = new Parser();
		parser.walk(response, 0);
		List<FeedItem> out = new ArrayList<>();
		for (FeedItem item : parser.items) {
			if (item.kind() == FeedItem.Kind.VIDEO && !videoId.equals(item.videoId())) out.add(item);
		}
		return out;
	}

	/**
	 * @param page page from the previous result, or null for the first page
	 */
	@NonNull
	public Call search(@NonNull String query, @Nullable Page page) {
		return run(signedIn -> {
			SearchExtractor extractor = ServiceList.YouTube.getSearchExtractor(query);
			ListExtractor.InfoItemsPage<InfoItem> result;
			if (page == null) {
				extractor.fetchPage();
				result = extractor.getInitialPage();
			} else {
				result = extractor.getPage(page);
			}
			List<FeedItem> items = new ArrayList<>();
			Set<String> seen = new HashSet<>();
			for (InfoItem item : result.getItems()) {
				FeedItem converted = convert(item);
				if (converted != null && seen.add(converted.url())) items.add(converted);
			}
			return new FeedPage(items, result.hasNextPage() ? result.getNextPage() : null, signedIn);
		});
	}

	@NonNull
	private Call run(@NonNull Load load) {
		Call call = new Call();
		try {
			executor.execute(() -> {
				if (call.cancelled) return;
				try {
					AuthContext context = auth.current(true);
					ExtractionSession session = new ExtractionSession(context);
					call.session = session;
					if (call.cancelled) {
						session.cancel();
						return;
					}
					FeedPage page = downloader.withExtractionSession(() -> load.run(context.loggedIn()), session);
					call.result.complete(page);
				} catch (Throwable error) {
					call.result.completeExceptionally(call.cancelled ? new CancellationException() : error);
				}
			});
		} catch (RuntimeException rejected) {
			call.result.completeExceptionally(new CompletionException(rejected));
		}
		return call;
	}

	@Nullable
	private static FeedItem convert(@NonNull InfoItem item) {
		if (item instanceof StreamInfoItem stream) {
			if (stream.isShortFormContent()) return null;
			String videoId = YoutubeExtractor.getVideoId(stream.getUrl());
			if (videoId == null) return null;
			StreamType type = stream.getStreamType();
			return new FeedItem(FeedItem.Kind.VIDEO, watchUrl(videoId), videoId, stream.getName(),
							stream.getUploaderName(), mobile(stream.getUploaderUrl()), FeedItem.thumbnailFor(videoId),
							stream.getDuration(), stream.getViewCount(), stream.getTextualUploadDate(),
							isLive(type));
		}
		if (item instanceof ChannelInfoItem channel) {
			return new FeedItem(FeedItem.Kind.CHANNEL, mobile(channel.getUrl()), null, channel.getName(),
							null, null, image(channel.getThumbnails()), -1L, channel.getSubscriberCount(),
							null, false);
		}
		if (item instanceof PlaylistInfoItem playlist) {
			return new FeedItem(FeedItem.Kind.PLAYLIST, mobile(playlist.getUrl()), null, playlist.getName(),
							playlist.getUploaderName(), mobile(playlist.getUploaderUrl()),
							image(playlist.getThumbnails()), -1L, playlist.getStreamCount(), null, false);
		}
		return null;
	}

	private static boolean isLive(@Nullable StreamType type) {
		return type == StreamType.LIVE_STREAM || type == StreamType.AUDIO_LIVE_STREAM;
	}

	@NonNull
	private static String watchUrl(@NonNull String videoId) {
		return Constant.HOME_URL + "/watch?v=" + videoId;
	}

	// we open YouTube pages on the mobile site
	@Nullable
	public static String mobile(@Nullable String url) {
		if (url == null || url.isBlank()) return null;
		return url.replaceFirst("^https?://(www\\.)?youtube\\.com", Constant.HOME_URL);
	}

	/** Smallest thumbnail that still looks sharp in a list row, otherwise the biggest. */
	@Nullable
	private static String image(@Nullable List<Image> images) {
		if (images == null || images.isEmpty()) return null;
		Image best = null;
		Image largest = null;
		for (Image image : images) {
			if (largest == null || image.getWidth() > largest.getWidth()) largest = image;
			if (image.getWidth() >= 176 && (best == null || image.getWidth() < best.getWidth())) best = image;
		}
		Image chosen = best != null ? best : largest;
		return chosen != null ? chosen.getUrl() : null;
	}

	/** Collects the videos from a browse response, however YouTube nests them. */
	private static final class Parser {
		@NonNull
		final List<FeedItem> items = new ArrayList<>();
		@NonNull
		private final Set<String> seen = new HashSet<>();
		@Nullable
		String continuation;

		void walk(@Nullable Object node, int depth) {
			if (depth > MAX_DEPTH || node == null) return;
			if (node instanceof JsonArray array) {
				for (Object child : array) walk(child, depth + 1);
				return;
			}
			if (!(node instanceof JsonObject object)) return;
			for (Map.Entry<String, Object> entry : object.entrySet()) {
				String key = entry.getKey();
				Object value = entry.getValue();
				if (SKIPPED.contains(key) || isPromoKey(key)
								|| !(value instanceof JsonObject || value instanceof JsonArray)) {
					continue;
				}
				switch (key) {
					case "videoRenderer", "gridVideoRenderer", "compactVideoRenderer" ->
									addVideo(new YoutubeStreamInfoItemExtractor((JsonObject) value, null));
					case "lockupViewModel" -> addLockup((JsonObject) value);
					case "continuationItemRenderer" -> readContinuation((JsonObject) value);
					default -> walk(value, depth + 1);
				}
			}
		}

		// Ads, Premium upsells, nudges and banners come under lots of names. Anything that looks
		// like one is dropped along with everything inside it.
		private static boolean isPromoKey(@NonNull String key) {
			return PROMO_KEY.matcher(key).find();
		}

		private void addLockup(@NonNull JsonObject lockup) {
			String type = lockup.getString("contentType", "");
			if (LOCKUP_VIDEO.equals(type)) {
				addVideo(new YoutubeStreamInfoItemLockupExtractor(lockup, null));
			} else if (LOCKUP_PLAYLIST.equals(type) || LOCKUP_MIX.equals(type)) {
				addPlaylist(new YoutubeMixOrPlaylistLockupInfoItemExtractor(lockup));
			}
		}

		private void addVideo(@NonNull StreamInfoItemExtractor extractor) {
			try {
				if (extractor.isAd() || extractor.isShortFormContent()) return;
				String videoId = YoutubeExtractor.getVideoId(extractor.getUrl());
				if (videoId == null || !seen.add(videoId)) return;
				StreamType type = safeType(extractor);
				long duration = safeDuration(extractor);
				String published = safePublished(extractor);
				// Real videos have a length or are live/upcoming. A bare title is an ad pretending to be a video.
				if (duration <= 0 && !isLive(type) && safeViews(extractor) < 0
								&& (published == null || published.isBlank())) {
					return;
				}
				items.add(new FeedItem(FeedItem.Kind.VIDEO, watchUrl(videoId), videoId,
								extractor.getName(), safeUploader(extractor), mobile(safeUploaderUrl(extractor)),
								FeedItem.thumbnailFor(videoId), duration, safeViews(extractor),
								published, isLive(type)));
			} catch (Exception ignored) {
				// don't lose the whole page over one bad entry
			}
		}

		private void addPlaylist(@NonNull PlaylistInfoItemExtractor extractor) {
			try {
				String url = mobile(extractor.getUrl());
				if (url == null || !seen.add(url)) return;
				String author;
				try {
					author = extractor.getUploaderName();
				} catch (Exception e) {
					author = null;
				}
				long count;
				try {
					count = extractor.getStreamCount();
				} catch (Exception e) {
					count = -1L;
				}
				items.add(new FeedItem(FeedItem.Kind.PLAYLIST, url, null, extractor.getName(), author,
								null, image(extractor.getThumbnails()), -1L, count, null, false));
			} catch (Exception ignored) {
				// skip entries in a shape we don't know
			}
		}

		private void readContinuation(@NonNull JsonObject renderer) {
			if (continuation != null) return;
			JsonObject endpoint = renderer.getObject("continuationEndpoint");
			String token = endpoint.getObject("continuationCommand").getString("token");
			if (token == null) {
				for (Object command : endpoint.getObject("commandExecutorCommand").getArray("commands")) {
					if (command instanceof JsonObject object) {
						token = object.getObject("continuationCommand").getString("token");
						if (token != null) break;
					}
				}
			}
			if (token != null && !token.isEmpty()) continuation = token;
		}

		@Nullable
		private static StreamType safeType(@NonNull StreamInfoItemExtractor extractor) {
			try {
				return extractor.getStreamType();
			} catch (Exception e) {
				return null;
			}
		}

		@Nullable
		private static String safeUploader(@NonNull StreamInfoItemExtractor extractor) {
			try {
				return extractor.getUploaderName();
			} catch (Exception e) {
				return null;
			}
		}

		@Nullable
		private static String safeUploaderUrl(@NonNull StreamInfoItemExtractor extractor) {
			try {
				return extractor.getUploaderUrl();
			} catch (Exception e) {
				return null;
			}
		}

		private static long safeDuration(@NonNull StreamInfoItemExtractor extractor) {
			try {
				return extractor.getDuration();
			} catch (Exception e) {
				return -1L;
			}
		}

		private static long safeViews(@NonNull StreamInfoItemExtractor extractor) {
			try {
				return extractor.getViewCount();
			} catch (Exception e) {
				return -1L;
			}
		}

		@Nullable
		private static String safePublished(@NonNull StreamInfoItemExtractor extractor) {
			try {
				String text = extractor.getTextualUploadDate();
				return text == null || text.isBlank() ? null : text.trim();
			} catch (Exception e) {
				return null;
			}
		}
	}
}
