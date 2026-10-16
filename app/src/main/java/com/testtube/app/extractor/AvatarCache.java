package com.testtube.app.extractor;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tencent.mmkv.MMKV;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Profile images of channels, for the uploader avatar in list rows. Images seen in any list are
 * remembered on the phone; channels whose image was never seen (followed channels without an
 * account, playlist entries) are looked up once, a few at a time, newest request first.
 */
public final class AvatarCache {
	private static final String TAG = "AvatarCache";
	private static final String STORE_ID = "channel_avatars";
	private static final int MAX_STORED = 4000;
	private static final long STALE_MS = TimeUnit.DAYS.toMillis(30);
	private static final long RETRY_MS = TimeUnit.MINUTES.toMillis(10);
	/** Lookups per app run, so a long scroll never turns into a flood of channel requests. */
	private static final int MAX_LOOKUPS_PER_RUN = 80;
	private static final int LOOKUP_THREADS = 2;
	/** Size a row needs: 36dp at xxhdpi. */
	private static final String ROW_SIZE = "=s108";
	private static final Pattern IMAGE_SIZE = Pattern.compile("=s\\d{2,4}(?=-|$)");
	private static final Pattern CHANNEL_PATH = Pattern.compile(
					"^https?://(?:[a-z0-9-]+\\.)?youtube\\.com/((?:@|c/|user/)[^/?#]+)", Pattern.CASE_INSENSITIVE);

	/** Fetches the profile image of a channel. Called on a background thread. */
	public interface Loader {
		@Nullable
		String load(@NonNull String channelUrl) throws Exception;
	}

	/** Notified on the main thread when the image of a channel becomes known. */
	public interface Listener {
		@MainThread
		void onAvatar(@NonNull String key);
	}

	private record Cached(@Nullable String url, long time) {
	}

	/** Marks channels already looked up in storage and not found there. */
	private static final Cached MISSING = new Cached(null, 0L);

	private static volatile AvatarCache instance;

	@NonNull
	private final Handler main = new Handler(Looper.getMainLooper());
	@NonNull
	private final Map<String, Cached> memory = new ConcurrentHashMap<>();
	@NonNull
	private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
	@NonNull
	private final Map<String, Long> failed = new ConcurrentHashMap<>();
	/** Held weakly, so a screen that is gone can't be kept alive by the cache. Main thread only. */
	@NonNull
	private final Set<Listener> listeners = Collections.newSetFromMap(new WeakHashMap<>());
	@NonNull
	private final AtomicInteger lookups = new AtomicInteger();
	@NonNull
	private final ThreadPoolExecutor pool;
	@Nullable
	private volatile Loader loader;
	@Nullable
	private MMKV store;
	private boolean storeFailed;

	private AvatarCache() {
		AtomicInteger count = new AtomicInteger();
		// LIFO, so the rows currently on screen (requested last) go first
		pool = new ThreadPoolExecutor(LOOKUP_THREADS, LOOKUP_THREADS, 30L, TimeUnit.SECONDS,
						new LinkedBlockingDeque<>() {
							@Override
							public boolean offer(Runnable runnable) {
								return offerFirst(runnable);
							}
						},
						runnable -> {
							Thread thread = new Thread(runnable, "avatar-lookup-" + count.incrementAndGet());
							thread.setDaemon(true);
							return thread;
						});
		pool.allowCoreThreadTimeOut(true);
	}

	@NonNull
	public static AvatarCache get() {
		AvatarCache current = instance;
		if (current == null) {
			synchronized (AvatarCache.class) {
				current = instance;
				if (current == null) {
					current = new AvatarCache();
					instance = current;
				}
			}
		}
		return current;
	}

	public void setLoader(@Nullable Loader loader) {
		this.loader = loader;
	}

	@MainThread
	public void addListener(@NonNull Listener listener) {
		listeners.add(listener);
	}

	@MainThread
	public void removeListener(@NonNull Listener listener) {
		listeners.remove(listener);
	}

	/**
	 * The same key for every link of a channel: its id when the link has one, otherwise its
	 * handle or custom path.
	 */
	@Nullable
	public static String key(@Nullable String channelUrl) {
		if (channelUrl == null || channelUrl.isBlank()) return null;
		String id = LocalSubscriptions.channelIdOf(channelUrl);
		if (id != null) return id;
		Matcher matcher = CHANNEL_PATH.matcher(channelUrl.trim());
		return matcher.find() ? matcher.group(1).toLowerCase(Locale.ROOT) : null;
	}

	/**
	 * Asks YouTube's image server for the size a list row needs. Links in another format are
	 * left alone.
	 */
	@Nullable
	public static String rowSize(@Nullable String imageUrl) {
		if (imageUrl == null || imageUrl.isBlank()) return null;
		String url = imageUrl.startsWith("//") ? "https:" + imageUrl : imageUrl;
		if (!url.startsWith("https://")) return null;
		boolean youtubeImage = url.startsWith("https://yt3.ggpht.com/") || url.startsWith("https://yt3.googleusercontent.com/")
						|| url.startsWith("https://lh3.googleusercontent.com/");
		if (!youtubeImage) return url;
		Matcher matcher = IMAGE_SIZE.matcher(url);
		return matcher.find() ? matcher.replaceFirst(ROW_SIZE) : url;
	}

