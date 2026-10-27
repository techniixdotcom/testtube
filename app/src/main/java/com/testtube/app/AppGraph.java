package com.testtube.app;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.cache.SimpleCache;

import com.bumptech.glide.Glide;
import com.google.gson.Gson;
import com.tencent.mmkv.MMKV;
import com.testtube.app.downloader.core.StreamDownloader;
import com.testtube.app.downloader.core.TestTubeDownloader;
import com.testtube.app.downloader.core.history.DownloadHistoryRepository;
import com.testtube.app.downloader.core.impl.StreamDownloaderImpl;
import com.testtube.app.downloader.core.impl.TestTubeDownloaderImpl;
import com.testtube.app.extractor.AvatarCache;
import com.testtube.app.extractor.DownloaderImpl;
import com.testtube.app.extractor.ExtractionSessionScope;
import com.testtube.app.extractor.FeedClient;
import com.testtube.app.extractor.InfoCache;
import com.testtube.app.extractor.NativeAuth;
import com.testtube.app.extractor.Originals;
import com.testtube.app.extractor.PageSource;
import com.testtube.app.extractor.PlaylistSource;
import com.testtube.app.extractor.YoutubeExtractor;
import com.testtube.app.extractor.potoken.PoTokenBridge;
import com.testtube.app.extractor.potoken.PoTokenCoordinator;
import com.testtube.app.extractor.potoken.PoTokenHost;
import com.testtube.app.extractor.potoken.TestTubePoTokenProvider;
import com.testtube.app.filter.AiChannelList;
import com.testtube.app.filter.ContentFilters;
import com.testtube.app.history.WatchHistory;
import com.testtube.app.player.PlayerStateStore;
import com.testtube.app.player.common.PlayerPreferences;
import com.testtube.app.player.engine.VideoCacheEvictor;
import com.testtube.app.player.queue.QueueRepository;
import com.testtube.app.player.sponsor.SponsorBlockManager;
import com.testtube.app.update.UpdateManager;
import com.testtube.app.util.NetworkWatcher;

import okhttp3.Cache;
import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@UnstableApi
public final class AppGraph {
	private static final long HTTP_CACHE_BYTES = 64L * 1024L * 1024L;

	@NonNull
	private final Context context;
	private OkHttpClient okHttpClient;
	private Executor executor;
	private Gson gson;
	private MMKV mmkv;
	private SimpleCache simpleCache;
	private VideoCacheEvictor cacheEvictor;
	private WatchHistory watchHistory;
	private DownloadHistoryRepository downloadHistory;
	private StreamDownloader streamDownloader;
	private TestTubeDownloader downloader;
	private PoTokenBridge poTokenBridge;
	private PoTokenHost poTokenHost;
	private PoTokenCoordinator poTokenCoordinator;
	private final AtomicBoolean warmedUp = new AtomicBoolean();
	/** Where the player's requests go first (the video hosts differ per video). */
	private static final String[] WARM_HOSTS = {"www.youtube.com", "youtubei.googleapis.com", "i.ytimg.com"};
	private TestTubePoTokenProvider poTokenProvider;
	private ExtractionSessionScope extractionSessionScope;
	private DownloaderImpl extractorDownloader;
	private NativeAuth nativeAuth;
	private FeedClient feedClient;
	private UpdateManager updateManager;
	private PlaylistSource playlistSource;
	private PageSource pageSource;
	private InfoCache infoCache;
	private YoutubeExtractor youtubeExtractor;
	private PlayerStateStore playerStateStore;
	private QueueRepository queueRepository;
	private PlayerPreferences playerPreferences;
	private NetworkWatcher networkWatcher;
	private SponsorBlockManager sponsorBlockManager;
	private ContentFilters contentFilters;
	private AiChannelList aiChannelList;

	AppGraph(@NonNull Context context) {
		this.context = context.getApplicationContext();
	}

	@NonNull
	public static AppGraph of(@NonNull Context context) {
		return ((App) context.getApplicationContext()).graph();
	}

	@NonNull
	public Context context() {
		return context;
	}

