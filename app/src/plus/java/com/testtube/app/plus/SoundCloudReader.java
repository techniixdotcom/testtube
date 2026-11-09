package com.testtube.app.plus;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.testtube.app.Constant;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;

/**
 * Reads SoundCloud like its website does, no account: public client id from the site's scripts,
 * then api-v2 for a track, set or artist, then the track's audio. Same approach as NewPipe and yt-dlp.
 */
final class SoundCloudReader {
	private static final String TAG = "SoundCloudReader";
	private static final String API = "https://api-v2.soundcloud.com/";
	private static final Pattern SCRIPT = Pattern.compile("<script[^>]+src=\"([^\"]+)\"");
	private static final Pattern CLIENT_ID = Pattern.compile("client_id\\s*:\\s*\"([0-9a-zA-Z]{32})\"");
	/** Max songs of a set to queue. */
	static final int MAX_SET_TRACKS = 100;
	/** Track ids the API takes per request. */
	private static final int IDS_PER_REQUEST = 50;
	/** Found once per run, looked up again when SoundCloud rejects it. */
	@Nullable
	private static String clientId;

	/** Thrown with what went wrong; the problem decides what the user sees. */
	static final class SoundCloudException extends Exception {
		enum Problem {
			/** No such track, or private without its secret link. */
			NOT_FOUND,
			/** Not in this country. */
			BLOCKED,
			/** Only a 30 s preview (Go+). */
			PREVIEW,
			/** DRM only. */
			UNPLAYABLE,
			/** Didn't understand the answer. */
			UNREADABLE
		}

		@NonNull
		final Problem problem;
		/** The song, to find on YouTube instead; null if unknown. */
		@Nullable
		final SpotifyItem.Track song;

		SoundCloudException(@NonNull Problem problem, @NonNull String message, @Nullable SpotifyItem.Track song) {
			super(message);
			this.problem = problem;
			this.song = song;
		}
	}

	/** A playable track. */
	record Track(@NonNull String pageUrl,
	             @NonNull String streamUrl,
	             boolean hls,
	             @NonNull String title,
	             @Nullable String author,
	             @Nullable String artworkUrl,
	             @Nullable String description,
	             long durationMs) {
	}

	/** A set song to queue; audio is read when it plays. */
	record Entry(@NonNull String pageUrl, @NonNull String title, @Nullable String author,
	             @Nullable String artworkUrl) {
	}

	/** What a link resolved to: track, set songs, or artist name. */
	record Resolved(@NonNull String kind, @NonNull JsonObject json) {
	}

	private SoundCloudReader() {
	}

	/** Track, set or artist a link points to (short links too). */
	@WorkerThread
	@NonNull
	static Resolved resolve(@NonNull OkHttpClient client, @NonNull String url)
					throws IOException, SoundCloudException {
		HttpUrl api = HttpUrl.get(API + "resolve").newBuilder().addQueryParameter("url", url).build();
		JsonObject json = apiObject(client, api);
		String kind = text(json, "kind");
		if (kind == null) throw new SoundCloudException(SoundCloudException.Problem.UNREADABLE, "no kind", null);
		return new Resolved(kind, json);
	}

	/** Audio of a resolved track. */
	@WorkerThread
	@NonNull
	static Track track(@NonNull OkHttpClient client, @NonNull JsonObject track)
					throws IOException, SoundCloudException {
		String title = text(track, "title");
		String pageUrl = text(track, "permalink_url");
		if (title == null || pageUrl == null) {
			throw new SoundCloudException(SoundCloudException.Problem.UNREADABLE, "track without title or link", null);
		}
		JsonObject user = object(track, "user");
		String author = user != null ? text(user, "username") : null;
		long fullDuration = number(track, "full_duration");
		long duration = fullDuration > 0L ? fullDuration : number(track, "duration");
		SpotifyItem.Track song = new SpotifyItem.Track(title, author, duration);
		String policy = text(track, "policy");
		if ("BLOCK".equalsIgnoreCase(policy)) {
			throw new SoundCloudException(SoundCloudException.Problem.BLOCKED, "blocked here", song);
		}
		String authorization = text(track, "track_authorization");
		JsonObject media = object(track, "media");
		JsonArray transcodings = media != null && media.get("transcodings") != null
						&& media.get("transcodings").isJsonArray() ? media.getAsJsonArray("transcodings") : null;
		if (transcodings == null || transcodings.size() == 0) {
			throw new SoundCloudException(SoundCloudException.Problem.UNPLAYABLE, "no transcodings", song);
		}

		Choice choice = choose(transcodings);
		JsonObject best = choice.best();
		if (best == null) {
			throw new SoundCloudException(choice.drmOnly() ? SoundCloudException.Problem.UNPLAYABLE
							: SoundCloudException.Problem.UNREADABLE, "no playable format", song);
		}
		if (choice.preview()) {
			throw new SoundCloudException(SoundCloudException.Problem.PREVIEW, "preview only", song);
		}
		String formatUrl = text(best, "url");
		if (formatUrl == null) throw new SoundCloudException(SoundCloudException.Problem.UNREADABLE, "no format url", song);
		boolean hls = choice.hls();
		HttpUrl.Builder streamApi = HttpUrl.get(formatUrl).newBuilder();
		if (authorization != null) streamApi.addQueryParameter("track_authorization", authorization);
		JsonObject stream = apiObject(client, streamApi.build());
		String streamUrl = text(stream, "url");
		if (streamUrl == null || !streamUrl.startsWith("https://")) {
			throw new SoundCloudException(SoundCloudException.Problem.UNREADABLE, "no stream url", song);
		}
		if (streamUrl.matches(".*/(?:preview|playlist)/0/30/.*")) {
			throw new SoundCloudException(SoundCloudException.Problem.PREVIEW, "preview stream", song);
		}
		String artwork = text(track, "artwork_url");
		if (artwork == null && user != null) artwork = text(user, "avatar_url");
		return new Track(pageUrl, streamUrl, hls, title, author, largeArtwork(artwork), text(track, "description"),
						duration);
	}

