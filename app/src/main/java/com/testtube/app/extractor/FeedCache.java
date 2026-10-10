package com.testtube.app.extractor;

import androidx.annotation.NonNull;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.tencent.mmkv.MMKV;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * Keeps the last feed that was shown, so a list can appear straight away on the next start while
 * the new one loads.
 */
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
			// The cache is only a convenience.
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
