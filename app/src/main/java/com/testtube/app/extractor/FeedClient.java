package com.testtube.app.extractor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonWriter;
import com.testtube.app.Constant;

import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.services.youtube.InnertubeClientRequestInfo;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ListExtractor;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs;
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.channel.ChannelInfo;
import org.schabi.newpipe.extractor.channel.ChannelInfoItem;
import org.schabi.newpipe.extractor.comments.CommentsExtractor;
import org.schabi.newpipe.extractor.comments.CommentsInfoItem;
import org.schabi.newpipe.extractor.stream.Description;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.localization.ContentCountry;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem;
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItemExtractor;
import org.schabi.newpipe.extractor.search.SearchExtractor;
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper;
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeMixOrPlaylistLockupInfoItemExtractor;
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamInfoItemExtractor;
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamInfoItemLockupExtractor;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamInfoItemExtractor;
import org.schabi.newpipe.extractor.stream.StreamType;

import android.text.format.DateUtils;
import android.util.Log;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.OffsetDateTime;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.concurrent.TimeoutException;
import android.text.Html;

/**
 * Loads the Home feed, the subscriptions feed and search results directly from YouTube, signed
 * in with the account cookies, and turns them into {@link FeedItem}s. Shorts, posts and ads are
 * left out.
 */
public final class FeedClient {
	private static final int MAX_DEPTH = 48;
	private static final String LOCKUP_VIDEO = "LOCKUP_CONTENT_TYPE_VIDEO";
	private static final String LOCKUP_PLAYLIST = "LOCKUP_CONTENT_TYPE_PLAYLIST";
	private static final String LOCKUP_MIX = "LOCKUP_CONTENT_TYPE_MIX";
	/**
	 * Names of ad slots, upsells ("Upgrade to YouTube Premium"), nudges and banners.
	 */
	private static final Pattern PROMO_KEY = Pattern.compile(
					"^ad[A-Z]|Ad[A-Z]|(?i:promo|upsell|nudge|masthead|banner|sponsor|searchPyv)");

