package com.testtube.app;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;

import com.google.gson.Gson;
import com.tencent.mmkv.MMKV;
import com.testtube.app.cache.WebViewCachePolicy;
import com.testtube.app.downloader.core.StreamDownloader;
import com.testtube.app.downloader.core.TestTubeDownloader;
import com.testtube.app.downloader.core.history.DownloadHistoryRepository;
import com.testtube.app.downloader.core.impl.StreamDownloaderImpl;
import com.testtube.app.downloader.core.impl.TestTubeDownloaderImpl;
import com.testtube.app.extension.ExtensionManager;
import com.testtube.app.extractor.AuthContextFactory;
import com.testtube.app.extractor.DownloaderImpl;
import com.testtube.app.extractor.ExtractionSessionScope;
import com.testtube.app.extractor.FeedClient;
import com.testtube.app.extractor.InfoCache;
import com.testtube.app.extractor.NativeAuth;
import com.testtube.app.extractor.PageSource;
import com.testtube.app.extractor.PlaylistSource;
import com.testtube.app.extractor.YoutubeExtractor;
import com.testtube.app.extractor.potoken.PoTokenBridge;
import com.testtube.app.extractor.potoken.PoTokenContextStore;
import com.testtube.app.extractor.potoken.PoTokenCoordinator;
import com.testtube.app.extractor.potoken.PoTokenHost;
import com.testtube.app.extractor.potoken.TestTubePoTokenProvider;
import com.testtube.app.filter.ContentFilters;
import com.testtube.app.history.WatchHistory;
import com.testtube.app.player.PlayerStateStore;
import com.testtube.app.player.common.PlayerPreferences;
import com.testtube.app.player.queue.QueueRepository;
import com.testtube.app.player.sponsor.SponsorBlockManager;

import java.io.File;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.Cache;
import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.ArrayList;

@UnstableApi
public final class AppGraph {
	private static final long HTTP_CACHE_BYTES = 64L * 1024L * 1024L;
	private static final long PLAYER_CACHE_BYTES = 128L * 1024L * 1024L;

