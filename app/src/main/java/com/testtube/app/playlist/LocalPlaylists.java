package com.testtube.app.playlist;

import android.content.Context;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tencent.mmkv.MMKV;
import com.testtube.app.Constant;
import com.testtube.app.R;
import com.testtube.app.util.Json;
import com.testtube.app.util.UrlUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Playlists kept on the phone, nothing goes to YouTube. Watch later is one of them, always there
 * and always first. YouTube videos only.
 */
public final class LocalPlaylists {
	/** Id of the built-in Watch later list. */
	public static final String WATCH_LATER = "wl";
	/** Starts a local playlist's id in a watch link's list param. YouTube's never contain a colon. */
	public static final String LIST_PREFIX = "local:";
	public static final int MAX_NAME_LENGTH = 100;
	private static final int MAX_PLAYLISTS = 500;
	private static final int MAX_ITEMS = 5000;
	private static final String KEY = "local_playlists";
	private static final String EXPORT_KEY = "testtube_playlists";
	private static final int EXPORT_VERSION = 1;
	private static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{11}");
	private static final Pattern PLAYLIST_ID = Pattern.compile("[a-z0-9]{1,32}");

	private static LocalPlaylists instance;

	/** One saved video. */
	public static final class Entry {
		private String videoId;
		@Nullable
		private String title;
		@Nullable
		private String author;
		@Nullable
		private String authorUrl;
		@Nullable
		private String thumbnailUrl;
		private long addedAt;

		public Entry() {
		}

		public Entry(@NonNull String videoId, @Nullable String title, @Nullable String author,
		             @Nullable String authorUrl, @Nullable String thumbnailUrl) {
			this.videoId = videoId;
			this.title = title;
			this.author = author;
			this.authorUrl = authorUrl;
			this.thumbnailUrl = thumbnailUrl;
			this.addedAt = System.currentTimeMillis();
		}

		@NonNull
		public String videoId() {
			return videoId;
		}

		@Nullable
		public String title() {
			return title;
		}

		@Nullable
		public String author() {
			return author;
		}

		@Nullable
		public String authorUrl() {
			return authorUrl;
		}

		@NonNull
		public String thumbnailUrl() {
			return thumbnailUrl != null && !thumbnailUrl.isBlank() ? thumbnailUrl
							: "https://i.ytimg.com/vi/" + videoId + "/mqdefault.jpg";
		}

		@NonNull
		Entry copy() {
			Entry copy = new Entry(videoId, title, author, authorUrl, thumbnailUrl);
			copy.addedAt = addedAt;
			return copy;
		}
	}

	/** A playlist; {@code name} is null for Watch later, whose name follows the app's language. */
	public static final class Playlist {
		private String id;
		@Nullable
		private String name;
		private long created;
		private List<Entry> items = new ArrayList<>();

		public Playlist() {
		}

		Playlist(@NonNull String id, @Nullable String name) {
			this.id = id;
			this.name = name;
			this.created = System.currentTimeMillis();
		}

		@NonNull
		public String id() {
			return id;
		}

		public boolean isWatchLater() {
			return WATCH_LATER.equals(id);
		}

		@NonNull
		public String name(@NonNull Context context) {
			return isWatchLater() || name == null ? context.getString(R.string.playlist_watch_later) : name;
		}

		/** Copy, safe to keep. */
		@NonNull
		public List<Entry> items() {
			List<Entry> copy = new ArrayList<>(items.size());
			for (Entry entry : items) copy.add(entry.copy());
			return copy;
		}

		public int size() {
			return items.size();
		}

		/** First video's picture for the playlist's row, null when empty. */
		@Nullable
		public String firstThumbnailUrl() {
			return items.isEmpty() ? null : items.get(0).thumbnailUrl();
		}

		/** Watch link that plays the playlist from {@code index}. */
		@NonNull
		public String urlAt(int index) {
			return watchUrl(items.get(index).videoId(), id, index);
		}

		@NonNull
		Playlist copy() {
			Playlist copy = new Playlist(id, name);
			copy.created = created;
			copy.items = items();
			return copy;
		}

