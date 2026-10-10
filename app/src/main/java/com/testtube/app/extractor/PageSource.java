package com.testtube.app.extractor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.testtube.app.Constant;

import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ListExtractor;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.channel.ChannelInfo;
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo;
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.playlist.PlaylistInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Loads channel and playlist pages natively: a header and the videos, page by page.
 */
public final class PageSource {
	public enum Kind {
		CHANNEL,
		PLAYLIST
	}

	/**
	 * @param subtitle subscribers for channels, uploader and video count for playlists
	 */
	public record Header(@NonNull Kind kind,
	                     @NonNull String title,
	                     @Nullable String subtitle,
	                     long count,
	                     @Nullable String avatarUrl,
	                     @Nullable String description,
	                     @Nullable String playlistId) {
	}

	/**
	 * Where the next page of a list comes from.
	 */
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
	 * @param header null for following pages
	 * @param next   null at the end of the list
	 */
	public record Result(@Nullable Header header, @NonNull List<FeedItem> items, @Nullable Cursor next) {
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

	/**
	 * First page of a channel or playlist.
	 */
	@NonNull
	public CompletableFuture<Result> open(@NonNull Kind kind, @NonNull String url) {
		String desktopUrl = url.replaceFirst("^https?://(m\\.|www\\.)?youtube\\.com", "https://www.youtube.com");
		return run(() -> kind == Kind.CHANNEL ? channel(desktopUrl) : playlist(desktopUrl));
	}

	@NonNull
	public CompletableFuture<Result> more(@NonNull Cursor cursor) {
		return run(() -> {
			if (cursor.kind == Kind.PLAYLIST && cursor.playlistUrl != null) {
				ListExtractor.InfoItemsPage<StreamInfoItem> page =
								PlaylistInfo.getMoreItems(ServiceList.YouTube, cursor.playlistUrl, cursor.page);
				return new Result(null, videos(page.getItems()),
								page.hasNextPage() ? new Cursor(cursor.kind, cursor.playlistUrl, null, page.getNextPage()) : null);
			}
			if (cursor.tab == null) return new Result(null, List.of(), null);
			ListExtractor.InfoItemsPage<InfoItem> page =
							ChannelTabInfo.getMoreItems(ServiceList.YouTube, cursor.tab, cursor.page);
			return new Result(null, videos(page.getItems()),
							page.hasNextPage() ? new Cursor(cursor.kind, null, cursor.tab, page.getNextPage()) : null);
		});
	}

	@NonNull
	private CompletableFuture<Result> run(@NonNull Load load) {
		CompletableFuture<Result> future = new CompletableFuture<>();
		try {
			executor.execute(() -> {
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
		ChannelInfo info = ChannelInfo.getInfo(ServiceList.YouTube, url);
		Header header = new Header(Kind.CHANNEL, info.getName(), null, info.getSubscriberCount(),
						image(info.getAvatars()), info.getDescription(), null);
		ListLinkHandler videosTab = null;
		for (ListLinkHandler tab : info.getTabs()) {
			if (tab.getContentFilters().contains(ChannelTabs.VIDEOS)) {
				videosTab = tab;
				break;
			}
		}
		if (videosTab == null) return new Result(header, List.of(), null);
		ChannelTabInfo tabInfo = ChannelTabInfo.getInfo(ServiceList.YouTube, videosTab);
		return new Result(header, videos(tabInfo.getRelatedItems()),
						tabInfo.hasNextPage() ? new Cursor(Kind.CHANNEL, null, videosTab, tabInfo.getNextPage()) : null);
	}

	@NonNull
	private static Result playlist(@NonNull String url) throws IOException, ExtractionException {
		PlaylistInfo info = PlaylistInfo.getInfo(ServiceList.YouTube, url);
		Header header = new Header(Kind.PLAYLIST, info.getName(), info.getUploaderName(), info.getStreamCount(),
						null, info.getDescription() != null ? info.getDescription().content() : null, info.getId());
		return new Result(header, videos(info.getRelatedItems()),
						info.hasNextPage() ? new Cursor(Kind.PLAYLIST, url, null, info.getNextPage()) : null);
	}

	@NonNull
	private static List<FeedItem> videos(@Nullable List<? extends InfoItem> items) {
		List<FeedItem> out = new ArrayList<>();
		if (items == null) return out;
		for (InfoItem item : items) {
			if (!(item instanceof StreamInfoItem stream) || stream.isShortFormContent()) continue;
			String videoId = YoutubeExtractor.getVideoId(stream.getUrl());
			if (videoId == null) continue;
			StreamType type = stream.getStreamType();
			boolean live = type == StreamType.LIVE_STREAM || type == StreamType.AUDIO_LIVE_STREAM;
			if (isUnavailable(stream)) continue;
			out.add(new FeedItem(FeedItem.Kind.VIDEO, Constant.HOME_URL + "/watch?v=" + videoId, videoId,
							stream.getName(), stream.getUploaderName(), FeedClient.mobile(stream.getUploaderUrl()),
							FeedItem.thumbnailFor(videoId), stream.getDuration(),
							stream.getViewCount(), stream.getTextualUploadDate(), live));
		}
		return out;
	}

	/**
	 * A private or deleted entry of a playlist: no length, views or date. The extractor drops
	 * these by their title, which it only recognises in English.
	 */
	static boolean isUnavailable(@NonNull StreamInfoItem stream) {
		StreamType type = stream.getStreamType();
		boolean live = type == StreamType.LIVE_STREAM || type == StreamType.AUDIO_LIVE_STREAM;
		return stream.getDuration() <= 0 && !live && stream.getViewCount() < 0
						&& stream.getTextualUploadDate() == null;
	}

	@Nullable
	private static String image(@Nullable List<Image> images) {
		if (images == null || images.isEmpty()) return null;
		Image best = null;
		for (Image image : images) {
			if (best == null || image.getWidth() > best.getWidth()) best = image;
		}
		return best != null ? best.getUrl() : null;
	}
}
