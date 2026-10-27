package com.testtube.app.extractor;

import android.text.Html;
import android.text.format.DateUtils;
import android.util.Log;
import android.util.Xml;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import com.grack.nanojson.JsonParserException;
import com.grack.nanojson.JsonWriter;
import com.tencent.mmkv.MMKV;
import com.testtube.app.AppSettings;
import com.testtube.app.Constant;
import com.testtube.app.net.RequestPacer;

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
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeCommentsExtractor;
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeMixOrPlaylistLockupInfoItemExtractor;
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeReelInfoItemExtractor;
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeShortsLockupInfoItemExtractor;
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamInfoItemExtractor;
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamInfoItemLockupExtractor;
import org.schabi.newpipe.extractor.stream.Description;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamInfoItemExtractor;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
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
 * Home, Subscriptions and search straight from InnerTube (with the account cookies when signed
 * in), as {@link FeedItem}s. Posts and ads are skipped, Shorts too unless they are switched on.
 */
public final class FeedClient {
	private static final int MAX_DEPTH = 48;
	private static final String LOCKUP_VIDEO = "LOCKUP_CONTENT_TYPE_VIDEO";
	private static final String LOCKUP_PLAYLIST = "LOCKUP_CONTENT_TYPE_PLAYLIST";
	private static final String LOCKUP_MIX = "LOCKUP_CONTENT_TYPE_MIX";
	/** Renderer names of ads, upsells, nudges and banners. */
	private static final Pattern PROMO_KEY = Pattern.compile(
					"^ad[A-Z]|Ad[A-Z]|(?i:promo|upsell|nudge|masthead|banner|sponsor|searchPyv)");

	/** Response parts that never contain feed videos, or only content we hide. */
	private static final Set<String> SKIPPED = Set.of(
					"responseContext", "topbar", "header", "frameworkUpdates", "sidebar",
					"adSlotRenderer", "promotedSparklesWebRenderer", "promotedVideoRenderer",
					"backstagePostThreadRenderer", "backstagePostRenderer", "postRenderer",
					"sharedPostRenderer", "chipCloudRenderer", "feedFilterChipBarRenderer",
					"statementBannerRenderer", "brandVideoShelfRenderer", "inlineSurveyRenderer",
					"mastheadRenderer", "bannerPromoRenderer", "primetimePromoRenderer",
					"emergencyOnebox", "clarificationRenderer", "infoPanelContainerRenderer");

	private static final Set<String> SHORTS_KEYS = Set.of(
					"reelShelfRenderer", "reelItemRenderer", "shortsLockupViewModel");

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
	/**
	 * @param whole the items are everything loaded so far, in order, and replace what's shown
	 *              instead of being added below it
	 */
	public record FeedPage(@NonNull List<FeedItem> items, @Nullable Object next, boolean signedIn,
	                       boolean whole) {
		public FeedPage(@NonNull List<FeedItem> items, @Nullable Object next, boolean signedIn) {
			this(items, next, signedIn, false);
		}
	}

	public record Comment(@NonNull String author, @Nullable String avatarUrl, @NonNull String text,
	                      @Nullable String published, int likes, boolean pinned) {
	}

	/**
	 * @param next         token for the next page, or null at the end
	 * @param disabled     true when the video has comments turned off
	 * @param createParams token for posting a comment, on the first page for a signed-in account
	 *                     that may comment; null otherwise
	 */
	public record CommentPage(@NonNull List<Comment> items, @Nullable Page next, boolean disabled,
	                          @Nullable String createParams) {
	}

	/**
	 * A page of top-level comments for a video.
	 */
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
		String createParams = null;
		if (page == null) {
			extractor.fetchPage();
			if (extractor.isCommentsDisabled()) return new CommentPage(new ArrayList<>(), null, true, null);
			result = extractor.getInitialPage();
			if (extractor instanceof YoutubeCommentsExtractor youtube) {
				createParams = youtube.getCreateCommentParams();
			}
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
		return new CommentPage(out, result.hasNextPage() ? result.getNextPage() : null, false, createParams);
	}