		int indexOf(@Nullable String videoId) {
			if (videoId == null) return -1;
			for (int i = 0; i < items.size(); i++) {
				if (videoId.equals(items.get(i).videoId)) return i;
			}
			return -1;
		}
	}

	/** After any change. Changes are made on the main thread, so that's where this runs. */
	public interface Listener {
		void onPlaylistsChanged();
	}

	@NonNull
	private final MMKV store;
	@NonNull
	private final Context context;
	@NonNull
	private final List<Playlist> playlists = new ArrayList<>();
	@NonNull
	private final List<Listener> listeners = new CopyOnWriteArrayList<>();

	private LocalPlaylists(@NonNull Context context, @NonNull MMKV store) {
		this.context = context.getApplicationContext();
		this.store = store;
		String json = store.decodeString(KEY, null);
		if (json != null) {
			try {
				Playlist[] saved = Json.GSON.fromJson(json, Playlist[].class);
				if (saved != null) {
					for (Playlist playlist : saved) {
						Playlist clean = sanitize(playlist);
						if (clean != null && find(clean.id) == null) playlists.add(clean);
					}
				}
			} catch (RuntimeException ignored) {
				// corrupt, start over rather than crash every start
			}
		}
		Playlist watchLater = find(WATCH_LATER);
		if (watchLater == null) {
			playlists.add(0, new Playlist(WATCH_LATER, null));
		} else if (playlists.indexOf(watchLater) != 0) {
			playlists.remove(watchLater);
			playlists.add(0, watchLater);
		}
	}

	@NonNull
	public static synchronized LocalPlaylists of(@NonNull Context context) {
		if (instance == null) instance = new LocalPlaylists(context, MMKV.defaultMMKV());
		return instance;
	}

	public static boolean isLocalList(@Nullable String listId) {
		return listId != null && listId.startsWith(LIST_PREFIX);
	}

	/** Playlist id inside a local list param, or null. */
	@Nullable
	public static String idOfList(@Nullable String listId) {
		return isLocalList(listId) ? listId.substring(LIST_PREFIX.length()) : null;
	}

	/** Watch link inside a local playlist. */
	@NonNull
	public static String watchUrl(@NonNull String videoId, @NonNull String playlistId, int index) {
		return Constant.HOME_URL + "/watch?v=" + videoId + "&list=" + LIST_PREFIX + playlistId + "&index=" + (index + 1);
	}

	/**
	 * The plain watch link for sharing, queueing and downloads: a local list means nothing to
	 * YouTube or other apps.
	 */
	@NonNull
	public static String withoutLocalList(@NonNull String url, @NonNull String videoId) {
		return isLocalList(UrlUtils.getQueryParameter(url, "list")) ? Constant.HOME_URL + "/watch?v=" + videoId : url;
	}

	@MainThread
	public void addListener(@NonNull Listener listener) {
		listeners.add(listener);
	}

	@MainThread
	public void removeListener(@NonNull Listener listener) {
		listeners.remove(listener);
	}

	/** Every playlist, Watch later first. Copies. */
	@NonNull
	public synchronized List<Playlist> all() {
		List<Playlist> copy = new ArrayList<>(playlists.size());
		for (Playlist playlist : playlists) copy.add(playlist.copy());
		return copy;
	}

	/** Copy of a playlist, or null if it's gone. */
	@Nullable
	public synchronized Playlist get(@NonNull String id) {
		Playlist playlist = find(id);
		return playlist != null ? playlist.copy() : null;
	}

	/** Ids of the playlists holding the video. */
	@NonNull
	public synchronized Set<String> containing(@NonNull String videoId) {
		Set<String> ids = new HashSet<>();
		for (Playlist playlist : playlists) {
			if (playlist.indexOf(videoId) >= 0) ids.add(playlist.id);
		}
		return ids;
	}

	/** @return the new playlist's id, or null if the name is empty or there are too many */
	@Nullable
	public String create(@NonNull String name) {
		String clean = cleanName(name);
		if (clean == null) return null;
		String id;
		synchronized (this) {
			if (playlists.size() >= MAX_PLAYLISTS) return null;
			do {
				id = Long.toString(System.currentTimeMillis(), 36) + Integer.toString(ThreadLocalRandom.current().nextInt(1296), 36);
			} while (find(id) != null);
			playlists.add(new Playlist(id, clean));
			save();
		}
		changed();
		return id;
	}

