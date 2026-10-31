package com.testtube.app.extractor;

import androidx.annotation.NonNull;

import com.google.gson.reflect.TypeToken;
import com.tencent.mmkv.MMKV;
import com.testtube.app.util.Json;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/** Last loaded list per feed, shown when a fresh load fails (e.g. offline). */
public final class FeedCache {
	private static final int MAX_ITEMS = 60;
	private static final String PREFIX = "feed_cache_";
	private static final Type LIST_TYPE = new TypeToken<List<FeedItem>>() {
	}.getType();
	private static final Executor WRITER = Executors.newSingleThreadExecutor(task -> {
		Thread thread = new Thread(task, "feed-cache");
		thread.setDaemon(true);
		return thread;
	});

	private FeedCache() {
	}

	/** Rows are immutable, so a copy of the list can be written out off the main thread. */
	public static void save(@NonNull String key, @NonNull List<FeedItem> items) {
		List<FeedItem> kept = new ArrayList<>(items.subList(0, Math.min(items.size(), MAX_ITEMS)));
		WRITER.execute(() -> {
			try {
				MMKV.defaultMMKV().encode(PREFIX + key, Json.GSON.toJson(kept, LIST_TYPE));
			} catch (RuntimeException ignored) {
				// The cache is only a convenience.
			}
		});
	}

	@NonNull
	public static List<FeedItem> load(@NonNull String key) {
		try {
			String json = MMKV.defaultMMKV().decodeString(PREFIX + key, null);
			if (json == null) return new ArrayList<>();
			List<FeedItem> items = Json.GSON.fromJson(json, LIST_TYPE);
			return items == null ? new ArrayList<>() : new ArrayList<>(items);
		} catch (RuntimeException e) {
			return new ArrayList<>();
		}
	}
}
