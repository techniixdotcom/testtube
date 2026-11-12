package com.testtube.app;

import android.app.Activity;
import android.app.Application;
import android.app.NotificationManager;
import android.app.usage.StorageStatsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.storage.StorageManager;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.WebView;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
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
import com.testtube.app.extractor.PlayerFunctionStore;
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
import com.testtube.app.player.common.DecoderSupport;
import com.testtube.app.player.common.PlayerPreferences;
import com.testtube.app.player.engine.VideoCacheEvictor;
import com.testtube.app.player.engine.datasource.StreamHttp;
import com.testtube.app.player.queue.QueueRepository;
import com.testtube.app.player.sponsor.SponsorBlockManager;
import com.testtube.app.update.UpdateManager;
import com.testtube.app.util.Json;
import com.testtube.app.util.NetworkWatcher;

import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager;
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper;

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
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@UnstableApi
public final class AppGraph {
	private static final long HTTP_CACHE_BYTES = 64L * 1024L * 1024L;
	/** Video cache folder. */
	private static final String PLAYER_DIR = "player";
	/** OkHttp cache folder. */
	private static final String OKHTTP_DIR = "okhttp";
	/** Last run's cache gets renamed in here at start and deleted in the background. */
	private static final String WIPE_DIR = ".wipe";

	@NonNull
	private final Context context;
	private OkHttpClient okHttpClient;
	private Executor executor;
	private MMKV mmkv;
	private SimpleCache simpleCache;
	private StandaloneDatabaseProvider databaseProvider;
	private VideoCacheEvictor cacheEvictor;
	private WatchHistory watchHistory;
	private DownloadHistoryRepository downloadHistory;
	private StreamDownloader streamDownloader;
	private TestTubeDownloader downloader;
	private PoTokenBridge poTokenBridge;
	private PoTokenHost poTokenHost;
	private PoTokenCoordinator poTokenCoordinator;
	private final AtomicBoolean warmedUp = new AtomicBoolean();
	/** Hosts the player hits first. */
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
							.cache(new Cache(new File(context.getCacheDir(), OKHTTP_DIR), HTTP_CACHE_BYTES))
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

	/** After the first screen: warm up connections and BotGuard so the first video starts fast. */
	public void warmUp() {
		if (!warmedUp.compareAndSet(false, true)) return;
		Executor background = executor();
		OkHttpClient client = okHttpClient();
		for (String host : WARM_HOSTS) {
			background.execute(() -> {
				Request request = new Request.Builder().url("https://" + host + "/generate_204").head().build();
				try (Response ignored = client.newCall(request).execute()) {
					// only the connection matters
				} catch (IOException | RuntimeException offline) {
				}
			});
		}
		background.execute(this::refreshClientVersion);
		// stored sig/n functions into QuickJS now, not when the first video is tapped
		background.execute(() -> {
			youtubeExtractor(); // inits NewPipe and the function store
			YoutubeJavaScriptPlayerManager.warmUp();
		});
		background.execute(() -> poTokenCoordinator().warmUp());
		background.execute(() -> StreamHttp.warmUp(context));
		background.execute(DecoderSupport::load);
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
	public Gson gson() {
		return Json.GSON;
	}

	@NonNull
	public synchronized MMKV mmkv() {
		if (mmkv == null) mmkv = MMKV.defaultMMKV();
		return mmkv;
	}

	/** Video cache index. */
	@NonNull
	private synchronized StandaloneDatabaseProvider databaseProvider() {
		if (databaseProvider == null) databaseProvider = new StandaloneDatabaseProvider(context);
		return databaseProvider;
	}

	@NonNull
	public synchronized SimpleCache simpleCache() {
		if (simpleCache == null) {
			cacheEvictor = new VideoCacheEvictor(playerCacheBytes());
			simpleCache = new SimpleCache(new File(context.getCacheDir(), PLAYER_DIR), cacheEvictor,
							databaseProvider());
		}
		return simpleCache;
	}

	private static long playerCacheBytes() {
		return AppSettings.videoCache().maxBytes;
	}

	/** Emptied through their own APIs, not by deleting files. */
	private static final Set<String> SELF_MANAGED_CACHES = Set.of(PLAYER_DIR, OKHTTP_DIR, TestTubeGlideModule.CACHE_DIR, "WebView", WIPE_DIR);
	/** How long an exit waits for screens and background work. */
	private static final long EXIT_RETRY_MS = 100L;
	private static final int EXIT_RETRIES = 30;
	/** One disk block, App info's minimum per file or folder. */
	private static final long BLOCK_BYTES = 4096L;

	/** Live screens, main thread only. */
	private int liveScreens;
	/** Screens ever opened; lets an exit notice the app came back. */
	private int openedScreens;

	/** Counts screens so an exit only kills the process once they're all gone. */
	@NonNull
	Application.ActivityLifecycleCallbacks screenTracker() {
		return new Application.ActivityLifecycleCallbacks() {
			@Override
			public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
				liveScreens++;
				openedScreens++;
			}

			@Override
			public void onActivityStarted(@NonNull Activity activity) {
			}

			@Override
			public void onActivityResumed(@NonNull Activity activity) {
			}

			@Override
			public void onActivityPaused(@NonNull Activity activity) {
			}

			@Override
			public void onActivityStopped(@NonNull Activity activity) {
			}

			@Override
			public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {
			}

			@Override
			public void onActivityDestroyed(@NonNull Activity activity) {
				if (liveScreens > 0) liveScreens--;
			}
		};
	}