	public void rename(@NonNull String id, @NonNull String name) {
		String clean = cleanName(name);
		if (clean == null || WATCH_LATER.equals(id)) return;
		synchronized (this) {
			Playlist playlist = find(id);
			if (playlist == null || clean.equals(playlist.name)) return;
			playlist.name = clean;
			save();
		}
		changed();
	}

	public void delete(@NonNull String id) {
		if (WATCH_LATER.equals(id)) return;
		synchronized (this) {
			if (!playlists.remove(find(id))) return;
			save();
		}
		changed();
	}

	/** @return false if it was there already, the list is full or the id isn't a video */
	public boolean add(@NonNull String playlistId, @NonNull Entry entry) {
		if (entry.videoId == null || !VIDEO_ID.matcher(entry.videoId).matches()) return false;
		synchronized (this) {
			Playlist playlist = find(playlistId);
			if (playlist == null || playlist.items.size() >= MAX_ITEMS || playlist.indexOf(entry.videoId) >= 0) return false;
			playlist.items.add(entry.copy());
			save();
		}
		changed();
		return true;
	}

	public void remove(@NonNull String playlistId, @NonNull String videoId) {
		synchronized (this) {
			Playlist playlist = find(playlistId);
			int index = playlist != null ? playlist.indexOf(videoId) : -1;
			if (index < 0) return;
			playlist.items.remove(index);
			save();
		}
		changed();
	}

	/** New order, as video ids. Videos missing from it keep their place at the end. */
	public void reorder(@NonNull String playlistId, @NonNull List<String> videoIds) {
		synchronized (this) {
			Playlist playlist = find(playlistId);
			if (playlist == null) return;
			List<Entry> sorted = new ArrayList<>(playlist.items.size());
			Set<String> placed = new HashSet<>();
			for (String videoId : videoIds) {
				int index = playlist.indexOf(videoId);
				if (index >= 0 && placed.add(videoId)) sorted.add(playlist.items.get(index));
			}
			for (Entry entry : playlist.items) {
				if (!placed.contains(entry.videoId)) sorted.add(entry);
			}
			playlist.items = sorted;
			save();
		}
		changed();
	}

	/** @return how many went */
	public int removeWatched(@NonNull String playlistId, @NonNull Predicate<String> watched) {
		int removed;
		synchronized (this) {
			Playlist playlist = find(playlistId);
			if (playlist == null) return 0;
			int before = playlist.items.size();
			playlist.items.removeIf(entry -> watched.test(entry.videoId));
			removed = before - playlist.items.size();
			if (removed == 0) return 0;
			save();
		}
		changed();
		return removed;
	}

	/** All playlists as a file other phones can import. */
	@NonNull
	public synchronized String exportJson() {
		JsonObject root = new JsonObject();
		root.addProperty(EXPORT_KEY, EXPORT_VERSION);
		JsonArray list = new JsonArray();
		for (Playlist playlist : playlists) {
			JsonObject out = new JsonObject();
			if (playlist.isWatchLater()) out.addProperty("watchLater", true);
			else out.addProperty("name", playlist.name);
			JsonArray items = new JsonArray();
			for (Entry entry : playlist.items) {
				JsonObject item = new JsonObject();
				item.addProperty("videoId", entry.videoId);
				if (entry.title != null) item.addProperty("title", entry.title);
				if (entry.author != null) item.addProperty("author", entry.author);
				if (entry.authorUrl != null) item.addProperty("authorUrl", entry.authorUrl);
				items.add(item);
			}
			out.add("items", items);
			list.add(out);
		}
		root.add("playlists", list);
		return root.toString();
	}

