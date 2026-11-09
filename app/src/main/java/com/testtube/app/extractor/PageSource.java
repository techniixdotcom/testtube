package com.testtube.app.extractor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.testtube.app.AppSettings;

import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ListExtractor;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.channel.ChannelExtractor;
import org.schabi.newpipe.extractor.channel.ChannelInfo;
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo;
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.playlist.PlaylistInfo;
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeChannelExtractor;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Channel and playlist pages: header plus a tab's rows, page by page. */
public final class PageSource {
	public enum Kind {
		CHANNEL,
		PLAYLIST
	}

	/** Channel tabs we show, in order. Shorts only if enabled. No Playlists tab. */
	private static final List<String> TAB_ORDER = List.of(ChannelTabs.VIDEOS, ChannelTabs.SHORTS,
					ChannelTabs.LIVESTREAMS, ChannelTabs.PODCASTS, ChannelTabs.COURSES, ChannelTabs.ALBUMS);

	/** Narrower banners look blurry across a phone. */
	private static final int BANNER_MIN_WIDTH = 1060;

	/**
	 * A channel page tab.
	 * @see ChannelTabs
	 */
	public static final class Tab {
		@NonNull
		private final String id;
		@NonNull
		final ListLinkHandler handler;

		Tab(@NonNull String id, @NonNull ListLinkHandler handler) {
			this.id = id;
			this.handler = handler;
		}

		/** One of the {@link ChannelTabs} names, e.g. {@link ChannelTabs#VIDEOS}. */
		@NonNull
		public String id() {
			return id;
		}
	}

	/**
	 * @param subtitle   uploader for playlists, null for channels
	 * @param count      subs for channels, videos for playlists; -1 if unknown
	 * @param handle     "@name" or null
	 * @param stats      counts in YouTube's words ("465K subscribers", "108 videos"), empty if unknown
	 * @param channelId  UC... id, null for playlists
	 * @param tabs       channel tabs, empty for playlists
	 */
	public record Header(@NonNull Kind kind,
	                     @NonNull String title,
	                     @Nullable String subtitle,
	                     long count,
	                     @Nullable String avatarUrl,
	                     @Nullable String description,
	                     @Nullable String playlistId,
	                     @Nullable String bannerUrl,
	                     @Nullable String handle,
	                     @NonNull List<String> stats,
	                     boolean verified,
	                     @Nullable String channelId,
	                     @NonNull List<Tab> tabs) {
	}

	/** Where a list's next page comes from. */
	public static final class Cursor {
		@NonNull
		final Kind kind;
		@Nullable
		final String playlistUrl;
		@Nullable
		final ListLinkHandler tab;
		@NonNull
		final Page page;

		Cursor(@NonNull Kind kind, @Nullable String playlistUrl, @Nullable ListLinkHandler tab, @NonNull Page page) {
			this.kind = kind;
			this.playlistUrl = playlistUrl;
			this.tab = tab;
			this.page = page;
		}
	}

	/**
	 * @param header null for later pages and tabs
	 * @param next   null at the end
	 * @param tab    channel tab id the rows belong to, null for playlists
	 */
	public record Result(@Nullable Header header, @NonNull List<FeedItem> items, @Nullable Cursor next,
	                     @Nullable String tab) {
	}

	@NonNull
	private final DownloaderImpl downloader;
	@NonNull
	private final NativeAuth auth;
	@NonNull
	private final Executor executor;

	public PageSource(@NonNull DownloaderImpl downloader, @NonNull NativeAuth auth, @NonNull Executor executor) {
		this.downloader = downloader;
		this.auth = auth;
		this.executor = executor;
	}

	@FunctionalInterface
	private interface Load {
		@NonNull
		Result run() throws IOException, ExtractionException;
	}

	/** Header and first rows of a channel (first tab) or playlist. */
	@NonNull
	public CompletableFuture<Result> open(@NonNull Kind kind, @NonNull String url) {
		String desktopUrl = url.replaceFirst("^https?://(m\\.|www\\.)?youtube\\.com", "https://www.youtube.com");
		return run(() -> kind == Kind.CHANNEL ? channel(desktopUrl) : playlist(desktopUrl));
	}

	/** First rows of another channel tab. */
	@NonNull
	public CompletableFuture<Result> openTab(@NonNull Tab tab) {
		return run(() -> tabPage(tab));
	}

	@NonNull
	public CompletableFuture<Result> more(@NonNull Cursor cursor) {
		return run(() -> {
			if (cursor.kind == Kind.PLAYLIST && cursor.playlistUrl != null) {
				ListExtractor.InfoItemsPage<StreamInfoItem> page =
								PlaylistInfo.getMoreItems(ServiceList.YouTube, cursor.playlistUrl, cursor.page);
				return new Result(null, items(page.getItems()), nextOf(cursor.kind, cursor.playlistUrl, null,
								page.hasNextPage() ? page.getNextPage() : null), null);
			}
			if (cursor.tab == null) return new Result(null, List.of(), null, null);
			ListExtractor.InfoItemsPage<InfoItem> page =
							ChannelTabInfo.getMoreItems(ServiceList.YouTube, cursor.tab, cursor.page);
			return new Result(null, items(page.getItems()), nextOf(cursor.kind, null, cursor.tab,
							page.hasNextPage() ? page.getNextPage() : null), tabId(cursor.tab));
		});
	}

	@NonNull
	private CompletableFuture<Result> run(@NonNull Load load) {
		CompletableFuture<Result> future = new CompletableFuture<>();
		try {
			executor.execute(() -> {
				if (future.isDone()) return;
				try {
					ExtractionSession session = new ExtractionSession(auth.current(true));
					future.complete(downloader.withExtractionSession(load::run, session));
				} catch (Throwable error) {
					future.completeExceptionally(error);
				}
			});
		} catch (RuntimeException rejected) {
			future.completeExceptionally(rejected);
		}
		return future;
	}