	/**
	 * Which format to play.
	 * @param best    null if none plays here
	 * @param drmOnly every format is DRM
	 * @param preview the best one is a 30 s preview
	 */
	record Choice(@Nullable JsonObject best, boolean hls, boolean drmOnly, boolean preview) {
	}

	/**
	 * Best first: progressive MP3 (plays and seeks best), HLS AAC, HLS MP3; full tracks before
	 * previews. DRM, Ogg/Opus HLS (Android's player can't demux it) and the broken "abr" ones are skipped.
	 */
	@NonNull
	static Choice choose(@NonNull JsonArray transcodings) {
		JsonObject best = null;
		boolean bestHls = false;
		int bestRank = 0;
		boolean drmOnly = true;
		for (JsonElement element : transcodings) {
			if (!element.isJsonObject()) continue;
			JsonObject candidate = element.getAsJsonObject();
			JsonObject format = object(candidate, "format");
			String protocol = format != null ? text(format, "protocol") : null;
			String mime = format != null ? text(format, "mime_type") : null;
			String formatUrl = text(candidate, "url");
			String preset = text(candidate, "preset");
			if (protocol == null || mime == null || formatUrl == null) continue;
			String lowerProtocol = protocol.toLowerCase(Locale.ROOT);
			if (lowerProtocol.startsWith("ctr-") || lowerProtocol.startsWith("cbc-")
							|| lowerProtocol.contains("encrypted") || formatUrl.contains("/encrypted-hls")) {
				continue;
			}
			drmOnly = false;
			if (preset != null && preset.startsWith("abr")) continue;
			boolean snipped = bool(candidate, "snipped") || formatUrl.contains("/preview/");
			String lowerMime = mime.toLowerCase(Locale.ROOT);
			boolean hls = "hls".equals(lowerProtocol) || formatUrl.contains("/hls");
			int rank;
			if (!hls && "progressive".equals(lowerProtocol) && lowerMime.contains("mpeg")) rank = 6;
			else if (hls && (lowerMime.contains("mp4") || lowerMime.contains("aac"))) rank = 5;
			else if (hls && lowerMime.contains("mpeg")) rank = 4;
			else continue;
			if (!snipped) rank += 10;
			if (rank > bestRank) {
				bestRank = rank;
				best = candidate;
				bestHls = hls;
			}
		}
		// under 10 the best is a preview, SoundCloud only has 30 s of it (Go+)
		boolean preview = best != null && bestRank < 10;
		return new Choice(best, bestHls, drmOnly, preview);
	}

	/** Set songs in order, max {@link #MAX_SET_TRACKS}. */
	@WorkerThread
	@NonNull
	static List<Entry> setTracks(@NonNull OkHttpClient client, @NonNull JsonObject set, @Nullable String secretToken)
					throws IOException, SoundCloudException {
		JsonArray tracks = set.get("tracks") != null && set.get("tracks").isJsonArray()
						? set.getAsJsonArray("tracks") : new JsonArray();
		List<Long> order = new ArrayList<>();
		Map<Long, JsonObject> full = new HashMap<>();
		for (JsonElement element : tracks) {
			if (!element.isJsonObject()) continue;
			JsonObject track = element.getAsJsonObject();
			long id = number(track, "id");
			if (id <= 0L) continue;
			order.add(id);
			if (text(track, "permalink_url") != null && text(track, "title") != null) full.put(id, track);
			if (order.size() >= MAX_SET_TRACKS) break;
		}
		// past the first few SoundCloud only lists ids, fetch the rest in batches
		List<Long> missing = new ArrayList<>();
		for (long id : order) if (!full.containsKey(id)) missing.add(id);
		long setId = number(set, "id");
		for (int start = 0; start < missing.size(); start += IDS_PER_REQUEST) {
			List<Long> batch = missing.subList(start, Math.min(missing.size(), start + IDS_PER_REQUEST));
			StringBuilder ids = new StringBuilder();
			for (long id : batch) {
				if (ids.length() > 0) ids.append(',');
				ids.append(id);
			}
			HttpUrl.Builder url = HttpUrl.get(API + "tracks").newBuilder().addQueryParameter("ids", ids.toString());
			if (secretToken != null && setId > 0L) {
				url.addQueryParameter("playlistId", String.valueOf(setId));
				url.addQueryParameter("playlistSecretToken", secretToken);
			}
			JsonElement answer = api(client, url.build());
			if (!answer.isJsonArray()) continue;
			for (JsonElement element : answer.getAsJsonArray()) {
				if (!element.isJsonObject()) continue;
				JsonObject track = element.getAsJsonObject();
				long id = number(track, "id");
				if (id > 0L) full.put(id, track);
			}
		}
		List<Entry> entries = new ArrayList<>();
		for (long id : order) {
			JsonObject track = full.get(id);
			if (track == null) continue;
			String pageUrl = text(track, "permalink_url");
			String title = text(track, "title");
			if (pageUrl == null || title == null) continue;
			JsonObject user = object(track, "user");
			String artwork = text(track, "artwork_url");
			if (artwork == null && user != null) artwork = text(user, "avatar_url");
			entries.add(new Entry(pageUrl, title, user != null ? text(user, "username") : null, largeArtwork(artwork)));
		}
		return entries;
	}

