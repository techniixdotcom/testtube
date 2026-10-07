package com.testtube.app.extractor;

import android.util.LruCache;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.testtube.app.Constant;

import org.schabi.newpipe.extractor.ListExtractor;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.playlist.PlaylistInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.io.IOException;

/**
 * Loads the videos of a playlist or mix natively, for the watch screen and playlist navigation.
 * Results are kept in memory for the session.
 */
public final class PlaylistSource {
	private static final int MAX_ITEMS = 500;
	private static final int MAX_PAGES = 12;

	/**
	 * @param items the videos in playlist order
	 */
	public record Playlist(@NonNull String id, @Nullable String title, @NonNull List<FeedItem> items) {
		public int indexOf(@Nullable String videoId) {
			if (videoId == null) return -1;
			for (int i = 0; i < items.size(); i++) {
				if (videoId.equals(items.get(i).videoId())) return i;
			}
			return -1;
		}

		@NonNull
		public String urlAt(int index) {
			FeedItem item = items.get(index);
			return Constant.HOME_URL + "/watch?v=" + item.videoId() + "&list=" + id + "&index=" + (index + 1);
		}
	}

	@NonNull
	private final DownloaderImpl downloader;
	@NonNull
	private final NativeAuth auth;
	@NonNull
	private final Executor executor;
	@NonNull
	private final LruCache<String, Playlist> cache = new LruCache<>(8);
	@NonNull
	private final Map<String, CompletableFuture<Playlist>> running = new ConcurrentHashMap<>();

	public PlaylistSource(@NonNull DownloaderImpl downloader, @NonNull NativeAuth auth, @NonNull Executor executor) {
		this.downloader = downloader;
		this.auth = auth;
		this.executor = executor;
	}

	/**
	 * @param videoId a video of the playlist; mixes are generated from it
	 */
	@NonNull
	public CompletableFuture<Playlist> load(@NonNull String listId, @Nullable String videoId) {
		String key = isMix(listId) ? listId + "|" + videoId : listId;
		Playlist cached = cache.get(key);
		if (cached != null) return CompletableFuture.completedFuture(cached);
		CompletableFuture<Playlist> created = new CompletableFuture<>();
		CompletableFuture<Playlist> existing = running.putIfAbsent(key, created);
		if (existing != null) return existing;
		try {
			executor.execute(() -> {
				try {
					ExtractionSession session = new ExtractionSession(auth.current(true));
					Playlist playlist = downloader.withExtractionSession(() -> fetch(listId, videoId), session);
					cache.put(key, playlist);
					created.complete(playlist);
				} catch (Throwable e) {
					created.completeExceptionally(e);
				} finally {
					running.remove(key, created);
				}
			});
		} catch (RuntimeException rejected) {
			running.remove(key, created);
			created.completeExceptionally(rejected);
		}
		return created;
	}

	@NonNull
	private static Playlist fetch(@NonNull String listId, @Nullable String videoId)
					throws IOException, org.schabi.newpipe.extractor.exceptions.ExtractionException {
		String url = isMix(listId) && videoId != null
						? "https://www.youtube.com/watch?v=" + videoId + "&list=" + listId
						: "https://www.youtube.com/playlist?list=" + listId;
		PlaylistInfo info = PlaylistInfo.getInfo(ServiceList.YouTube, url);
		List<FeedItem> items = new ArrayList<>();
		Set<String> seen = new LinkedHashSet<>();
		add(items, seen, info.getRelatedItems());
		Page next = info.hasNextPage() ? info.getNextPage() : null;
		int pages = 0;
		while (next != null && items.size() < MAX_ITEMS && pages++ < MAX_PAGES) {
			ListExtractor.InfoItemsPage<StreamInfoItem> page = PlaylistInfo.getMoreItems(ServiceList.YouTube, url, next);
			int before = items.size();
			add(items, seen, page.getItems());
			// Mixes never end: stop once a page adds nothing new.
			if (items.size() == before) break;
			next = page.hasNextPage() ? page.getNextPage() : null;
		}
		return new Playlist(listId, info.getName(), Collections.unmodifiableList(items));
	}

	private static void add(@NonNull List<FeedItem> items, @NonNull Set<String> seen,
	                        @Nullable List<StreamInfoItem> page) {
		if (page == null) return;
		for (StreamInfoItem stream : page) {
			if (items.size() >= MAX_ITEMS) return;
			String videoId = YoutubeExtractor.getVideoId(stream.getUrl());
			if (videoId == null || !seen.add(videoId)) continue;
			items.add(new FeedItem(FeedItem.Kind.VIDEO, Constant.HOME_URL + "/watch?v=" + videoId, videoId,
							stream.getName(), stream.getUploaderName(), FeedClient.mobile(stream.getUploaderUrl()),
							FeedItem.thumbnailFor(videoId), stream.getDuration(),
							stream.getViewCount(), stream.getTextualUploadDate(), false));
		}
	}

	public static boolean isMix(@NonNull String listId) {
		return listId.startsWith("RD");
	}
}
