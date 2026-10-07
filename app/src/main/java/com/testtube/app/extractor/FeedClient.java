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
import org.schabi.newpipe.extractor.ServiceList;
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

import java.io.IOException;
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
import java.util.concurrent.Executor;

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
				content = android.text.Html.fromHtml(content, android.text.Html.FROM_HTML_MODE_COMPACT).toString().trim();
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

	/**
	 * A Home for people who are not signed in: popular videos found by searching.
	 */
	@NonNull
	private static FeedPage guestHome() {
		List<FeedItem> items = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		for (String query : GUEST_QUERIES) {
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
							stream.getUploaderName(), mobile(stream.getUploaderUrl()), videoThumbnail(videoId),
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

	@NonNull
	private static String videoThumbnail(@NonNull String videoId) {
		// 480x360 exists for every video and is cropped to 16:9 by the list.
		return "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg";
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
				if (SKIPPED.contains(key) || !(value instanceof JsonObject || value instanceof JsonArray)) {
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
				items.add(new FeedItem(FeedItem.Kind.VIDEO, watchUrl(videoId), videoId,
								extractor.getName(), safeUploader(extractor), mobile(safeUploaderUrl(extractor)),
								videoThumbnail(videoId), safeDuration(extractor), safeViews(extractor),
								safePublished(extractor), isLive(type)));
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