	/**
	 * Merges an export in: Watch later into Watch later, others into the playlist of the same name
	 * or a new one. Videos already there are skipped.
	 * @return videos added, or -1 if it isn't a TestTube playlists file
	 */
	public int importJson(@NonNull String json) {
		JsonObject root;
		try {
			JsonElement parsed = JsonParser.parseString(json);
			if (!parsed.isJsonObject()) return -1;
			root = parsed.getAsJsonObject();
			if (!root.has(EXPORT_KEY) || !root.has("playlists") || !root.get("playlists").isJsonArray()) return -1;
		} catch (RuntimeException e) {
			return -1;
		}
		int added = 0;
		synchronized (this) {
			for (JsonElement element : root.getAsJsonArray("playlists")) {
				if (!element.isJsonObject()) continue;
				JsonObject in = element.getAsJsonObject();
				Playlist target = target(in);
				if (target == null) continue;
				JsonArray items = in.has("items") && in.get("items").isJsonArray() ? in.getAsJsonArray("items") : new JsonArray();
				for (JsonElement itemElement : items) {
					if (!itemElement.isJsonObject() || target.items.size() >= MAX_ITEMS) continue;
					JsonObject item = itemElement.getAsJsonObject();
					String videoId = string(item, "videoId");
					if (videoId == null || !VIDEO_ID.matcher(videoId).matches() || target.indexOf(videoId) >= 0) continue;
					target.items.add(new Entry(videoId, string(item, "title"), string(item, "author"),
									string(item, "authorUrl"), null));
					added++;
				}
			}
			save();
		}
		changed();
		return added;
	}

	@Nullable
	private Playlist target(@NonNull JsonObject in) {
		JsonElement watchLater = in.get("watchLater");
		if (watchLater != null && watchLater.isJsonPrimitive() && watchLater.getAsBoolean()) return find(WATCH_LATER);
		String name = cleanName(string(in, "name"));
		if (name == null) return null;
		for (Playlist playlist : playlists) {
			if (!playlist.isWatchLater() && name.equalsIgnoreCase(playlist.name)) return playlist;
		}
		if (playlists.size() >= MAX_PLAYLISTS) return null;
		String id;
		do {
			id = Long.toString(System.currentTimeMillis(), 36) + Integer.toString(ThreadLocalRandom.current().nextInt(1296), 36);
		} while (find(id) != null);
		Playlist created = new Playlist(id, name);
		playlists.add(created);
		return created;
	}

	@Nullable
	private static String string(@NonNull JsonObject object, @NonNull String key) {
		JsonElement value = object.get(key);
		if (value == null || !value.isJsonPrimitive()) return null;
		String text = value.getAsString();
		return text.isBlank() ? null : text;
	}

	@Nullable
	private static String cleanName(@Nullable String name) {
		if (name == null) return null;
		String clean = name.replaceAll("\\s+", " ").trim();
		if (clean.isEmpty()) return null;
		return clean.length() > MAX_NAME_LENGTH ? clean.substring(0, MAX_NAME_LENGTH).trim() : clean;
	}

	/** Drops broken or duplicate entries an old or damaged save might hold. */
	@Nullable
	private static Playlist sanitize(@Nullable Playlist playlist) {
		if (playlist == null || playlist.id == null || !PLAYLIST_ID.matcher(playlist.id).matches()) return null;
		Playlist clean = new Playlist(playlist.id, playlist.isWatchLater() ? null : cleanName(playlist.name));
		if (!clean.isWatchLater() && clean.name == null) clean.name = "?";
		clean.created = playlist.created;
		Set<String> seen = new LinkedHashSet<>();
		if (playlist.items != null) {
			for (Entry entry : playlist.items) {
				if (entry == null || entry.videoId == null || !VIDEO_ID.matcher(entry.videoId).matches()) continue;
				if (seen.add(entry.videoId) && clean.items.size() < MAX_ITEMS) clean.items.add(entry.copy());
			}
		}
		return clean;
	}

	@Nullable
	private Playlist find(@Nullable String id) {
		if (id == null) return null;
		for (Playlist playlist : playlists) {
			if (playlist.id.equals(id)) return playlist;
		}
		return null;
	}

	private void save() {
		store.encode(KEY, Json.GSON.toJson(playlists));
	}

	private void changed() {
		for (Listener listener : listeners) listener.onPlaylistsChanged();
	}
}