	/** Same number as App info (cache, code cache, external cache, whole blocks). Slow, worker thread. */
	@WorkerThread
	public long cacheSize() {
		try {
			StorageStatsManager stats = context.getSystemService(StorageStatsManager.class);
			StorageManager storage = context.getSystemService(StorageManager.class);
			if (stats != null && storage != null) {
				UUID volume = storage.getUuidForPath(context.getCacheDir());
				// no permission needed for our own package
				return stats.queryStatsForPackage(volume, context.getPackageName(), Process.myUserHandle()).getCacheBytes();
			}
		} catch (IOException | PackageManager.NameNotFoundException | RuntimeException e) {
			Log.w("AppGraph", "Android could not tell the cache size, counting it here", e);
		}
		long total = allocatedSize(context.getCacheDir()) - BLOCK_BYTES + allocatedSize(context.getCodeCacheDir()) - BLOCK_BYTES;
		File[] external = externalCacheDirs();
		for (File dir : external) total += allocatedSize(dir) - BLOCK_BYTES;
		return Math.max(0, total);
	}

	/**
	 * Main thread part of clearing: WebView cache and in-memory pictures. {@link #clearCaches()} does the rest.
	 */
	@MainThread
	public void clearMainThreadCaches() {
		try {
			WebView web = new WebView(context);
			web.clearCache(true);
			web.destroy();
		} catch (RuntimeException e) {
			// no WebView, clear the rest anyway
		}
		Glide.get(context).clearMemory();
	}

	/** Suffix of a moved video cache, deleted with its DB index. */
	private static final String WIPED_PLAYER_SUFFIX = "-" + PLAYER_DIR;
	/** Without a journal, files in OkHttp's folder are orphans. */
	private static final String[] OKHTTP_JOURNALS = {"journal", "journal.bkp"};
	@NonNull
	private final Handler mainHandler = new Handler(Looper.getMainLooper());
	private int wipeCount;

	/**
	 * Runs at process start, before any cache opens. With clear-on-exit on, whatever the last run
	 * left (killed mid-exit, download kept it alive) goes, like App info's Clear cache. Folders are
	 * renamed here and deleted in the background.
	 * <p>
	 * With the setting off only an orphaned OkHttp folder goes (see {@link #clearCaches()}).
	 */
	@MainThread
	public void wipeCacheOnStart() {
		File cacheDir = context.getCacheDir();
		File wipe = new File(cacheDir, WIPE_DIR);
		boolean clearAll = AppSettings.clearCacheOnExit();
		if (clearAll) {
			File[] files = cacheDir.listFiles();
			if (files != null) {
				for (File file : files) {
					// nothing downloads yet, so part files are leftovers
					if (WIPE_DIR.equals(file.getName())) continue;
					moveToWipe(file, wipe);
				}
			}
			// graphics caches, App info counts them too
			File[] codeCache = context.getCodeCacheDir().listFiles();
			if (codeCache != null) {
				for (File file : codeCache) moveToWipe(file, wipe);
			}
		} else {
			File okhttp = new File(cacheDir, OKHTTP_DIR);
			if (okhttp.isDirectory() && !hasOkHttpJournal(okhttp)) moveToWipe(okhttp, wipe);
		}
		if (!clearAll && !wipe.exists()) return;
		executor().execute(() -> {
			deleteWipe(wipe);
			if (clearAll) deleteExternalCaches();
		});
	}

	private static boolean hasOkHttpJournal(@NonNull File dir) {
		for (String name : OKHTTP_JOURNALS) {
			if (new File(dir, name).exists()) return true;
		}
		return false;
	}

	@MainThread
	private void moveToWipe(@NonNull File file, @NonNull File wipe) {
		if (!wipe.isDirectory() && !wipe.mkdirs() && !wipe.isDirectory()) {
			Log.w("AppGraph", "could not create " + wipe);
			return;
		}
		// unique in case an old wipe never finished; keeps the name's end for the player check
		File target = new File(wipe, System.currentTimeMillis() + "-" + (wipeCount++) + "-" + file.getName());
		if (!file.renameTo(target)) Log.w("AppGraph", "could not move " + file + " aside");
	}