	@NonNull
	private final Context context;
	private OkHttpClient okHttpClient;
	private Executor executor;
	private Gson gson;
	private MMKV mmkv;
	private SimpleCache simpleCache;
	private WebViewCachePolicy webViewCachePolicy;
	private WatchHistory watchHistory;
	private DownloadHistoryRepository downloadHistory;
	private StreamDownloader streamDownloader;
	private TestTubeDownloader downloader;
	private PoTokenContextStore poTokenContextStore;
	private PoTokenBridge poTokenBridge;
	private PoTokenHost poTokenHost;
	private PoTokenCoordinator poTokenCoordinator;
	private TestTubePoTokenProvider poTokenProvider;
	private ExtractionSessionScope extractionSessionScope;
	private DownloaderImpl extractorDownloader;
	private AuthContextFactory authContextFactory;
	private NativeAuth nativeAuth;
	private FeedClient feedClient;
	private PlaylistSource playlistSource;
	private PageSource pageSource;
	private InfoCache infoCache;
	private YoutubeExtractor youtubeExtractor;
	private ExtensionManager extensionManager;
	private PlayerStateStore playerStateStore;
	private QueueRepository queueRepository;
	private PlayerPreferences playerPreferences;
	private SponsorBlockManager sponsorBlockManager;
	private ContentFilters contentFilters;

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
			WebViewCachePolicy policy = webViewCachePolicy();
			okHttpClient = new OkHttpClient.Builder()
							.cache(new Cache(new File(context.getCacheDir(), "okhttp"), HTTP_CACHE_BYTES))
							.dispatcher(dispatcher)
							.addNetworkInterceptor(chain -> policy.maybeRewriteResponse(null, chain.request(), chain.proceed(chain.request())))
							.retryOnConnectionFailure(true)
							.connectTimeout(20L, TimeUnit.SECONDS)
							.writeTimeout(30L, TimeUnit.SECONDS)
							.readTimeout(45L, TimeUnit.SECONDS)
							.connectionPool(new ConnectionPool(24, 10L, TimeUnit.MINUTES))
							.build();
		}
		return okHttpClient;
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
			simpleCache = new SimpleCache(new File(context.getCacheDir(), "player"),
							new LeastRecentlyUsedCacheEvictor(PLAYER_CACHE_BYTES),
							new StandaloneDatabaseProvider(context));
		}
		return simpleCache;
	}

	@NonNull
	public synchronized WebViewCachePolicy webViewCachePolicy() {
		if (webViewCachePolicy == null) webViewCachePolicy = new WebViewCachePolicy();
		return webViewCachePolicy;
	}

	@NonNull
	/**
	 * What to search for when building a Home without an account: the channels watched most often
	 * lately and the titles of the last videos.
	 */
	private List<String> historyInterests() {
		List<WatchHistory.Entry> entries = new ArrayList<>(watchHistory().entries());
		entries.sort((a, b) -> Long.compare(b.watchedAt(), a.watchedAt()));
		Map<String, Integer> channels = new LinkedHashMap<>();
		List<String> titles = new ArrayList<>();
		for (int i = 0; i < Math.min(entries.size(), 30); i++) {
			WatchHistory.Entry entry = entries.get(i);
			if (entry.author() != null && !entry.author().isBlank()) channels.merge(entry.author(), 1, Integer::sum);
			if (titles.size() < 2 && entry.title() != null && !entry.title().isBlank()) titles.add(entry.title());
		}
		List<String> topChannels = new ArrayList<>(channels.keySet());
		topChannels.sort((a, b) -> Integer.compare(channels.get(b), channels.get(a)));
		List<String> out = new ArrayList<>(topChannels.subList(0, Math.min(topChannels.size(), 3)));
		out.addAll(titles);
		return out;
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
	public synchronized PoTokenContextStore poTokenContextStore() {
		if (poTokenContextStore == null) poTokenContextStore = new PoTokenContextStore();
		return poTokenContextStore;
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
		if (extractorDownloader == null) extractorDownloader = new DownloaderImpl(okHttpClient(), extractionSessionScope());
		return extractorDownloader;
	}

	@NonNull
	public synchronized AuthContextFactory authContextFactory() {
		if (authContextFactory == null) {
			authContextFactory = new AuthContextFactory(poTokenContextStore(), nativeAuth());
		}
		return authContextFactory;
	}

	@NonNull
	public synchronized NativeAuth nativeAuth() {
		if (nativeAuth == null) nativeAuth = new NativeAuth(okHttpClient());
		return nativeAuth;
	}

	@NonNull
	public synchronized FeedClient feedClient() {
		if (feedClient == null) {
			// The extractor sets up NewPipe, which the feed requests go through.
			youtubeExtractor();
			feedClient = new FeedClient(extractorDownloader(), nativeAuth(), executor());
			feedClient.setInterests(this::historyInterests);
		}
		return feedClient;
	}

	@NonNull
	public synchronized PageSource pageSource() {
		if (pageSource == null) {
			// The extractor sets up NewPipe, which the page requests go through.
			youtubeExtractor();
			pageSource = new PageSource(extractorDownloader(), nativeAuth(), executor());
		}
		return pageSource;
	}

	@NonNull
	public synchronized PlaylistSource playlistSource() {
		if (playlistSource == null) {
			// The extractor sets up NewPipe, which the playlist requests go through.
			youtubeExtractor();
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
			youtubeExtractor = new YoutubeExtractor(extractorDownloader(), poTokenProvider(), authContextFactory(),
							infoCache(), executor(), gson(), contentFilters());
		}
		return youtubeExtractor;
	}

	@NonNull
	public synchronized ExtensionManager extensionManager() {
		if (extensionManager == null) extensionManager = new ExtensionManager(mmkv());
		return extensionManager;
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
		if (playerPreferences == null) playerPreferences = new PlayerPreferences(mmkv(), gson());
		return playerPreferences;
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
		if (contentFilters == null) contentFilters = new ContentFilters(mmkv(), gson(), extensionManager());
		return contentFilters;
	}
}