	/** The image of the channel behind a link, or null when it isn't known yet. */
	@Nullable
	public String find(@Nullable String channelUrl) {
		String key = key(channelUrl);
		if (key == null) return null;
		Cached cached = cached(key);
		return cached != null ? cached.url() : null;
	}

	public void remember(@Nullable String channelUrl, @Nullable String avatarUrl) {
		rememberKey(key(channelUrl), avatarUrl);
	}

	/** Keeps the images that came with the rows of a list. */
	public void rememberAll(@NonNull List<FeedItem> items) {
		for (FeedItem item : items) {
			if (item.authorAvatarUrl() != null) remember(item.authorUrl(), item.authorAvatarUrl());
		}
	}

	/**
	 * Looks up the image of a channel in the background when it is unknown or old. Listeners hear
	 * about the result.
	 */
	public void request(@Nullable String channelUrl) {
		Loader current = loader;
		String key = key(channelUrl);
		if (current == null || key == null || channelUrl == null) return;
		Cached cached = cached(key);
		long now = System.currentTimeMillis();
		if (cached != null && cached.url() != null && now - cached.time() < STALE_MS) return;
		Long failedAt = failed.get(key);
		if (failedAt != null && now - failedAt < RETRY_MS) return;
		if (lookups.get() >= MAX_LOOKUPS_PER_RUN || !inFlight.add(key)) return;
		try {
			pool.execute(() -> lookup(current, key, channelUrl));
		} catch (RejectedExecutionException e) {
			inFlight.remove(key);
		}
	}

	private void lookup(@NonNull Loader current, @NonNull String key, @NonNull String channelUrl) {
		try {
			// Another list may have brought the image while this request waited
			Cached cached = cached(key);
			if (cached != null && cached.url() != null && System.currentTimeMillis() - cached.time() < STALE_MS) return;
			if (lookups.incrementAndGet() > MAX_LOOKUPS_PER_RUN) return;
			String url = current.load(channelUrl);
			if (url == null || url.isBlank()) {
				failed.put(key, System.currentTimeMillis());
				return;
			}
			failed.remove(key);
			rememberKey(key, url);
		} catch (Exception e) {
			failed.put(key, System.currentTimeMillis());
			Log.w(TAG, "channel image lookup failed", e);
		} finally {
			inFlight.remove(key);
		}
	}

	private void rememberKey(@Nullable String key, @Nullable String avatarUrl) {
		String url = rowSize(avatarUrl);
		if (key == null || url == null) return;
		long now = System.currentTimeMillis();
		Cached previous = cached(key);
		// Unchanged and recent: nothing to write
		if (previous != null && url.equals(previous.url()) && now - previous.time() < STALE_MS / 2) return;
		memory.put(key, new Cached(url, now));
		MMKV storage = storage();
		if (storage != null) {
			try {
				storage.encode(key, now + "\n" + url);
			} catch (RuntimeException e) {
				Log.w(TAG, "could not save a channel image", e);
			}
		}
		if (previous == null || !url.equals(previous.url())) notifyListeners(key);
	}

	private void notifyListeners(@NonNull String key) {
		main.post(() -> {
			if (listeners.isEmpty()) return;
			for (Listener listener : new ArrayList<>(listeners)) listener.onAvatar(key);
		});
	}

	/** From memory, then from storage; null when the channel was never seen. */
	@Nullable
	private Cached cached(@NonNull String key) {
		Cached cached = memory.get(key);
		if (cached != null) return cached == MISSING ? null : cached;
		Cached stored = readStored(key);
		memory.putIfAbsent(key, stored != null ? stored : MISSING);
		return stored;
	}

	@Nullable
	private Cached readStored(@NonNull String key) {
		MMKV storage = storage();
		if (storage == null) return null;
		try {
			String value = storage.decodeString(key, null);
			if (value == null) return null;
			int split = value.indexOf('\n');
			if (split <= 0 || split == value.length() - 1) return null;
			long time = Long.parseLong(value.substring(0, split));
			return new Cached(value.substring(split + 1), time);
		} catch (RuntimeException e) {
			return null;
		}
	}

	@Nullable
	private synchronized MMKV storage() {
		if (store != null || storeFailed) return store;
		try {
			MMKV opened = MMKV.mmkvWithID(STORE_ID);
			// Rarely reached; starting over is cheaper than tracking ages
			if (opened.count() > MAX_STORED) opened.clearAll();
			store = opened;
		} catch (RuntimeException | UnsatisfiedLinkError e) {
			// No storage (MMKV not initialized): keep images in memory only
			storeFailed = true;
		}
		return store;
	}
}