	@NonNull
	public synchronized OkHttpClient okHttpClient() {
		if (okHttpClient == null) {
			Dispatcher dispatcher = new Dispatcher();
			dispatcher.setMaxRequests(128);
			dispatcher.setMaxRequestsPerHost(24);
			okHttpClient = new OkHttpClient.Builder()
							.cache(new Cache(new File(context.getCacheDir(), "okhttp"), HTTP_CACHE_BYTES))
							.dispatcher(dispatcher)
							.retryOnConnectionFailure(true)
							.connectTimeout(20L, TimeUnit.SECONDS)
							.writeTimeout(30L, TimeUnit.SECONDS)
							.readTimeout(45L, TimeUnit.SECONDS)
							.connectionPool(new ConnectionPool(24, 10L, TimeUnit.MINUTES))
							.build();
		}
		return okHttpClient;
	}

	/**
	 * Once the first screen is up: opens the connections a video needs and gets BotGuard going,
	 * so the first tap on a video doesn't pay for any of it.
	 */
	public void warmUp() {
		if (!warmedUp.compareAndSet(false, true)) return;
		Executor background = executor();
		OkHttpClient client = okHttpClient();
		for (String host : WARM_HOSTS) {
			background.execute(() -> {
				Request request = new Request.Builder().url("https://" + host + "/generate_204").head().build();
				try (Response ignored = client.newCall(request).execute()) {
					// only the open connection matters
				} catch (IOException | RuntimeException offline) {
					// the real requests will say so
				}
			});
		}
		background.execute(() -> poTokenCoordinator().warmUp());
	}

	@NonNull
	public synchronized Executor executor() {
		if (executor == null) {
			AtomicInteger count = new AtomicInteger();
			ThreadPoolExecutor pool = new ThreadPoolExecutor(6, 6, 30L, TimeUnit.SECONDS,
							new LinkedBlockingQueue<>(),
							runnable -> {
								Thread thread = new Thread(runnable, "testtube-worker-" + count.incrementAndGet());
								thread.setDaemon(true);
								return thread;
							});
			pool.allowCoreThreadTimeOut(true);
			executor = pool;
		}
		return executor;
	}

	@NonNull
	public synchronized Gson gson() {
		if (gson == null) gson = new Gson();
		return gson;
	}

	@NonNull
	public synchronized MMKV mmkv() {
		if (mmkv == null) mmkv = MMKV.defaultMMKV();
		return mmkv;
	}

	@NonNull
	public synchronized SimpleCache simpleCache() {
		if (simpleCache == null) {
			cacheEvictor = new VideoCacheEvictor(playerCacheBytes());
			simpleCache = new SimpleCache(new File(context.getCacheDir(), "player"), cacheEvictor,
							new StandaloneDatabaseProvider(context));
		}
		return simpleCache;
	}

	private static long playerCacheBytes() {
		return AppSettings.videoCache().maxBytes;
	}

	/** Folders in the cache directory that "Clear cache" must leave alone. */
	private static final Set<String> KEPT_CACHE_FILES = Set.of("updates");
	/** Cache folders emptied through their owners' APIs rather than by deleting files. */
	private static final Set<String> SELF_MANAGED_CACHES = Set.of("player", "okhttp", TestTubeGlideModule.CACHE_DIR, "WebView");

	/**
	 * Bytes of cache the app holds: saved video, pictures and web data. Half-finished downloads
	 * and downloaded updates aren't counted, as "Clear cache" keeps them. Reads the disk.
	 */
	@WorkerThread
	public long cacheSize() {
		long total = 0;
		File[] files = context.getCacheDir().listFiles();
		if (files == null) return 0;
		for (File file : files) {
			if (isKeptCacheFile(file)) continue;
			total += sizeOf(file);
		}
		return total;
	}

	/**
	 * Empties the video, HTTP and picture caches through their own APIs, so this is
	 * safe while something plays, and deletes other leftovers in the cache directory. Downloads in
	 * progress, downloaded updates, cookies, history, the queue and settings stay. Reads and writes
	 * the disk; the WebView's cache and the pictures in memory have to be cleared on the main
	 * thread by the caller.
	 */
	@WorkerThread
	public void clearCaches() {
		SimpleCache video = simpleCache();
		for (String key : video.getKeys()) {
			try {
				video.removeResource(key);
			} catch (RuntimeException e) {
				Log.w("AppGraph", "could not remove cached video " + key, e);
			}
		}
		Cache http = okHttpClient().cache();
		if (http != null) {
			try {
				http.evictAll();
			} catch (IOException e) {
				Log.w("AppGraph", "could not clear the HTTP cache", e);
			}
		}
		// Pictures; their memory cache is emptied by the caller on the main thread
		Glide.get(context).clearDiskCache();
		File[] files = context.getCacheDir().listFiles();
		if (files == null) return;
		for (File file : files) {
			String name = file.getName();
			// The caches above and the WebView (cleared by the caller) manage their own folders
			if (isKeptCacheFile(file) || SELF_MANAGED_CACHES.contains(name)) continue;
			deleteRecursively(file);
		}
	}