	/**
	 * Parts of the response that never hold feed videos, or hold content the app hides.
	 */
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
			FeedPage page = browsePage(feed, continuation, null);
			if (feed == Feed.SUBSCRIPTIONS) FeedItem.sortNewestFirst(page.items());
			if (page.items().isEmpty() && !signedIn && feed == Feed.HOME && continuation == null) {
				// Signed out, YouTube may return an empty Home without a visitor id: retry with one.
				String visitor = visitorData();
				if (visitor != null) page = browsePage(feed, null, visitor);
				// Still nothing: build a Home from searches, so the app works without an account.
				if (page.items().isEmpty()) page = guestHome();
			}
			return page;
		});
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

	private static final String[] GUEST_QUERIES = {"trending", "popular today", "new this week", "most watched"};
	private static final String TAG = "FeedClient";
	private static final long FALLBACK_BUDGET_MS = 30_000L;
	private static final int MAX_LOCAL_VIDEOS = 120;
	private static final ExecutorService FEED_POOL = Executors.newFixedThreadPool(6, task -> {
		Thread thread = new Thread(task, "channel-feed");
		thread.setDaemon(true);
		return thread;
	});

	/**
	 * Words to search for when building a Home without an account: channels and titles the person
	 * watched lately.
	 */
	@NonNull
	private volatile Supplier<List<String>> interests = List::of;

	public void setInterests(@NonNull Supplier<List<String>> interests) {
		this.interests = interests;
	}

	/**
	 * A Home for people who are not signed in: videos found by searching for what they watched
	 * lately, then for what is popular.
	 */
	@NonNull
	private FeedPage guestHome() {
		List<FeedItem> items = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		List<String> queries = new ArrayList<>();
		try {
			queries.addAll(interests.get());
		} catch (RuntimeException ignored) {
			// No history yet: the general searches below are enough.
		}
		Collections.addAll(queries, GUEST_QUERIES);
		for (String query : queries) {
			try {
				SearchExtractor extractor = ServiceList.YouTube.getSearchExtractor(query);
				extractor.fetchPage();
				for (InfoItem item : extractor.getInitialPage().getItems()) {
					FeedItem converted = convert(item);
					if (converted != null && converted.kind() == FeedItem.Kind.VIDEO && seen.add(converted.url())) {
						items.add(converted);
					}
				}
			} catch (Exception ignored) {
				// One failing search must not empty the whole Home.
			}
			if (items.size() >= 40) break;
		}
		Collections.shuffle(items);
		return new FeedPage(items, null, false);
	}

	private record RssVideo(@NonNull String id, @NonNull String title, @NonNull String author,
	                        @NonNull String channelId, long views, long time) {
	}

	/**
	 * The latest videos of the channels followed without an account, newest first.
	 */
	@NonNull
	public Call localSubscriptions(@NonNull List<LocalSubscriptions.Channel> channels) {
		return run(signedIn -> loadLocalFeed(channels));
	}

	@NonNull
	private static FeedPage loadLocalFeed(@NonNull List<LocalSubscriptions.Channel> channels) throws IOException {
		long start = System.currentTimeMillis();
		List<Future<List<RssVideo>>> pending = new ArrayList<>(channels.size());
		for (LocalSubscriptions.Channel channel : channels) {
			pending.add(FEED_POOL.submit(() -> fetchChannelFeed(channel)));
		}
		List<RssVideo> all = new ArrayList<>();
		List<LocalSubscriptions.Channel> failed = new ArrayList<>();
		for (int i = 0; i < pending.size(); i++) {
			try {
				List<RssVideo> videos = pending.get(i).get(40, TimeUnit.SECONDS);
				if (videos.isEmpty()) failed.add(channels.get(i));
				else all.addAll(videos);
			} catch (InterruptedException | ExecutionException | TimeoutException e) {
				Log.w(TAG, "channel feed failed id=" + channels.get(i).id(), e);
				failed.add(channels.get(i));
			}
		}
		// A channel whose feed did not answer, or came back empty, is read through the extractor.
		for (LocalSubscriptions.Channel channel : failed) {
			if (System.currentTimeMillis() - start > FALLBACK_BUDGET_MS) break;
			try {
				all.addAll(videosFromExtractor(channel));
			} catch (IOException | ExtractionException e) {
				Log.w(TAG, "extractor fallback failed id=" + channel.id(), e);
			}
		}
		// Channels are followed but nothing could be read: report it, an empty list would only mislead.
		if (all.isEmpty() && !channels.isEmpty()) throw new IOException("no followed channel could be loaded");
		all.sort((a, b) -> Long.compare(b.time(), a.time()));
		long now = System.currentTimeMillis();
		List<FeedItem> items = new ArrayList<>();
		for (RssVideo video : all) {
			if (items.size() >= MAX_LOCAL_VIDEOS) break;
			items.add(new FeedItem(FeedItem.Kind.VIDEO, watchUrl(video.id()), video.id(), video.title(),
							video.author(), mobile("https://www.youtube.com/channel/" + video.channelId()),
							FeedItem.thumbnailFor(video.id()), -1, video.views(),
							DateUtils.getRelativeTimeSpanString(video.time(), now, DateUtils.MINUTE_IN_MILLIS,
											DateUtils.FORMAT_ABBREV_RELATIVE).toString(), false));
		}
		return new FeedPage(items, null, true);
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
				// Without an exact date, keep the channel's own order, newest first.
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
		connection.setConnectTimeout(8_000);
		connection.setReadTimeout(12_000);
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
							// An entry with an unreadable date is skipped.
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
		return new LocalSubscriptions.Channel(info.getId(), info.getName() == null ? "" : info.getName());
	}

	/**
	 * Suggested videos for a video, straight from YouTube's "next" response.
	 */
	@NonNull
	public Call related(@NonNull String videoId) {
		return run(signedIn -> {
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
			return new FeedPage(out, null, signedIn);
		});
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
	 * Walks a browse response and collects the videos, whatever layout YouTube wraps them in.
	 */
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

		/**
		 * Ad slots, upsells ("Upgrade to YouTube Premium"), nudges and banners are named in many
		 * ways; anything that looks like one is left out together with everything inside it.
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
				if (extractor.isAd() || extractor.isShortFormContent()) return;
				String videoId = YoutubeExtractor.getVideoId(extractor.getUrl());
				if (videoId == null || !seen.add(videoId)) return;
				StreamType type = safeType(extractor);
				long duration = safeDuration(extractor);
				String published = safePublished(extractor);
				// A real video has a length, or is live or announced; a bare title with none of
				// that is a promotion dressed up as a video.
				if (duration <= 0 && !isLive(type) && safeViews(extractor) < 0
								&& (published == null || published.isBlank())) {
					return;
				}
				items.add(new FeedItem(FeedItem.Kind.VIDEO, watchUrl(videoId), videoId,
								extractor.getName(), safeUploader(extractor), mobile(safeUploaderUrl(extractor)),
								FeedItem.thumbnailFor(videoId), duration, safeViews(extractor),
								published, isLive(type)));
			} catch (Exception ignored) {
				// One malformed entry must not drop the whole page.
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
				// Skip entries YouTube changed the shape of.
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