	/** 500×500 artwork instead of the API's 100×100. */
	@Nullable
	private static String largeArtwork(@Nullable String url) {
		return url == null ? null : url.replace("-large.", "-t500x500.");
	}

	@NonNull
	private static JsonObject apiObject(@NonNull OkHttpClient client, @NonNull HttpUrl url)
					throws IOException, SoundCloudException {
		JsonElement json = api(client, url);
		if (!json.isJsonObject()) throw new SoundCloudException(SoundCloudException.Problem.UNREADABLE, "not an object", null);
		return json.getAsJsonObject();
	}

	/** API request with the client id; a rejected id is looked up once more. */
	@NonNull
	private static JsonElement api(@NonNull OkHttpClient client, @NonNull HttpUrl url)
					throws IOException, SoundCloudException {
		for (int attempt = 0; attempt < 2; attempt++) {
			String id = clientId(client, attempt > 0);
			HttpUrl withId = url.newBuilder().setQueryParameter("client_id", id).build();
			WebPage page = WebPage.get(client, withId.toString(), Constant.DESKTOP_USER_AGENT);
			if (page.code == 401 || page.code == 403) {
				if (attempt == 0) continue;
				throw new IOException("SoundCloud refused the client id (" + page.code + ")");
			}
			if (page.code == 404) throw new SoundCloudException(SoundCloudException.Problem.NOT_FOUND, "404", null);
			if (page.code == 429) throw new IOException("SoundCloud rate limit");
			if (page.code < 200 || page.code >= 300) throw new IOException("HTTP " + page.code);
			try {
				return JsonParser.parseString(page.text);
			} catch (JsonParseException e) {
				throw new SoundCloudException(SoundCloudException.Problem.UNREADABLE, "bad JSON", null);
			}
		}
		throw new IOException("unreachable");
	}

	/** Client id the website uses, from its scripts. */
	@NonNull
	private static synchronized String clientId(@NonNull OkHttpClient client, boolean refresh)
					throws IOException, SoundCloudException {
		String known = clientId;
		if (known != null && !refresh) return known;
		WebPage home = WebPage.get(client, "https://soundcloud.com/", Constant.DESKTOP_USER_AGENT);
		List<String> scripts = new ArrayList<>();
		Matcher matcher = SCRIPT.matcher(home.text);
		while (matcher.find()) {
			String src = matcher.group(1);
			if (src.startsWith("https://") && src.contains("sndcdn.com/") && src.endsWith(".js")) scripts.add(src);
		}
		// it's in one of the last scripts
		for (int i = scripts.size() - 1; i >= 0; i--) {
			WebPage script = WebPage.get(client, scripts.get(i), Constant.DESKTOP_USER_AGENT);
			Matcher id = CLIENT_ID.matcher(script.text);
			if (id.find()) {
				clientId = id.group(1);
				return clientId;
			}
		}
		Log.w(TAG, "no client id in " + scripts.size() + " scripts");
		throw new SoundCloudException(SoundCloudException.Problem.UNREADABLE, "no client id", null);
	}

	@Nullable
	static String text(@NonNull JsonObject object, @NonNull String key) {
		JsonElement value = object.get(key);
		if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) return null;
		String text = value.getAsString().trim();
		return text.isEmpty() ? null : text;
	}

	private static long number(@NonNull JsonObject object, @NonNull String key) {
		JsonElement value = object.get(key);
		try {
			return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber() ? value.getAsLong() : 0L;
		} catch (NumberFormatException e) {
			return 0L;
		}
	}

	private static boolean bool(@NonNull JsonObject object, @NonNull String key) {
		JsonElement value = object.get(key);
		return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() && value.getAsBoolean();
	}

	@Nullable
	private static JsonObject object(@NonNull JsonObject object, @NonNull String key) {
		JsonElement value = object.get(key);
		return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
	}
}
