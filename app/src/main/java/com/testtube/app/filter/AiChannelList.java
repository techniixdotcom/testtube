package com.testtube.app.filter;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tencent.mmkv.MMKV;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Channels on AiSList, the community list of YouTube channels that post AI-generated videos
 * (https://github.com/Override92/AiSList, CC BY-NC 4.0). The list is downloaded once a day and
 * kept on the phone.
 *
 * <p>Most entries are handles, while list rows often only know a channel by its id. The ids that
 * belong to listed handles are picked up from YouTube's responses as they go by.</p>
 */
public final class AiChannelList {
	private static final String TAG = "AiChannelList";
	private static final String LIST_URL =
					"https://raw.githubusercontent.com/Override92/AiSList/main/AiSList/aislist_blocklist.txt";
	private static final String FILE_NAME = "aislist_blocklist.txt";
	// Same keys as v1.9.6, so the choice survives an update from there
	private static final String KEY_ENABLED = "ai_filter_enabled";
	private static final String KEY_ETAG = "ai_filter_etag";
	private static final String KEY_FETCHED_AT = "ai_filter_fetched_at";
	private static final String KEY_LEARNED_IDS = "ai_filter_ids";
	private static final long REFRESH_MS = 24 * 60 * 60 * 1000L;
	/** The list is about 1 MB; anything far bigger is not the list. */
	private static final long MAX_LIST_BYTES = 16L * 1024 * 1024;
	private static final int MAX_LEARNED_IDS = 20_000;
	private static final long SAVE_DELAY_MS = 10_000L;
	private static final Pattern HANDLE_THEN_ID = Pattern.compile(
					"\"canonicalBaseUrl\"\\s*:\\s*\"/@([^\"\\\\/?]+)\"\\s*,\\s*\"browseId\"\\s*:\\s*\"(UC[0-9A-Za-z_-]{22})\"");
	private static final Pattern ID_THEN_HANDLE = Pattern.compile(
					"\"browseId\"\\s*:\\s*\"(UC[0-9A-Za-z_-]{22})\"\\s*,\\s*\"canonicalBaseUrl\"\\s*:\\s*\"/@([^\"\\\\/?]+)\"");

	@NonNull
	private final MMKV store;
	@NonNull
	private final OkHttpClient client;
	@NonNull
	private final Executor executor;
	@NonNull
	private final File file;
	@NonNull
	private final Handler main = new Handler(Looper.getMainLooper());
	@NonNull
	private final AtomicBoolean refreshing = new AtomicBoolean();
	@NonNull
	private final AtomicBoolean saveScheduled = new AtomicBoolean();
	@NonNull
	private volatile Set<String> handles = Collections.emptySet();
	@NonNull
	private volatile Set<String> listedIds = Collections.emptySet();
	@NonNull
	private final Set<String> learnedIds = ConcurrentHashMap.newKeySet();
	@Nullable
	private volatile Runnable onChanged;

	public AiChannelList(@NonNull MMKV store,
	                     @NonNull OkHttpClient client,
	                     @NonNull Executor executor,
	                     @NonNull File directory) {
		this.store = store;
		// Conditional requests with the ETag instead of the shared HTTP cache
		this.client = client.newBuilder().cache(null).build();
		this.executor = executor;
		this.file = new File(directory, FILE_NAME);
	}

	public boolean isEnabled() {
		return store.decodeBool(KEY_ENABLED, false);
	}

	/** Called on the main thread when the list or the known ids changed. */
	public void setOnChanged(@Nullable Runnable onChanged) {
		this.onChanged = onChanged;
	}

	/** Loads the saved list and downloads a new one when it is a day old. */
	public void start() {
		if (!isEnabled()) return;
		executor.execute(() -> {
			if (handles.isEmpty() && file.exists()) {
				loadFromFile();
				notifyChanged();
			}
			long fetchedAt = store.decodeLong(KEY_FETCHED_AT, 0L);
			if (!file.exists() || System.currentTimeMillis() - fetchedAt > REFRESH_MS) refresh();
		});
	}

	public void setEnabled(boolean enabled) {
		if (enabled == isEnabled()) return;
		store.encode(KEY_ENABLED, enabled);
		if (enabled) {
			start();
			return;
		}
		handles = Collections.emptySet();
		listedIds = Collections.emptySet();
		learnedIds.clear();
		store.removeValuesForKeys(new String[]{KEY_FETCHED_AT, KEY_ETAG, KEY_LEARNED_IDS});
		executor.execute(() -> {
			if (file.exists() && !file.delete()) Log.w(TAG, "could not delete the saved list");
		});
		notifyChanged();
	}

	/**
	 * @param channelPath a path from {@link ContentFilters#channelPath}, such as "/@name" or
	 *                    "/channel/UC..."
	 */
	public boolean matches(@Nullable String channelPath) {
		if (channelPath == null || handles.isEmpty()) return false;
		if (channelPath.startsWith("/@")) {
			return handles.contains(normalizeHandle(channelPath.substring(2)));
		}
		if (channelPath.startsWith("/channel/")) {
			String id = channelPath.substring("/channel/".length()).toLowerCase(Locale.ROOT);
			return listedIds.contains(id) || learnedIds.contains(id);
		}
		return false;
	}

	/**
	 * Remembers the ids of listed channels from a YouTube response, where a channel's handle and
	 * id appear side by side. Called on the network threads.
	 */
	public void learn(@Nullable String response) {
		Set<String> listed = handles;
		if (listed.isEmpty() || response == null || !response.contains("canonicalBaseUrl")) return;
		if (learnedIds.size() >= MAX_LEARNED_IDS) return;
		boolean added = learn(HANDLE_THEN_ID.matcher(response), 2, 1, listed);
		added |= learn(ID_THEN_HANDLE.matcher(response), 1, 2, listed);
		if (added) scheduleSave();
	}

	private boolean learn(@NonNull Matcher matcher, int idGroup, int handleGroup, @NonNull Set<String> listed) {
		boolean added = false;
		while (matcher.find()) {
			if (listed.contains(normalizeHandle(matcher.group(handleGroup)))) {
				added |= learnedIds.add(matcher.group(idGroup).toLowerCase(Locale.ROOT));
			}
		}
		return added;
	}

	private void scheduleSave() {
		if (!saveScheduled.compareAndSet(false, true)) return;
		main.postDelayed(() -> {
			saveScheduled.set(false);
			if (!isEnabled()) return;
			Set<String> snapshot = new HashSet<>(learnedIds);
			executor.execute(() -> store.encode(KEY_LEARNED_IDS, snapshot));
			notifyChanged();
		}, SAVE_DELAY_MS);
	}

	private void refresh() {
		if (!refreshing.compareAndSet(false, true)) return;
		try {
			Request.Builder request = new Request.Builder().url(LIST_URL);
			String etag = store.decodeString(KEY_ETAG, null);
			if (etag != null && file.exists()) request.header("If-None-Match", etag);
			try (Response response = client.newCall(request.build()).execute()) {
				if (response.code() == 304) {
					store.encode(KEY_FETCHED_AT, System.currentTimeMillis());
					return;
				}
				ResponseBody body = response.body();
				if (!response.isSuccessful() || body == null) {
					Log.w(TAG, "list download failed: HTTP " + response.code());
					return;
				}
				File partial = new File(file.getPath() + ".tmp");
				try (InputStream in = body.byteStream(); OutputStream out = new FileOutputStream(partial)) {
					copy(in, out);
				}
				// Switched off while downloading
				if (!isEnabled()) {
					if (!partial.delete()) Log.w(TAG, "could not delete the partial list");
					return;
				}
				if (!partial.renameTo(file)) throw new IOException("could not replace the saved list");
				String newEtag = response.header("ETag");
				if (newEtag != null) store.encode(KEY_ETAG, newEtag);
				else store.removeValueForKey(KEY_ETAG);
				store.encode(KEY_FETCHED_AT, System.currentTimeMillis());
			}
			loadFromFile();
			notifyChanged();
		} catch (IOException | RuntimeException e) {
			Log.w(TAG, "list download failed", e);
		} finally {
			refreshing.set(false);
		}
	}

	private static void copy(@NonNull InputStream in, @NonNull OutputStream out) throws IOException {
		byte[] buffer = new byte[16 * 1024];
		long total = 0;
		for (int read; (read = in.read(buffer)) != -1; ) {
			total += read;
			if (total > MAX_LIST_BYTES) throw new IOException("list larger than " + MAX_LIST_BYTES + " bytes");
			out.write(buffer, 0, read);
		}
	}

	private void loadFromFile() {
		Set<String> nextHandles = new HashSet<>(32_768);
		Set<String> nextIds = new HashSet<>();
		try (BufferedReader reader = new BufferedReader(
						new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
			for (String line; (line = reader.readLine()) != null; ) {
				String entry = line.trim();
				// "!" starts a comment line
				if (entry.isEmpty() || entry.startsWith("!")) continue;
				if (entry.startsWith("@") && entry.length() > 1) {
					nextHandles.add(normalizeHandle(entry.substring(1)));
				} else if (entry.startsWith("UC")) {
					nextIds.add(entry.toLowerCase(Locale.ROOT));
				}
			}
		} catch (IOException e) {
			Log.w(TAG, "could not read the saved list", e);
			return;
		}
		if (!isEnabled()) return;
		handles = Collections.unmodifiableSet(nextHandles);
		listedIds = Collections.unmodifiableSet(nextIds);
		Set<String> saved = store.decodeStringSet(KEY_LEARNED_IDS, null);
		if (saved != null) learnedIds.addAll(saved);
		Log.i(TAG, "loaded " + (nextHandles.size() + nextIds.size()) + " channels");
	}

	/** Handles are matched without case, and percent-encoded ones (non-Latin names) decoded. */
	@NonNull
	private static String normalizeHandle(@NonNull String handle) {
		String decoded = handle;
		if (handle.indexOf('%') >= 0) {
			try {
				decoded = URLDecoder.decode(handle, "UTF-8");
			} catch (UnsupportedEncodingException | IllegalArgumentException e) {
				decoded = handle;
			}
		}
		return decoded.toLowerCase(Locale.ROOT);
	}

	private void notifyChanged() {
		Runnable listener = onChanged;
		if (listener != null) main.post(listener);
	}
}