	/**
	 * Posts a top-level comment as the signed-in account.
	 *
	 * @param createParams the token from {@link CommentPage#createParams()}
	 * @return the comment as YouTube shows it, with the account's name when it was in the answer
	 */
	@NonNull
	public CompletableFuture<Comment> postComment(@NonNull String createParams, @NonNull String text) {
		CompletableFuture<Comment> result = new CompletableFuture<>();
		try {
			executor.execute(() -> {
				try {
					AuthContext context = auth.current(true);
					if (!context.loggedIn()) throw new IOException("Not signed in");
					ExtractionSession session = new ExtractionSession(context);
					result.complete(downloader.withExtractionSession(() -> createComment(createParams, text), session));
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
	private static Comment createComment(@NonNull String createParams, @NonNull String text)
					throws IOException, ExtractionException {
		Localization localization = NewPipe.getPreferredLocalization();
		ContentCountry country = NewPipe.getPreferredContentCountry();
		byte[] body = JsonWriter.string(YoutubeParsingHelper.prepareDesktopJsonBuilder(localization, country)
										.value("commentText", text)
										.value("createCommentParams", createParams)
										.done())
						.getBytes(StandardCharsets.UTF_8);
		JsonObject response = YoutubeParsingHelper.getJsonPostResponse("comment/create_comment", body, localization);
		if (response.has("error")) {
			throw new IOException("YouTube refused the comment: "
							+ response.getObject("error").getString("message", "no reason given"));
		}
		String status = response.getObject("actionResult").getString("status");
		boolean created = "STATUS_SUCCEEDED".equals(status)
						|| findObject(response, "createCommentAction", 0) != null
						|| findObject(response, "commentEntityPayload", 0) != null;
		if (!created) throw new IOException("YouTube did not confirm the comment (status " + status + ")");
		return new Comment(commentAuthor(response), null, text, null, 0, false);
	}

	@NonNull
	private static String commentAuthor(@NonNull JsonObject response) {
		JsonObject entity = findObject(response, "commentEntityPayload", 0);
		if (entity != null) {
			String name = entity.getObject("author").getString("displayName");
			if (name != null && !name.isBlank()) return name;
		}
		JsonObject renderer = findObject(response, "commentRenderer", 0);
		if (renderer != null) {
			String name = renderer.getObject("authorText").getString("simpleText");
			if (name != null && !name.isBlank()) return name;
		}
		return "";
	}

	@Nullable
	private static JsonObject findObject(@Nullable Object node, @NonNull String key, int depth) {
		if (depth > 32) return null;
		if (node instanceof JsonObject object) {
			Object direct = object.get(key);
			if (direct instanceof JsonObject found) return found;
			for (Object value : object.values()) {
				JsonObject found = findObject(value, key, depth + 1);
				if (found != null) return found;
			}
		} else if (node instanceof JsonArray array) {
			for (Object value : array) {
				JsonObject found = findObject(value, key, depth + 1);
				if (found != null) return found;
			}
		}
		return null;
	}

	/**
	 * A running request that can be cancelled.
	 */
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
			FeedItem.sortByUploadDate(page.items());
			if (signedIn) rememberSubscribed(page.items());
			return page;
		});
	}

	/**
	 * Home without followed or subscribed channels (that's what Subscriptions is for), with at most
	 * two videos per channel. Topped up with related videos when too little is left.
	 */
	@NonNull
	private FeedPage homePage(@Nullable String continuation, boolean signedIn)
					throws IOException, ExtractionException {
		Set<String> known = knownChannels();
		if (continuation != null && continuation.startsWith(DISCOVER_PREFIX)) {
			// Past YouTube's own pages: keep following related videos
			List<String> seeds = List.of(continuation.substring(DISCOVER_PREFIX.length()).split(","));
			List<FeedItem> more = discover(known, seeds);
			return new FeedPage(more, more.isEmpty() ? null : discoverToken(more), signedIn);
		}
		FeedPage page = browsePage(Feed.HOME, continuation, null);
		if (page.items().isEmpty() && !signedIn && continuation == null) {
			// Signed out, Home can be empty without visitor data; retry with it
			String visitor = visitorData();
			if (visitor != null) page = browsePage(Feed.HOME, null, visitor);
		}
		List<FeedItem> items = curate(page.items(), known);
		Object next = page.next();
		// The first page goes on screen with whatever it has; the list asks for more right after.
		// Later pages are topped up so scrolling doesn't stall on near-empty ones.
		int wanted = continuation == null ? 1 : HOME_MIN_PAGE;
		for (int i = 0; i < MAX_EXTRA_PAGES && items.size() < wanted && next instanceof String token; i++) {
			FeedPage more = browsePage(Feed.HOME, token, null);
			items.addAll(curate(more.items(), known));
			next = more.next();
		}
		if (continuation == null && items.isEmpty()) {
			Set<String> urls = new HashSet<>();
			for (FeedItem item : items) urls.add(item.url());
			for (FeedItem item : discover(known, List.of())) {
				if (urls.add(item.url())) items.add(item);
			}
			Collections.shuffle(items);
		}
		// After the last page, continue with related videos
		return new FeedPage(items, next != null ? next : discoverToken(items), page.signedIn());
	}

	@NonNull
	private static String discoverToken(@NonNull List<FeedItem> shown) {
		List<String> ids = new ArrayList<>();
		for (FeedItem item : shown) {
			if (item.videoId() != null) ids.add(item.videoId());
		}
		Collections.shuffle(ids);
		return DISCOVER_PREFIX + String.join(",", ids.subList(0, Math.min(ids.size(), DISCOVER_SEEDS)));
	}

	/** Removes known channels and allows at most MAX_PER_CHANNEL videos per channel. */
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
		return browsePage(feed, continuation, visitorData, new Parser());
	}

	@NonNull
	private static FeedPage browsePage(@NonNull Feed feed, @Nullable String continuation,
	                                   @Nullable String visitorData, @NonNull Parser parser)
					throws IOException, ExtractionException {
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

	private static final String KEY_SUBSCRIBED = "subscribed_channels";
	private static final int MAX_REMEMBERED = 1500;
	private static final int MAX_PER_CHANNEL = 2;
	private static final int HOME_MIN_PAGE = 6;
	private static final int MAX_EXTRA_PAGES = 2;
	/** Prefix of our own Home continuation token, followed by seed video ids. */
	private static final String DISCOVER_PREFIX = "testtube:discover:";
	private static final int DISCOVER_SEEDS = 3;
	private static final int DISCOVER_TARGET = 40;
	private static final String TAG = "FeedClient";
	/** Max wait for channel feeds on the first page; slower ones show their saved videos until the next load. */
	private static final long FEED_WAIT_MS = 9_000L;
	private static final int RELATED_LOOKUPS = 4;
	private static final long FALLBACK_WAIT_MS = 8_000L;
	private static final int MAX_FALLBACK_CHANNELS = 6;
	/**
	 * How long a followed channel's videos count as fresh, like a feed reader's polling interval.
	 * Kept across restarts, so opening Subscriptions doesn't fetch every channel again.
	 */
	private static final long CHANNEL_CACHE_MS = 15 * 60_000L;
	/** Older entries are still shown while the fresh list loads, or when it can't. */
	private static final long CHANNEL_CACHE_KEEP_MS = 7L * 24 * 60 * 60_000L;
	private static final String CHANNEL_STORE_ID = "testtube_channel_feeds";
	private static final long OLDER_BUDGET_MS = 25_000L;
	private static final int MAX_LOCAL_VIDEOS = 120;
	private static final int SHORTS_CHANNELS_PER_PAGE = 4;
	private static final int SHORTS_QUERIES_PER_PAGE = 2;
	private static final int SHORTS_PER_CHANNEL = 6;
	private static final long SHORTS_WAIT_MS = 10_000L;
	/** Few channels at a time; the requests are background requests for the pacer as well. */
	private static final ExecutorService FEED_POOL = Executors.newFixedThreadPool(4, task -> {
		Thread thread = new Thread(task, "channel-feed");
		thread.setDaemon(true);
		return thread;
	});

	/** Random picks from recent history: video ids for related lookups and titles to search for. */
	public record Taste(@NonNull List<String> videoIds, @NonNull List<String> titles) {
	}

	@NonNull
	private volatile Supplier<Taste> taste = () -> new Taste(List.of(), List.of());

	public void setTaste(@NonNull Supplier<Taste> taste) {
		this.taste = taste;
	}

	@NonNull
	private volatile Supplier<List<String>> generalQueries = List::of;

	public void setGeneralQueries(@NonNull Supplier<List<String>> queries) {
		this.generalQueries = queries;
	}

	/**
	 * Related videos of the seeds (or of recent history) without known channels, shuffled. Fills
	 * Home when YouTube's own feed is empty, mostly known channels, or exhausted.
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
				// Skip failed lookups
			}
		}
		List<String> queries = new ArrayList<>();
		for (String title : current.titles()) queries.add(firstWords(title, 5));
		if (pool.size() < DISCOVER_TARGET) {
			List<String> general = new ArrayList<>(generalQueries.get());
			Collections.shuffle(general);
			queries.addAll(general.subList(0, Math.min(general.size(), 3)));
		}
		for (String query : queries) {
			try {
				pool.addAll(searchVideos(query));
			} catch (IOException | ExtractionException ignored) {
				// Ignore, the other queries may still work
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

	/**
	 * @param time     upload time; {@link #UNKNOWN_TIME} until placed among the channel's other videos
	 * @param dateText YouTube's own wording ("2 days ago") for videos from the channel page, where
	 *                 the time is only an estimate; null for feed entries, which have exact times
	 */
	private record RssVideo(@NonNull String id, @NonNull String title, @NonNull String author,
	                        @NonNull String channelId, long views, long time, boolean shortForm,
	                        @Nullable String dateText) {
		RssVideo withTime(long newTime) {
			return new RssVideo(id, title, author, channelId, views, newTime, shortForm, dateText);
		}
	}

	private static final long UNKNOWN_TIME = Long.MIN_VALUE;

	/**
	 * Paging state per followed channel (channels without older uploads are left out), and every
	 * video loaded so far: an older page from one channel can still be newer than the oldest
	 * videos of another, so each page sorts the lot again.
	 */
	private record LocalCursor(@NonNull Map<String, ChannelCursor> channels, @NonNull List<RssVideo> loaded) {
	}

	private record ChannelCursor(@Nullable ListLinkHandler tab, @Nullable Page page, @NonNull String name) {
	}

	/**
	 * Latest uploads of the locally followed channels, newest first. Next pages continue with
	 * older uploads.
	 *
	 * @param continuation {@code next} of the previous page, or null
	 */
	@NonNull
	public Call localSubscriptions(@NonNull List<LocalSubscriptions.Channel> channels,
	                               @Nullable Object continuation) {
		return localSubscriptions(channels, continuation, false, null);
	}

	/**
	 * Same as {@link #localSubscriptions(List, Object)}, but on the first page {@code partial} gets
	 * the whole list again each time more channels answered (on a worker thread).
	 *
	 * @param force load every channel now (pull to refresh) instead of using recent results
	 */
	@NonNull
	public Call localSubscriptions(@NonNull List<LocalSubscriptions.Channel> channels,
	                               @Nullable Object continuation, boolean force,
	                               @Nullable Consumer<List<FeedItem>> partial) {
		return run(signedIn -> continuation instanceof LocalCursor cursor
						? olderLocalVideos(cursor)
						: loadLocalFeed(channels, force, partial));
	}

	private record CachedChannel(long loadedAt, @NonNull List<RssVideo> videos) {
	}

	/** Recent results per channel, so reopening Subscriptions doesn't refetch everything. */
	private static final Map<String, CachedChannel> CHANNEL_CACHE = new ConcurrentHashMap<>();
	private static final Gson CACHE_GSON = new Gson();

	@Nullable
	private static CachedChannel cachedChannel(@NonNull String channelId) {
		CachedChannel cached = CHANNEL_CACHE.get(channelId);
		if (cached != null) return cached;
		try {
			String json = MMKV.mmkvWithID(CHANNEL_STORE_ID).decodeString(channelId, null);
			if (json == null) return null;
			cached = CACHE_GSON.fromJson(json, CachedChannel.class);
		} catch (RuntimeException e) {
			return null;
		}
		if (cached == null || cached.videos() == null
						|| System.currentTimeMillis() - cached.loadedAt() > CHANNEL_CACHE_KEEP_MS) {
			return null;
		}
		CHANNEL_CACHE.put(channelId, cached);
		return cached;
	}

	@NonNull
	private static List<RssVideo> channelVideos(@NonNull LocalSubscriptions.Channel channel)
					throws IOException, XmlPullParserException, ExtractionException {
		List<RssVideo> videos = fetchChannelFeed(channel);
		if (!videos.isEmpty()) {
			CachedChannel cached = new CachedChannel(System.currentTimeMillis(), videos);
			CHANNEL_CACHE.put(channel.id(), cached);
			try {
				MMKV.mmkvWithID(CHANNEL_STORE_ID).encode(channel.id(), CACHE_GSON.toJson(cached));
			} catch (RuntimeException ignored) {
				// Only a cache
			}
		}
		return videos;
	}

	@NonNull
	private static FeedPage loadLocalFeed(@NonNull List<LocalSubscriptions.Channel> channels, boolean force,
	                                      @Nullable Consumer<List<FeedItem>> partial) throws IOException {
		long start = System.currentTimeMillis();
		// Freshly loaded videos per channel; channels that haven't answered show their saved ones
		Map<String, List<RssVideo>> fresh = new LinkedHashMap<>();
		Map<String, List<RssVideo>> saved = new HashMap<>();
		ExecutorCompletionService<List<RssVideo>> done = new ExecutorCompletionService<>(FEED_POOL);
		Map<Future<List<RssVideo>>, LocalSubscriptions.Channel> pending = new HashMap<>();
		for (LocalSubscriptions.Channel channel : channels) {
			CachedChannel cached = cachedChannel(channel.id());
			if (cached != null && !force && start - cached.loadedAt() < CHANNEL_CACHE_MS) {
				fresh.put(channel.id(), cached.videos());
			} else {
				if (cached != null) saved.put(channel.id(), cached.videos());
				pending.put(done.submit(RequestPacer.inBackground(() -> channelVideos(channel))), channel);
			}
		}
		if (partial != null && (!fresh.isEmpty() || !saved.isEmpty())) {
			partial.accept(toFeedItems(merge(fresh, saved), MAX_LOCAL_VIDEOS));
		}
		// Every answer updates the whole list, newest first, so slow channels just slot in
		List<LocalSubscriptions.Channel> failed = new ArrayList<>();
		long limit = start + FEED_WAIT_MS;
		while (!pending.isEmpty()) {
			boolean changed = false;
			try {
				Future<List<RssVideo>> first = done.poll(Math.max(0L, limit - System.currentTimeMillis()),
								TimeUnit.MILLISECONDS);
				// The rest keeps loading in the background and ends up in the cache
				if (first == null) break;
				for (Future<List<RssVideo>> ready = first; ready != null; ready = done.poll()) {
					LocalSubscriptions.Channel channel = pending.remove(ready);
					if (channel == null) continue;
					try {
						List<RssVideo> videos = ready.get();
						if (videos.isEmpty()) {
							failed.add(channel);
						} else {
							fresh.put(channel.id(), videos);
							changed = true;
						}
					} catch (ExecutionException e) {
						Log.w(TAG, "channel feed failed", e);
						failed.add(channel);
					}
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}
			if (partial != null && changed) partial.accept(toFeedItems(merge(fresh, saved), MAX_LOCAL_VIDEOS));
		}
		// Retry failed or empty feeds through the extractor, in parallel
		if (!failed.isEmpty()) {
			Map<LocalSubscriptions.Channel, Future<List<RssVideo>>> fallbacks = new LinkedHashMap<>();
			for (LocalSubscriptions.Channel channel : failed.subList(0, Math.min(failed.size(), MAX_FALLBACK_CHANNELS))) {
				fallbacks.put(channel, FEED_POOL.submit(RequestPacer.inBackground(() -> videosFromExtractor(channel))));
			}
			long until = System.currentTimeMillis() + FALLBACK_WAIT_MS;
			for (Map.Entry<LocalSubscriptions.Channel, Future<List<RssVideo>>> fallback : fallbacks.entrySet()) {
				try {
					List<RssVideo> videos = fallback.getValue().get(Math.max(1L, until - System.currentTimeMillis()),
									TimeUnit.MILLISECONDS);
					if (!videos.isEmpty()) fresh.put(fallback.getKey().id(), videos);
				} catch (InterruptedException | ExecutionException | TimeoutException e) {
					Log.w(TAG, "extractor fallback failed", e);
				}
			}
		}
		List<RssVideo> all = merge(fresh, saved);
		// Nothing loaded at all: report an error rather than an empty list
		if (all.isEmpty() && !channels.isEmpty()) throw new IOException("no followed channel could be loaded");
		Map<String, ChannelCursor> older = new LinkedHashMap<>();
		for (LocalSubscriptions.Channel channel : channels) {
			older.put(channel.id(), new ChannelCursor(null, null, channel.name()));
		}
		return new FeedPage(toFeedItems(new ArrayList<>(all), MAX_LOCAL_VIDEOS), new LocalCursor(older, all), true, true);
	}

	/** The fresh videos, plus the saved ones of channels that haven't answered (yet). */
	@NonNull
	private static List<RssVideo> merge(@NonNull Map<String, List<RssVideo>> fresh,
	                                    @NonNull Map<String, List<RssVideo>> saved) {
		List<RssVideo> out = new ArrayList<>();
		for (List<RssVideo> videos : fresh.values()) out.addAll(videos);
		for (Map.Entry<String, List<RssVideo>> entry : saved.entrySet()) {
			if (!fresh.containsKey(entry.getKey())) out.addAll(entry.getValue());
		}
		return out;
	}

	/**
	 * Gives videos without a readable date a time from their neighbours in the channel's own
	 * order (newest first): just above the next older one, or just below the newer one before.
	 * Inventing "now" put two-day-old videos at the top of Subscriptions.
	 *
	 * @param newerThan time of the channel's oldest video already known, or {@link #UNKNOWN_TIME}
	 */
	@NonNull
	private static List<RssVideo> placeUndated(@NonNull List<RssVideo> channelOrder, long newerThan) {
		List<RssVideo> out = new ArrayList<>(channelOrder);
		int i = 0;
		while (i < out.size()) {
			if (out.get(i).time() != UNKNOWN_TIME) {
				i++;
				continue;
			}
			// A run of undated videos between a newer and an older dated one (either may be missing)
			int end = i;
			while (end < out.size() && out.get(end).time() == UNKNOWN_TIME) end++;
			long newer = i > 0 ? out.get(i - 1).time() : newerThan;
			long older = end < out.size() ? out.get(end).time() : UNKNOWN_TIME;
			int count = end - i;
			for (int r = 0; r < count; r++) {
				long time;
				if (newer != UNKNOWN_TIME && older != UNKNOWN_TIME) {
					time = newer - (newer - older) * (r + 1) / (count + 1);
				} else if (older != UNKNOWN_TIME) {
					time = older + 60_000L * (count - r);
				} else if (newer != UNKNOWN_TIME) {
					time = newer - 60_000L * (r + 1);
				} else {
					// Nothing to go by at all: the bottom, not the top
					time = -60_000L * r;
				}
				out.set(i + r, out.get(i + r).withTime(time));
			}
			i = end;
		}
		return out;
	}

	@NonNull
	private static List<FeedItem> toFeedItems(@NonNull List<RssVideo> videos, int limit) {
		videos.sort((a, b) -> Long.compare(b.time(), a.time()));
		long now = System.currentTimeMillis();
		boolean shorts = AppSettings.showShorts();
		List<FeedItem> items = new ArrayList<>();
		for (RssVideo video : videos) {
			if (items.size() >= limit) break;
			if (video.shortForm() && !shorts) continue;
			// A channel's own feed has the title and the channel name as the creator wrote them
			Originals.get().remember(video.id(), video.title(), video.author());
			items.add(new FeedItem(FeedItem.Kind.VIDEO, watchUrl(video.id()), video.id(), video.title(),
							video.author(), mobile("https://www.youtube.com/channel/" + video.channelId()),
							FeedItem.thumbnailFor(video.id()), -1, video.views(),
							video.dateText() != null ? video.dateText()
											: DateUtils.getRelativeTimeSpanString(video.time(), now, DateUtils.MINUTE_IN_MILLIS,
											DateUtils.FORMAT_ABBREV_RELATIVE).toString(), false, video.shortForm(), null));
		}
		return items;
	}

	private record OlderRound(@NonNull String channelId, @Nullable ChannelCursor next,
	                          @NonNull List<RssVideo> videos) {
	}

	/**
	 * The next batch of older uploads, one page per followed channel, newest first.
	 */
	@NonNull
	private static FeedPage olderLocalVideos(@NonNull LocalCursor cursor) {
		Map<String, Future<OlderRound>> pending = new LinkedHashMap<>();
		for (Map.Entry<String, ChannelCursor> entry : cursor.channels().entrySet()) {
			pending.put(entry.getKey(), FEED_POOL.submit(RequestPacer.inBackground(
							() -> olderRound(entry.getKey(), entry.getValue()))));
		}
		long deadline = System.currentTimeMillis() + OLDER_BUDGET_MS;
		Map<String, ChannelCursor> next = new LinkedHashMap<>();
		List<RssVideo> videos = new ArrayList<>(cursor.loaded());
		Set<String> known = new HashSet<>();
		for (RssVideo video : videos) known.add(video.id());
		for (Map.Entry<String, Future<OlderRound>> entry : pending.entrySet()) {
			try {
				OlderRound round = entry.getValue().get(Math.max(1L, deadline - System.currentTimeMillis()),
								TimeUnit.MILLISECONDS);
				// Below the channel's oldest video so far; the feed's exact times win over estimates
				long oldest = UNKNOWN_TIME;
				for (RssVideo video : videos) {
					if (video.channelId().equals(entry.getKey()) && (oldest == UNKNOWN_TIME || video.time() < oldest)) {
						oldest = video.time();
					}
				}
				for (RssVideo video : placeUndated(round.videos(), oldest)) {
					if (known.add(video.id())) videos.add(video);
				}
				if (round.next() != null) next.put(round.channelId(), round.next());
			} catch (TimeoutException e) {
				// Timed out, retry this channel on the next page
				entry.getValue().cancel(true);
				next.put(entry.getKey(), cursor.channels().get(entry.getKey()));
			} catch (InterruptedException | ExecutionException e) {
				Log.w(TAG, "older uploads failed id=" + entry.getKey(), e);
			}
		}
		return new FeedPage(toFeedItems(new ArrayList<>(videos), Integer.MAX_VALUE),
						next.isEmpty() ? null : new LocalCursor(next, videos), true, true);
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
			AvatarCache.get().remember("https://www.youtube.com/channel/" + channelId, avatar(info.getAvatars()));
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
		List<RssVideo> videos = new ArrayList<>();
		for (InfoItem item : items) {
			if (!(item instanceof StreamInfoItem stream) || stream.isShortFormContent()) continue;
			String id = YoutubeExtractor.getVideoId(stream.getUrl());
			if (id == null) continue;
			videos.add(new RssVideo(id, stream.getName(), name, channelId, stream.getViewCount(), timeOf(stream), false,
							stream.getTextualUploadDate()));
		}
		ChannelCursor next = nextPage != null && Page.isValid(nextPage) ? new ChannelCursor(tab, nextPage, name) : null;
		return new OlderRound(channelId, next, videos);
	}

	/** When a channel-page video went up; live right now counts as now. */
	private static long timeOf(@NonNull StreamInfoItem stream) {
		if (stream.getStreamType() == StreamType.LIVE_STREAM || stream.getStreamType() == StreamType.AUDIO_LIVE_STREAM) {
			return System.currentTimeMillis();
		}
		return stream.getUploadDate() != null
						? stream.getUploadDate().offsetDateTime().toInstant().toEpochMilli()
						: UNKNOWN_TIME;
	}

	@NonNull
	private static List<RssVideo> videosFromExtractor(@NonNull LocalSubscriptions.Channel channel)
					throws IOException, ExtractionException {
		ChannelInfo info = ChannelInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/channel/" + channel.id());
		AvatarCache.get().remember("https://www.youtube.com/channel/" + channel.id(), avatar(info.getAvatars()));
		List<RssVideo> out = new ArrayList<>();
		for (ListLinkHandler tab : info.getTabs()) {
			if (!tab.getContentFilters().contains(ChannelTabs.VIDEOS)) continue;
			for (InfoItem item : ChannelTabInfo.getInfo(ServiceList.YouTube, tab).getRelatedItems()) {
				if (!(item instanceof StreamInfoItem stream) || stream.isShortFormContent()) continue;
				String id = YoutubeExtractor.getVideoId(stream.getUrl());
				if (id == null) continue;
				out.add(new RssVideo(id, stream.getName(), info.getName(), channel.id(), stream.getViewCount(),
								timeOf(stream), false, stream.getTextualUploadDate()));
				if (out.size() >= 10) break;
			}
			break;
		}
		LocalSubscriptions.get().rename(channel.id(), info.getName());
		return placeUndated(out, UNKNOWN_TIME);
	}

	/**
	 * A channel's RSS feed, through the same client, cookies, identity and pacing as every other
	 * YouTube request (it used to be a separate connection with its own headers).
	 */
	@NonNull
	private static List<RssVideo> fetchChannelFeed(@NonNull LocalSubscriptions.Channel channel)
					throws IOException, XmlPullParserException, ExtractionException {
		// Never from the HTTP cache: a 15 minute old feed misses the video that just went up
		org.schabi.newpipe.extractor.downloader.Response response = NewPipe.getDownloader()
						.get("https://www.youtube.com/feeds/videos.xml?channel_id=" + channel.id(),
										Map.of("Cache-Control", List.of("no-cache")));
		if (response.responseCode() != 200) {
			throw new IOException("channel feed answered HTTP " + response.responseCode());
		}
		return parseChannelFeed(new StringReader(response.responseBody()), channel);
	}

	@NonNull
	private static List<RssVideo> parseChannelFeed(@NonNull Reader reader,
	                                               @NonNull LocalSubscriptions.Channel channel)
					throws IOException, XmlPullParserException {
		XmlPullParser parser = Xml.newPullParser();
		parser.setInput(reader);
		List<RssVideo> out = new ArrayList<>();
		String feedAuthor = channel.name();
		boolean inEntry = false;
		boolean inAuthor = false;
		boolean shortForm = false;
		String id = null, title = null, author = null, published = null;
		long views = -1;
		for (int event = parser.getEventType(); event != XmlPullParser.END_DOCUMENT; event = parser.next()) {
			if (event == XmlPullParser.START_TAG) {
				String name = parser.getName();
				if ("entry".equals(name)) {
					inEntry = true;
					id = title = author = published = null;
					views = -1;
					shortForm = false;
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
						// Shorts link to /shorts/ instead of /watch
						case "link" -> {
							String href = parser.getAttributeValue(null, "href");
							if (href != null && href.contains("/shorts/")) shortForm = true;
						}
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
											views, time, shortForm, null));
						} catch (RuntimeException ignored) {
							// Skip entries with an unparseable date
						}
					}
				}
			}
		}
		LocalSubscriptions.get().rename(channel.id(), feedAuthor);
		return out;
	}

	/**
	 * Finds the channel behind a link, a handle (@name) or a channel id.
	 */
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
		AvatarCache.get().remember("https://www.youtube.com/channel/" + info.getId(), avatar(info.getAvatars()));
		return new LocalSubscriptions.Channel(info.getId(), info.getName() == null ? "" : info.getName());
	}

	/**
	 * Suggested videos for a video, straight from YouTube's "next" response.
	 */
	@NonNull
	public Call related(@NonNull String videoId) {
		return run(signedIn -> new FeedPage(relatedItems(videoId), null, signedIn));
	}

	/**
	 * More suggestions for the watch screen: related videos of a few random entries, minus the ones
	 * already shown.
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
			// Suggestions under the player stay without Shorts
			if (item.kind() == FeedItem.Kind.VIDEO && !item.shortForm() && !videoId.equals(item.videoId())) {
				out.add(item);
			}
		}
		return out;
	}

	/**
	 * Where the next page of the Shorts tab comes from.
	 *
	 * @param homeToken continuation of YouTube's Home feed; null with {@code homeDone} false means
	 *                  its first page
	 * @param channels  followed channels whose Shorts were not read yet
	 * @param queries   searches not made yet
	 */
	private record ShortsCursor(@Nullable String homeToken, boolean homeDone,
	                            @NonNull List<String> channels, @NonNull List<String> queries) {
		boolean exhausted() {
			return homeDone && channels.isEmpty() && queries.isEmpty();
		}
	}

	/**
	 * A page for the Shorts tab, mixed from YouTube's Home feed, the Shorts tabs of followed
	 * channels and searches for topics of the watch history.
	 *
	 * @param continuation {@code next} of the previous page, or null for the first page
	 */
	@NonNull
	public Call shorts(@Nullable Object continuation) {
		return run(signedIn -> shortsPage(
						continuation instanceof ShortsCursor cursor ? cursor : firstShortsCursor(), signedIn));
	}

	@NonNull
	private ShortsCursor firstShortsCursor() {
		List<String> channels = new ArrayList<>();
		for (LocalSubscriptions.Channel channel : LocalSubscriptions.get().all()) channels.add(channel.id());
		Collections.shuffle(channels);
		Taste current;
		try {
			current = taste.get();
		} catch (RuntimeException e) {
			current = new Taste(List.of(), List.of());
		}
		List<String> queries = new ArrayList<>();
		for (String title : current.titles()) queries.add(firstWords(title, 4));
		List<String> general = new ArrayList<>(generalQueries.get());
		Collections.shuffle(general);
		queries.addAll(general);
		return new ShortsCursor(null, false, channels, queries);
	}

	@NonNull
	private static FeedPage shortsPage(@NonNull ShortsCursor cursor, boolean signedIn) {
		List<String> channels = cursor.channels().subList(0, Math.min(cursor.channels().size(), SHORTS_CHANNELS_PER_PAGE));
		List<String> queries = cursor.queries().subList(0, Math.min(cursor.queries().size(), SHORTS_QUERIES_PER_PAGE));
		Future<FeedPage> home = cursor.homeDone() ? null : FEED_POOL.submit(() -> homeShorts(cursor.homeToken()));
		List<Future<List<FeedItem>>> sources = new ArrayList<>();
		for (String channelId : channels) sources.add(FEED_POOL.submit(() -> channelShorts(channelId)));
		for (String query : queries) sources.add(FEED_POOL.submit(() -> searchShorts(query)));

		long deadline = System.currentTimeMillis() + SHORTS_WAIT_MS;
		List<List<FeedItem>> lists = new ArrayList<>();
		String homeToken = null;
		if (home != null) {
			try {
				FeedPage page = home.get(Math.max(1L, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
				lists.add(onlyShorts(page.items()));
				if (page.next() instanceof String token) homeToken = token;
			} catch (InterruptedException | ExecutionException | TimeoutException e) {
				Log.w(TAG, "home shorts failed", e);
			}
		}
		for (Future<List<FeedItem>> source : sources) {
			try {
				lists.add(source.get(Math.max(1L, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS));
			} catch (InterruptedException | ExecutionException | TimeoutException e) {
				Log.w(TAG, "shorts source failed", e);
			}
		}
		ShortsCursor next = new ShortsCursor(homeToken, homeToken == null,
						new ArrayList<>(cursor.channels().subList(channels.size(), cursor.channels().size())),
						new ArrayList<>(cursor.queries().subList(queries.size(), cursor.queries().size())));
		return new FeedPage(interleave(lists), next.exhausted() ? null : next, signedIn);
	}

	@NonNull
	private static FeedPage homeShorts(@Nullable String token) throws IOException, ExtractionException {
		FeedPage page = browsePage(Feed.HOME, token, null, new Parser(true));
		if (token == null && page.items().isEmpty()) {
			// Signed out, Home can be empty without visitor data; retry with it
			String visitor = visitorData();
			if (visitor != null) page = browsePage(Feed.HOME, null, visitor, new Parser(true));
		}
		return page;
	}

	/** Takes one item from each list in turn, so no single source fills the screen. */
	@NonNull
	private static List<FeedItem> interleave(@NonNull List<List<FeedItem>> lists) {
		List<FeedItem> out = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		for (int index = 0; ; index++) {
			boolean any = false;
			for (List<FeedItem> list : lists) {
				if (index >= list.size()) continue;
				any = true;
				FeedItem item = list.get(index);
				if (item.videoId() != null && seen.add(item.videoId())) out.add(item);
			}
			if (!any) return out;
		}
	}

	@NonNull
	private static List<FeedItem> onlyShorts(@NonNull List<FeedItem> items) {
		List<FeedItem> out = new ArrayList<>();
		for (FeedItem item : items) {
			if (item.shortForm() && item.videoId() != null) out.add(item);
		}
		return out;
	}

	@NonNull
	private static List<FeedItem> channelShorts(@NonNull String channelId) throws IOException, ExtractionException {
		ChannelInfo info = ChannelInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/channel/" + channelId);
		AvatarCache.get().remember("https://www.youtube.com/channel/" + channelId, avatar(info.getAvatars()));
		for (ListLinkHandler tab : info.getTabs()) {
			if (!tab.getContentFilters().contains(ChannelTabs.SHORTS)) continue;
			List<FeedItem> out = new ArrayList<>();
			for (InfoItem item : ChannelTabInfo.getInfo(ServiceList.YouTube, tab).getRelatedItems()) {
				FeedItem converted = convert(item, true);
				if (converted != null && converted.shortForm()) out.add(converted);
				if (out.size() >= SHORTS_PER_CHANNEL) break;
			}
			return out;
		}
		return List.of();
	}

	/** Search results come with shelves of Shorts; "#shorts" makes sure there are some. */
	@NonNull
	private static List<FeedItem> searchShorts(@NonNull String query) throws IOException, ExtractionException {
		Localization localization = NewPipe.getPreferredLocalization();
		ContentCountry country = NewPipe.getPreferredContentCountry();
		byte[] body = JsonWriter.string(YoutubeParsingHelper.prepareDesktopJsonBuilder(localization, country)
						.value("query", query + " #shorts").done()).getBytes(StandardCharsets.UTF_8);
		JsonObject response = YoutubeParsingHelper.getJsonPostResponse("search", body, localization);
		Parser parser = new Parser(true);
		parser.walk(response, 0);
		return onlyShorts(parser.items);
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
	static FeedItem convert(@NonNull InfoItem item) {
		return convert(item, AppSettings.showShorts());
	}

	@Nullable
	private static FeedItem convert(@NonNull InfoItem item, boolean shorts) {
		if (item instanceof StreamInfoItem stream) {
			if (stream.isShortFormContent() && !shorts) return null;
			String videoId = YoutubeExtractor.getVideoId(stream.getUrl());
			if (videoId == null) return null;
			StreamType type = stream.getStreamType();
			return new FeedItem(FeedItem.Kind.VIDEO, watchUrl(videoId), videoId, stream.getName(),
							stream.getUploaderName(), mobile(stream.getUploaderUrl()), FeedItem.thumbnailFor(videoId),
							stream.getDuration(), stream.getViewCount(), stream.getTextualUploadDate(),
							isLive(type), stream.isShortFormContent(), avatar(stream.getUploaderAvatars()));
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

	/**
	 * The app opens YouTube pages on the mobile site.
	 */
	@Nullable
	public static String mobile(@Nullable String url) {
		if (url == null || url.isBlank()) return null;
		return url.replaceFirst("^https?://(www\\.)?youtube\\.com", Constant.HOME_URL);
	}

	/**
	 * Picks the smallest image that is still sharp in a list row, or the largest one.
	 */
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

	/**
	 * A channel's profile image for list rows, or null when there is none.
	 */
	@Nullable
	static String avatar(@Nullable List<Image> images) {
		if (images == null || images.isEmpty()) return null;
		Image largest = null;
		for (Image image : images) {
			if (image == null || image.getUrl() == null || image.getUrl().isBlank()) continue;
			if (largest == null || image.getWidth() > largest.getWidth()) largest = image;
		}
		return largest != null ? AvatarCache.rowSize(largest.getUrl()) : null;
	}

	/**
	 * Loads the profile image of a channel (blocking). Used for rows whose source has none, such
	 * as followed channels read from their public feeds.
	 */
	@Nullable
	public String channelAvatar(@NonNull String channelUrl) throws IOException, ExtractionException {
		String url = channelUrl.trim().replaceFirst("^https?://(m\\.|www\\.)?youtube\\.com", "https://www.youtube.com");
		if (!url.startsWith("https://www.youtube.com/")) return null;
		// Background: a missing channel picture must never hold up the list it belongs to
		ExtractionSession session = new ExtractionSession(auth.current(true)).setBackground(true);
		return downloader.withExtractionSession(() -> {
			ChannelInfo info = ChannelInfo.getInfo(ServiceList.YouTube, url);
			String avatar = avatar(info.getAvatars());
			// Rows may link to the channel by id instead of handle
			AvatarCache.get().remember("https://www.youtube.com/channel/" + info.getId(), avatar);
			return avatar;
		}, session);
	}

	/**
	 * A video's title and channel name as the creator wrote them. YouTube's oEmbed page gives
	 * them untranslated, where search and browse give them in the app's language. Runs as
	 * background work, so it waits for whatever the person is doing. Null when YouTube won't say
	 * (embedding off, removed).
	 */
	@Nullable
	public Originals.Original originals(@NonNull String videoId) throws IOException, ExtractionException {
		ExtractionSession session = new ExtractionSession(auth.current(true)).setBackground(true);
		return downloader.withExtractionSession(() -> {
			org.schabi.newpipe.extractor.downloader.Response response = NewPipe.getDownloader()
							.get("https://www.youtube.com/oembed?format=json&url=https%3A%2F%2Fwww.youtube.com%2Fwatch%3Fv%3D"
											+ videoId);
			if (response.responseCode() != 200) return null;
			try {
				JsonObject json = JsonParser.object().from(response.responseBody());
				String title = json.getString("title");
				if (title == null || title.isBlank()) return null;
				String author = json.getString("author_name");
				return new Originals.Original(title, author == null || author.isBlank() ? null : author);
			} catch (JsonParserException e) {
				throw new IOException("unreadable oEmbed answer", e);
			}
		}, session);
	}

	private static final class Parser {
		@NonNull
		final List<FeedItem> items = new ArrayList<>();
		@NonNull
		private final Set<String> seen = new HashSet<>();
		private final boolean shorts;
		@Nullable
		String continuation;

		Parser() {
			this(AppSettings.showShorts());
		}

		/** @param shorts whether Shorts are collected */
		Parser(boolean shorts) {
			this.shorts = shorts;
		}

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
				if (SKIPPED.contains(key) || isPromoKey(key) || (!shorts && SHORTS_KEYS.contains(key))
								|| !(value instanceof JsonObject || value instanceof JsonArray)) {
					continue;
				}
				switch (key) {
					case "videoRenderer", "gridVideoRenderer", "compactVideoRenderer" ->
									addVideo(new YoutubeStreamInfoItemExtractor((JsonObject) value, null));
					case "reelItemRenderer" -> addVideo(new YoutubeReelInfoItemExtractor((JsonObject) value));
					case "shortsLockupViewModel" ->
									addVideo(new YoutubeShortsLockupInfoItemExtractor((JsonObject) value));
					case "lockupViewModel" -> addLockup((JsonObject) value);
					case "continuationItemRenderer" -> readContinuation((JsonObject) value);
					default -> walk(value, depth + 1);
				}
			}
		}

		/**
		 * Ads, upsells, nudges and banners come under many names. Skip anything that looks like one,
		 * including its children.
		 */
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
				if (extractor.isAd()) return;
				boolean shortForm = extractor.isShortFormContent();
				if (shortForm && !shorts) return;
				String videoId = YoutubeExtractor.getVideoId(extractor.getUrl());
				if (videoId == null || !seen.add(videoId)) return;
				StreamType type = safeType(extractor);
				long duration = safeDuration(extractor);
				String published = safePublished(extractor);
				// No duration, not live, no views and no date: a promo, not a video. Shorts never
				// have a duration or date in lists, so they are exempt.
				if (!shortForm && duration <= 0 && !isLive(type) && safeViews(extractor) < 0
								&& (published == null || published.isBlank())) {
					return;
				}
				items.add(new FeedItem(FeedItem.Kind.VIDEO, watchUrl(videoId), videoId,
								extractor.getName(), safeUploader(extractor), mobile(safeUploaderUrl(extractor)),
								FeedItem.thumbnailFor(videoId), duration, safeViews(extractor),
								published, isLive(type), shortForm, safeAvatar(extractor)));
			} catch (Exception ignored) {
				// Skip malformed entries
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
				// Skip entries with an unexpected shape
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

		@Nullable
		private static String safeAvatar(@NonNull StreamInfoItemExtractor extractor) {
			try {
				return avatar(extractor.getUploaderAvatars());
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