	@NonNull
	private static Result channel(@NonNull String url) throws IOException, ExtractionException {
		ChannelExtractor extractor = ServiceList.YouTube.getChannelExtractor(url);
		extractor.fetchPage();
		ChannelInfo info = ChannelInfo.getInfo(extractor);
		String handle = null;
		List<String> stats = List.of();
		if (extractor instanceof YoutubeChannelExtractor youtube) {
			handle = youtube.getChannelHandle();
			stats = youtube.getHeaderCountTexts();
		}
		List<Tab> tabs = tabs(info.getTabs());
		String avatar = largest(info.getAvatars());
		String channelUrl = "https://www.youtube.com/channel/" + info.getId();
		AvatarCache.get().remember(channelUrl, avatar);
		AvatarCache.get().remember(url, avatar);
		Header header = new Header(Kind.CHANNEL, info.getName(), null, info.getSubscriberCount(),
						avatar, info.getDescription(), null, banner(info.getBanners()), handle, stats,
						info.isVerified(), info.getId(), tabs);
		if (tabs.isEmpty()) return new Result(header, List.of(), null, null);
		Result first = tabPage(tabs.get(0));
		return new Result(header, first.items(), first.next(), first.tab());
	}

	@NonNull
	private static Result tabPage(@NonNull Tab tab) throws IOException, ExtractionException {
		ChannelTabInfo info = ChannelTabInfo.getInfo(ServiceList.YouTube, tab.handler);
		return new Result(null, items(info.getRelatedItems()), nextOf(Kind.CHANNEL, null, tab.handler,
						info.hasNextPage() ? info.getNextPage() : null), tab.id());
	}

	@NonNull
	private static List<Tab> tabs(@Nullable List<ListLinkHandler> handlers) {
		List<Tab> out = new ArrayList<>();
		if (handlers == null) return out;
		boolean shorts = AppSettings.showShorts();
		for (String id : TAB_ORDER) {
			if (id.equals(ChannelTabs.SHORTS) && !shorts) continue;
			for (ListLinkHandler handler : handlers) {
				if (handler != null && id.equals(tabId(handler))) {
					out.add(new Tab(id, handler));
					break;
				}
			}
		}
		return Collections.unmodifiableList(out);
	}

	@Nullable
	private static String tabId(@NonNull ListLinkHandler handler) {
		List<String> filters = handler.getContentFilters();
		return filters == null || filters.isEmpty() ? null : filters.get(0);
	}

	@NonNull
	private static Result playlist(@NonNull String url) throws IOException, ExtractionException {
		PlaylistInfo info = PlaylistInfo.getInfo(ServiceList.YouTube, url);
		Header header = new Header(Kind.PLAYLIST, info.getName(), info.getUploaderName(), info.getStreamCount(),
						null, info.getDescription() != null ? info.getDescription().content() : null, info.getId(),
						null, null, List.of(), false, null, List.of());
		return new Result(header, items(info.getRelatedItems()), nextOf(Kind.PLAYLIST, url, null,
						info.hasNextPage() ? info.getNextPage() : null), null);
	}

	@Nullable
	private static Cursor nextOf(@NonNull Kind kind, @Nullable String playlistUrl, @Nullable ListLinkHandler tab,
	                             @Nullable Page page) {
		return page != null && Page.isValid(page) ? new Cursor(kind, playlistUrl, tab, page) : null;
	}

	@NonNull
	private static List<FeedItem> items(@Nullable List<? extends InfoItem> items) {
		List<FeedItem> out = new ArrayList<>();
		if (items == null) return out;
		for (InfoItem item : items) {
			if (item == null) continue;
			if (item instanceof StreamInfoItem stream && isUnavailable(stream)) continue;
			FeedItem converted = FeedClient.convert(item);
			if (converted != null) out.add(converted);
		}
		return out;
	}

	/**
	 * Private or deleted playlist entry: no duration, views or date. The extractor only spots these
	 * by their English titles, useless in any other language, so check the shit that's missing instead.
	 */
	static boolean isUnavailable(@NonNull StreamInfoItem stream) {
		StreamType type = stream.getStreamType();
		boolean live = type == StreamType.LIVE_STREAM || type == StreamType.AUDIO_LIVE_STREAM;
		return stream.getDuration() <= 0 && !live && stream.getViewCount() < 0
						&& stream.getTextualUploadDate() == null;
	}

	@Nullable
	private static String largest(@Nullable List<Image> images) {
		if (images == null || images.isEmpty()) return null;
		Image best = null;
		for (Image image : images) {
			if (image == null || image.getUrl() == null || image.getUrl().isBlank()) continue;
			if (best == null || image.getWidth() > best.getWidth()) best = image;
		}
		return best != null ? best.getUrl() : null;
	}

	/** Smallest banner still sharp across a phone, else the largest. */
	@Nullable
	private static String banner(@Nullable List<Image> images) {
		if (images == null || images.isEmpty()) return null;
		Image best = null;
		Image largest = null;
		for (Image image : images) {
			if (image == null || image.getUrl() == null || image.getUrl().isBlank()) continue;
			if (largest == null || image.getWidth() > largest.getWidth()) largest = image;
			if (image.getWidth() >= BANNER_MIN_WIDTH && (best == null || image.getWidth() < best.getWidth())) best = image;
		}
		Image chosen = best != null ? best : largest;
		return chosen != null ? chosen.getUrl() : null;
	}
}
