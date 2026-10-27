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
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Video titles and channel names as their creators wrote them. Search, channel pages and
 * suggestions come with both translated into the app's language (by the creator, or
 * automatically by YouTube), so the originals of each video shown are looked up once, a few at
 * a time, and kept on the phone. What the channels' own feeds and the player give is already
 * original and is just remembered.
 */
public final class Originals {
	private static final String TAG = "Originals";
	private static final String STORE_ID = "originals";
	private static final int MAX_STORED = 6000;
	/** Creators rename videos now and then. */
	private static final long STALE_MS = TimeUnit.DAYS.toMillis(14);
	private static final long RETRY_MS = TimeUnit.MINUTES.toMillis(10);
	/** Lookups per app run, so endless scrolling doesn't turn into a fucking request storm. */
	private static final int MAX_LOOKUPS_PER_RUN = 300;
	private static final int LOOKUP_THREADS = 2;

	/** A video's title and its channel's name as the creator wrote them. */
	public record Original(@NonNull String title, @Nullable String author) {
	}

	/** Fetches the originals of a video, or null when YouTube won't say. Background thread. */
	public interface Loader {
		@Nullable
		Original load(@NonNull String videoId) throws Exception;
	}

	/** Notified on the main thread when the originals of a video become known. */
	public interface Listener {
		@MainThread
		void onOriginal(@NonNull String videoId);
	}

	/** @param original null when YouTube had nothing to give (embedding off, say) */
	private record Cached(@Nullable Original original, long time) {
	}

	private static final Cached MISSING = new Cached(null, 0L);

	private static volatile Originals instance;

	@NonNull
	private final Handler main = new Handler(Looper.getMainLooper());
	@NonNull
	private final Map<String, Cached> memory = new ConcurrentHashMap<>();
	@NonNull
	private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
	@NonNull
	private final Map<String, Long> failed = new ConcurrentHashMap<>();
	/** Held weakly, so a closed screen isn't kept alive by the cache. Main thread only. */
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

	private Originals() {
		AtomicInteger count = new AtomicInteger();
		// Newest request first: those are the rows on screen right now
		pool = new ThreadPoolExecutor(LOOKUP_THREADS, LOOKUP_THREADS, 30L, TimeUnit.SECONDS,
						new LinkedBlockingDeque<>() {
							@Override
							public boolean offer(Runnable runnable) {
								return offerFirst(runnable);
							}
						},
						runnable -> {
							Thread thread = new Thread(runnable, "title-lookup-" + count.incrementAndGet());
							thread.setDaemon(true);
							return thread;
						});
		pool.allowCoreThreadTimeOut(true);
	}

	@NonNull
	public static Originals get() {
		Originals current = instance;
		if (current == null) {
			synchronized (Originals.class) {
				current = instance;
				if (current == null) {
					current = new Originals();
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

	/** The originals of a video, or null when they aren't known (yet). */
	@Nullable
	public Original find(@Nullable String videoId) {
		if (videoId == null) return null;
		Cached cached = cached(videoId);
		return cached != null ? cached.original() : null;
	}

	/** The row with the original title and channel name when they're known, as listed otherwise. */
	@NonNull
	public FeedItem apply(@NonNull FeedItem item) {
		Original original = find(item.videoId());
		if (original == null) return item;
		String author = original.author() != null ? original.author() : item.author();
		if (original.title().equals(item.title()) && Objects.equals(author, item.author())) return item;
		return item.withNames(original.title(), author);
	}

	/** For originals that are known as such (a channel's own feed, the player). */
	public void remember(@Nullable String videoId, @Nullable String title, @Nullable String author) {
		if (videoId == null || title == null || title.isBlank()) return;
		save(videoId, new Original(title, author == null || author.isBlank() ? null : author));
	}

	/** Looks up the originals in the background unless they're known and recent. */
	public void request(@Nullable String videoId) {
		Loader current = loader;
		if (current == null || videoId == null) return;
		long now = System.currentTimeMillis();
		Cached cached = cached(videoId);
		if (cached != null && now - cached.time() < STALE_MS) return;
		Long failedAt = failed.get(videoId);
		if (failedAt != null && now - failedAt < RETRY_MS) return;
		if (lookups.get() >= MAX_LOOKUPS_PER_RUN || !inFlight.add(videoId)) return;
		try {
			pool.execute(() -> lookup(current, videoId));
		} catch (RejectedExecutionException e) {
			inFlight.remove(videoId);
		}
	}

	private void lookup(@NonNull Loader current, @NonNull String videoId) {
		try {
			// The player or a feed may have brought it while this waited
			Cached cached = cached(videoId);
			if (cached != null && System.currentTimeMillis() - cached.time() < STALE_MS) return;
			if (lookups.incrementAndGet() > MAX_LOOKUPS_PER_RUN) return;
			Original original = current.load(videoId);
			failed.remove(videoId);
			// No answer (embedding off, removed): remember that, the listed names stay
			save(videoId, original == null || original.title().isBlank() ? null : original);
		} catch (Exception e) {
			failed.put(videoId, System.currentTimeMillis());
			Log.w(TAG, "lookup failed", e);
		} finally {
			inFlight.remove(videoId);
		}
	}

	private void save(@NonNull String videoId, @Nullable Original original) {
		long now = System.currentTimeMillis();
		Cached previous = cached(videoId);
		boolean changed = previous == null || !Objects.equals(original, previous.original());
		// Same as before and saved recently: nothing to write
		if (!changed && now - previous.time() < STALE_MS / 2) return;
		memory.put(videoId, new Cached(original, now));
		MMKV storage = storage();
		if (storage != null) {
			try {
				// One line each; titles and channel names can't contain line breaks
				String value = original == null ? ""
								: original.title() + (original.author() != null ? "\n" + original.author() : "");
				storage.encode(videoId, now + "\n" + value);
			} catch (RuntimeException e) {
				Log.w(TAG, "could not save", e);
			}
		}
		if (changed && original != null) notifyListeners(videoId);
	}

	private void notifyListeners(@NonNull String videoId) {
		main.post(() -> {
			if (listeners.isEmpty()) return;
			for (Listener listener : new ArrayList<>(listeners)) listener.onOriginal(videoId);
		});
	}

	@Nullable
	private Cached cached(@NonNull String videoId) {
		Cached cached = memory.get(videoId);
		if (cached != null) return cached == MISSING ? null : cached;
		Cached stored = readStored(videoId);
		memory.putIfAbsent(videoId, stored != null ? stored : MISSING);
		return stored;
	}

	@Nullable
	private Cached readStored(@NonNull String videoId) {
		MMKV storage = storage();
		if (storage == null) return null;
		try {
			String value = storage.decodeString(videoId, null);
			if (value == null) return null;
			String[] parts = value.split("\n", 3);
			if (parts.length < 2) return null;
			long time = Long.parseLong(parts[0]);
			if (parts[1].isEmpty()) return new Cached(null, time);
			return new Cached(new Original(parts[1], parts.length > 2 ? parts[2] : null), time);
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
			// No storage (MMKV not initialized): keep them in memory only
			storeFailed = true;
		}
		return store;
	}
}