	private static boolean isKeptCacheFile(@NonNull File file) {
		// Downloads write *.tmp files here until they are finished
		return KEPT_CACHE_FILES.contains(file.getName()) || (file.isFile() && file.getName().endsWith(".tmp"));
	}

	private static long sizeOf(@NonNull File file) {
		if (file.isFile()) return file.length();
		long total = 0;
		File[] children = file.listFiles();
		if (children != null) {
			for (File child : children) total += sizeOf(child);
		}
		return total;
	}

	private static void deleteRecursively(@NonNull File file) {
		File[] children = file.isDirectory() ? file.listFiles() : null;
		if (children != null) {
			for (File child : children) deleteRecursively(child);
		}
		if (!file.delete() && file.exists()) Log.w("AppGraph", "could not delete " + file);
	}

	/** The "Video cache" setting changed: a smaller limit frees space right away. */
	public void applyVideoCacheSize() {
		SimpleCache cache;
		VideoCacheEvictor evictor;
		synchronized (this) {
			cache = simpleCache;
			evictor = cacheEvictor;
		}
		if (cache == null || evictor == null) return;
		long limit = playerCacheBytes();
		executor().execute(() -> evictor.setMaxBytes(cache, limit));
	}

	@NonNull
	private FeedClient.Taste historyTaste() {
		List<WatchHistory.Entry> entries = new ArrayList<>(watchHistory().entries());
		entries.sort((x, y) -> Long.compare(y.watchedAt(), x.watchedAt()));
		List<WatchHistory.Entry> recent = new ArrayList<>(entries.subList(0, Math.min(entries.size(), 30)));
		Collections.shuffle(recent);
		List<String> ids = new ArrayList<>();
		List<String> titles = new ArrayList<>();
		for (WatchHistory.Entry entry : recent) {
			if (ids.size() < 4) ids.add(entry.videoId());
			else if (titles.size() < 2 && entry.title() != null && !entry.title().isBlank()) titles.add(entry.title());
		}
		return new FeedClient.Taste(ids, titles);
	}

	public synchronized WatchHistory watchHistory() {
		if (watchHistory == null) watchHistory = new WatchHistory(gson());
		return watchHistory;
	}

	@NonNull
	public synchronized DownloadHistoryRepository downloadHistory() {
		if (downloadHistory == null) downloadHistory = new DownloadHistoryRepository(mmkv(), gson());
		return downloadHistory;
	}

	@NonNull
	public synchronized StreamDownloader streamDownloader() {
		if (streamDownloader == null) streamDownloader = new StreamDownloaderImpl(okHttpClient(), mmkv());
		return streamDownloader;
	}

	@NonNull
	public synchronized TestTubeDownloader downloader() {
		if (downloader == null) downloader = new TestTubeDownloaderImpl(context, streamDownloader());
		return downloader;
	}

	@NonNull
	public synchronized PoTokenBridge poTokenBridge() {
		if (poTokenBridge == null) poTokenBridge = new PoTokenBridge();
		return poTokenBridge;
	}

	@NonNull
	public synchronized PoTokenHost poTokenHost() {
		if (poTokenHost == null) poTokenHost = new PoTokenHost(context, poTokenBridge());
		return poTokenHost;
	}

	@NonNull
	public synchronized PoTokenCoordinator poTokenCoordinator() {
		if (poTokenCoordinator == null) {
			poTokenCoordinator = new PoTokenCoordinator(gson(), poTokenBridge(), poTokenHost(),
							extractionSessionScope(), okHttpClient());
		}
		return poTokenCoordinator;
	}

	@NonNull
	public synchronized TestTubePoTokenProvider poTokenProvider() {
		if (poTokenProvider == null) poTokenProvider = new TestTubePoTokenProvider(poTokenCoordinator());
		return poTokenProvider;
	}