	private void deleteWipe(@NonNull File wipe) {
		File[] files = wipe.listFiles();
		if (files != null) {
			for (File file : files) {
				if (file.isDirectory() && file.getName().endsWith(WIPED_PLAYER_SUFFIX)) {
					deleteVideoCache(file);
				}
				deleteRecursively(file);
			}
		}
		deleteRecursively(wipe);
	}

	/** Deletes a video cache and its DB index. */
	private void deleteVideoCache(@NonNull File dir) {
		try {
			SimpleCache.delete(dir, databaseProvider());
		} catch (RuntimeException e) {
			Log.w("AppGraph", "could not delete the video cache's index", e);
		}
	}

	@NonNull
	private File[] externalCacheDirs() {
		try {
			File[] dirs = context.getExternalCacheDirs();
			if (dirs == null) return new File[0];
			List<File> present = new ArrayList<>();
			for (File dir : dirs) {
				if (dir != null) present.add(dir);
			}
			return present.toArray(new File[0]);
		} catch (RuntimeException e) {
			return new File[0];
		}
	}

	/** We never write there, but App info counts it. */
	private void deleteExternalCaches() {
		for (File dir : externalCacheDirs()) deleteChildren(dir);
	}

	/**
	 * Clear cache on exit and Quit. {@code done} runs on the main thread afterwards, even on failure.
	 * <p>
	 * Once every screen is gone the leftovers (index files, folders) go too and the process ends, so
	 * App info shows 0. Reopening, a running or paused download, or an update download keeps the
	 * process; next start cleans up then.
	 */
	@MainThread
	public void clearCachesOnExit(@Nullable Runnable done) {
		int openedAtExit = openedScreens;
		try {
			clearMainThreadCaches();
		} catch (RuntimeException e) {
			Log.w("AppGraph", "could not clear the in-memory caches", e);
		}
		executor().execute(() -> {
			try {
				clearCaches();
			} catch (RuntimeException e) {
				Log.w("AppGraph", "could not clear the cache on exit", e);
			} finally {
				mainHandler.post(() -> {
					try {
						if (done != null) done.run();
					} finally {
						endProcessWhenClosed(openedAtExit, EXIT_RETRIES);
					}
				});
			}
		});
	}

	@MainThread
	private void endProcessWhenClosed(int openedAtExit, int retriesLeft) {
		// opened again, leave it
		if (openedScreens != openedAtExit) return;
		if (downloadsInProgress() || updateDownloading()) return;
		// give screens a moment to close and background work (history saves) a chance to finish.
		// An open screen keeps us alive, slow background work doesn't, Android kills closed apps anyway.
		if (liveScreens > 0 || backgroundBusy()) {
			if (retriesLeft > 0) {
				mainHandler.postDelayed(() -> endProcessWhenClosed(openedAtExit, retriesLeft - 1), EXIT_RETRY_MS);
				return;
			}
			if (liveScreens > 0) return;
		}
		// on the main thread so no screen can open and hit a deleted cache folder
		try {
			wipeAll();
		} catch (RuntimeException e) {
			Log.w("AppGraph", "could not wipe the cache on exit", e);
		}
		try {
			// flush sign-in cookies
			CookieManager.getInstance().flush();
		} catch (RuntimeException e) {
			// no WebView
		}
		NotificationManager notifications = context.getSystemService(NotificationManager.class);
		if (notifications != null) notifications.cancel(PlaybackService.NOTIFICATION_ID);
		Process.killProcess(Process.myPid());
	}

	/** Workers still busy. */
	private boolean backgroundBusy() {
		Executor current;
		synchronized (this) {
			current = executor;
		}
		return current instanceof ThreadPoolExecutor pool && (pool.getActiveCount() > 0 || !pool.getQueue().isEmpty());
	}

	/** What Android's Clear cache deletes. Only right before the process dies. */
	@MainThread
	private void wipeAll() {
		File[] files = context.getCacheDir().listFiles();
		if (files != null) {
			for (File file : files) {
				String name = file.getName();
				if (PLAYER_DIR.equals(name)) {
					deleteVideoCache(file);
				} else if (WIPE_DIR.equals(name)) {
					deleteWipe(file);
				}
				deleteRecursively(file);
			}
		}
		deleteChildren(context.getCodeCacheDir());
		deleteExternalCaches();
	}

