package com.testtube.app.extractor;

import androidx.annotation.NonNull;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.tencent.mmkv.MMKV;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/** Last shown feed, so the next start has something on screen while the new one loads. */
public final class FeedCache {
	private static final int MAX_ITEMS = 60;
	private static final String PREFIX = "feed_cache_";
	private static final Type LIST_TYPE = new TypeToken<List<FeedItem>>() {
	}.getType();
	private static final Gson GSON = new Gson();

	private FeedCache() {
	}

	public static void save(@NonNull String key, @NonNull List<FeedItem> items) {
		List<FeedItem> kept = new ArrayList<>(items.subList(0, Math.min(items.size(), MAX_ITEMS)));
		try {
			MMKV.defaultMMKV().encode(PREFIX + key, GSON.toJson(kept, LIST_TYPE));
		} catch (RuntimeException ignored) {
			// it's only a cache
		}
	}

	@NonNull
	public static List<FeedItem> load(@NonNull String key) {
		try {
			String json = MMKV.defaultMMKV().decodeString(PREFIX + key, null);
			if (json == null) return new ArrayList<>();
			List<FeedItem> items = GSON.fromJson(json, LIST_TYPE);
			return items == null ? new ArrayList<>() : new ArrayList<>(items);
		} catch (RuntimeException e) {
			return new ArrayList<>();
		}
	}
}