	@NonNull
	public synchronized ExtractionSessionScope extractionSessionScope() {
		if (extractionSessionScope == null) extractionSessionScope = new ExtractionSessionScope();
		return extractionSessionScope;
	}

	@NonNull
	public synchronized DownloaderImpl extractorDownloader() {
		if (extractorDownloader == null) {
			extractorDownloader = new DownloaderImpl(okHttpClient(), extractionSessionScope());
			AiChannelList aiChannels = aiChannelList();
			extractorDownloader.setResponseListener(response -> {
				if (aiChannels.isEnabled()) aiChannels.learn(response);
			});
		}
		return extractorDownloader;
	}

	@NonNull
	public synchronized AiChannelList aiChannelList() {
		if (aiChannelList == null) {
			aiChannelList = new AiChannelList(mmkv(), okHttpClient(), executor(), context.getFilesDir());
		}
		return aiChannelList;
	}

	@NonNull
	public synchronized NativeAuth nativeAuth() {
		if (nativeAuth == null) nativeAuth = new NativeAuth(okHttpClient());
		return nativeAuth;
	}

	@NonNull
	public synchronized UpdateManager updateManager() {
		if (updateManager == null) updateManager = new UpdateManager(okHttpClient());
		return updateManager;
	}

	@NonNull
	public synchronized FeedClient feedClient() {
		if (feedClient == null) {
			youtubeExtractor(); // initializes NewPipe
			feedClient = new FeedClient(extractorDownloader(), nativeAuth(), executor());
			feedClient.setTaste(this::historyTaste);
			feedClient.setGeneralQueries(() -> Arrays.asList(context.getResources().getStringArray(R.array.discover_queries)));
			AvatarCache.get().setLoader(feedClient::channelAvatar);
			Originals.get().setLoader(feedClient::originals);
		}
		return feedClient;
	}

	@NonNull
	public synchronized PageSource pageSource() {
		if (pageSource == null) {
			youtubeExtractor(); // initializes NewPipe
			pageSource = new PageSource(extractorDownloader(), nativeAuth(), executor());
		}
		return pageSource;
	}

	@NonNull
	public synchronized PlaylistSource playlistSource() {
		if (playlistSource == null) {
			youtubeExtractor(); // initializes NewPipe
			playlistSource = new PlaylistSource(extractorDownloader(), nativeAuth(), executor());
		}
		return playlistSource;
	}

	@NonNull
	public synchronized InfoCache infoCache() {
		if (infoCache == null) infoCache = new InfoCache(mmkv(), gson());
		return infoCache;
	}

	@NonNull
	public synchronized YoutubeExtractor youtubeExtractor() {
		if (youtubeExtractor == null) {
			youtubeExtractor = new YoutubeExtractor(extractorDownloader(), poTokenProvider(), nativeAuth(),
							infoCache(), executor(), gson(), contentFilters());
		}
		return youtubeExtractor;
	}

	@NonNull
	public synchronized PlayerStateStore playerStateStore() {
		if (playerStateStore == null) playerStateStore = new PlayerStateStore();
		return playerStateStore;
	}

	@NonNull
	public synchronized QueueRepository queueRepository() {
		if (queueRepository == null) queueRepository = new QueueRepository(mmkv(), gson());
		return queueRepository;
	}

	@NonNull
	public synchronized PlayerPreferences playerPreferences() {
		if (playerPreferences == null) {
			NetworkWatcher network = networkWatcher();
			playerPreferences = new PlayerPreferences(mmkv(), gson(), network::isMobile);
		}
		return playerPreferences;
	}

	@NonNull
	public synchronized NetworkWatcher networkWatcher() {
		if (networkWatcher == null) networkWatcher = new NetworkWatcher(context);
		return networkWatcher;
	}

	@NonNull
	public synchronized SponsorBlockManager sponsorBlockManager() {
		if (sponsorBlockManager == null) {
			sponsorBlockManager = new SponsorBlockManager(okHttpClient(), gson(), playerPreferences());
		}
		return sponsorBlockManager;
	}

	@NonNull
	public synchronized ContentFilters contentFilters() {
		if (contentFilters == null) contentFilters = new ContentFilters(mmkv(), gson(), aiChannelList());
		return contentFilters;
	}
}