	/**
	 * Empties video, HTTP and picture caches through their APIs (safe while playing) and deletes
	 * the rest of the cache dir, graphics caches and external cache. Running or paused downloads keep
	 * their part files, an update download keeps its file. Worker thread; the caller clears the
	 * WebView and memory caches on the main thread.
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
				// evictAll leaves a line per entry in the journal (can be hundreds of KB). Deleting it is fine:
				// OkHttp keeps writing to the open file and the next start sweeps the folder.
				http.flush();
				File okhttp = new File(context.getCacheDir(), OKHTTP_DIR);
				for (String name : OKHTTP_JOURNALS) {
					File journal = new File(okhttp, name);
					if (!journal.delete() && journal.exists()) Log.w("AppGraph", "could not delete " + journal);
				}
			} catch (IOException | RuntimeException e) {
				Log.w("AppGraph", "could not clear the HTTP cache", e);
			}
		}
		// memory cache is cleared by the caller
		Glide.get(context).clearDiskCache();
		File[] files = context.getCacheDir().listFiles();
		if (files != null) {
			boolean downloading = downloadsInProgress();
			boolean updating = updateDownloading();
			for (File file : files) {
				String name = file.getName();
				// managed by their owners
				if (SELF_MANAGED_CACHES.contains(name)) continue;
				if (updating && UpdateManager.UPDATE_DIR.equals(name)) continue;
				// download part files
				if (downloading && file.isFile() && name.endsWith(".tmp")) continue;
				deleteRecursively(file);
			}
		}
		// graphics caches, rebuilt when needed
		deleteChildren(context.getCodeCacheDir());
		deleteExternalCaches();
	}

	/** A download is running or paused, its part files are in use. */
	private boolean downloadsInProgress() {
		TestTubeDownloader current;
		synchronized (this) {
			current = downloader;
		}
		return current != null && current.hasTasks();
	}

	/** An update is downloading into the cache dir. */
	private boolean updateDownloading() {
		UpdateManager current;
		synchronized (this) {
			current = updateManager;
		}
		return current != null && current.isDownloading();
	}

	/** Disk usage like App info counts it: whole blocks, folders too. */
	private static long allocatedSize(@NonNull File file) {
		if (file.isFile()) return (file.length() + BLOCK_BYTES - 1) / BLOCK_BYTES * BLOCK_BYTES;
		if (!file.isDirectory()) return 0;
		long total = BLOCK_BYTES;
		File[] children = file.listFiles();
		if (children != null) {
			for (File child : children) total += allocatedSize(child);
		}
		return total;
	}

	private static void deleteChildren(@NonNull File dir) {
		File[] children = dir.listFiles();
		if (children == null) return;
		for (File child : children) deleteRecursively(child);
	}

	private static void deleteRecursively(@NonNull File file) {
		File[] children = file.isDirectory() ? file.listFiles() : null;
		if (children != null) {
			for (File child : children) deleteRecursively(child);
		}
		if (!file.delete() && file.exists()) Log.w("AppGraph", "could not delete " + file);
	}

	/** Video cache setting changed; a smaller limit frees space now. */
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
			// sync on purpose, this response's rows get filtered with what it taught
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
			youtubeExtractor(); // inits NewPipe
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
			youtubeExtractor(); // inits NewPipe
			pageSource = new PageSource(extractorDownloader(), nativeAuth(), executor());
		}
		return pageSource;
	}

	@NonNull
	public synchronized PlaylistSource playlistSource() {
		if (playlistSource == null) {
			youtubeExtractor(); // inits NewPipe
			playlistSource = new PlaylistSource(context, extractorDownloader(), nativeAuth(), executor());
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
			// before any extraction, so the first video can use the stored functions
			YoutubeJavaScriptPlayerManager.setPersistentCache(new PlayerFunctionStore(context));
			// last launch's WEB client version: Home's first request doesn't wait for sw.js_data
			String version = mmkv().decodeString(KEY_CLIENT_VERSION, null);
			if (version != null && System.currentTimeMillis() - mmkv().decodeLong(KEY_CLIENT_VERSION_AT, 0L) < CLIENT_VERSION_MAX_AGE_MS) {
				YoutubeParsingHelper.seedClientVersion(version);
			}
		}
		return youtubeExtractor;
	}

	private static final String KEY_CLIENT_VERSION = "web_client_version";
	private static final String KEY_CLIENT_VERSION_AT = "web_client_version_at";
	/** YouTube accepts a client version for months; a few days keeps it close to current. */
	private static final long CLIENT_VERSION_MAX_AGE_MS = 3L * 24 * 60 * 60 * 1000;

	/** Extracts the current client version (one small request) and keeps it for next launch. */
	@WorkerThread
	private void refreshClientVersion() {
		youtubeExtractor(); // inits NewPipe
		YoutubeParsingHelper.refreshClientVersion();
		String version = YoutubeParsingHelper.getExtractedClientVersion();
		if (version != null) {
			mmkv().encode(KEY_CLIENT_VERSION, version);
			mmkv().encode(KEY_CLIENT_VERSION_AT, System.currentTimeMillis());
		}
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
