package com.testtube.app.plus;

import android.util.Base64;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;

/**
 * Reads a Spotify link from the public embed page other sites show: no account, no app. The page
 * has its data as JSON in one of three forms Spotify used over time; all three work.
 */
final class SpotifyReader {
	/** Link can't be read; message is for the log only. */
	static final class SpotifyException extends Exception {
		SpotifyException(@NonNull String message) {
			super(message);
		}
	}

	private static final Pattern SCRIPT = Pattern.compile(
					"<script\\b[^>]*\\bid=\"(__NEXT_DATA__|resource|initial-state)\"[^>]*>(.*?)</script>",
					Pattern.DOTALL);
	/** Max album/playlist songs looked up, so long lists stay polite. */
	static final int MAX_TRACKS = 50;

	private SpotifyReader() {
	}

	/** Where a spotify.link short link points. */
	@WorkerThread
	@NonNull
	static SpotifyLink.Ref resolve(@NonNull OkHttpClient client, @NonNull String url, @NonNull String userAgent)
					throws IOException, SpotifyException {
		SpotifyLink.Ref ref = SpotifyLink.parse(url);
		if (ref != null) return ref;
		if (!SpotifyLink.isShortLink(url)) throw new SpotifyException("not a Spotify link");
		WebPage page = WebPage.get(client, url, userAgent);
		ref = SpotifyLink.parse(page.url.toString());
		if (ref == null) ref = SpotifyLink.find(page.text);
		if (ref == null) throw new SpotifyException("short link led nowhere known (" + page.code + ")");
		return ref;
	}

	@WorkerThread
	@NonNull
	static SpotifyItem read(@NonNull OkHttpClient client, @NonNull SpotifyLink.Ref ref, @NonNull String userAgent)
					throws IOException, SpotifyException {
		WebPage page = WebPage.get(client, ref.embedUrl(), userAgent);
		if (page.code == 404) throw new SpotifyException("not found");
		if (page.code < 200 || page.code >= 300) throw new IOException("HTTP " + page.code);
		JsonObject entity = entity(page.text);
		if (entity != null) {
			SpotifyItem item = parse(ref.type(), entity);
			if (item != null) return item;
		}
		// page changed: the link preview still has a title to search
		String title = oembedTitle(client, ref, userAgent);
		if (title == null) title = page.meta("og:title");
		if (title == null) throw new SpotifyException("no data in the embed page");
		return new SpotifyItem(ref.type(), title, List.of());
	}

	@Nullable
	private static JsonObject entity(@NonNull String html) {
		Matcher matcher = SCRIPT.matcher(html);
		while (matcher.find()) {
			String id = matcher.group(1);
			String content = matcher.group(2).trim();
			try {
				switch (id) {
					case "__NEXT_DATA__" -> {
						JsonObject entity = path(JsonParser.parseString(content),
										"props", "pageProps", "state", "data", "entity");
						if (entity != null) return entity;
					}
					case "resource" -> {
						JsonElement data = JsonParser.parseString(decodeBase64(content));
						if (data.isJsonObject()) return data.getAsJsonObject();
					}
					case "initial-state" -> {
						JsonObject entity = path(JsonParser.parseString(decodeBase64(content)), "data", "entity");
						if (entity != null) return entity;
					}
					default -> {
						// not a data script
					}
				}
			} catch (JsonParseException | IllegalArgumentException | IllegalStateException e) {
				// next form
			}
		}
		return null;
	}

	@NonNull
	private static String decodeBase64(@NonNull String content) {
		return new String(Base64.decode(content, Base64.DEFAULT), StandardCharsets.UTF_8);
	}

	@Nullable
	private static JsonObject path(@NonNull JsonElement root, @NonNull String... keys) {
		JsonElement current = root;
		for (String key : keys) {
			if (current == null || !current.isJsonObject()) return null;
			current = current.getAsJsonObject().get(key);
		}
		return current != null && current.isJsonObject() ? current.getAsJsonObject() : null;
	}

	@Nullable
	private static SpotifyItem parse(@NonNull SpotifyLink.Type linked, @NonNull JsonObject entity) {
		SpotifyLink.Type type = typeOf(entity, linked);
		String name = firstText(entity, "name", "title");
		if (name == null) return null;
		List<SpotifyItem.Track> tracks = new ArrayList<>();
		switch (type) {
			case TRACK -> {
				String title = firstText(entity, "title", "name");
				if (title != null) tracks.add(new SpotifyItem.Track(title, artists(entity), duration(entity)));
			}
			case ALBUM, PLAYLIST -> {
				JsonElement list = entity.get("trackList");
				if (list != null && list.isJsonArray()) {
					for (JsonElement element : list.getAsJsonArray()) {
						if (!element.isJsonObject()) continue;
						JsonObject track = element.getAsJsonObject();
						String title = firstText(track, "title", "name");
						if (title == null) continue;
						String artist = artists(track);
						if (artist == null) artist = firstText(track, "subtitle");
						tracks.add(new SpotifyItem.Track(title, artist, duration(track)));
						if (tracks.size() >= MAX_TRACKS) break;
					}
				}
			}
			case EPISODE -> {
				String show = firstText(entity, "subtitle");
				JsonObject showObject = path(entity, "show");
				if (show == null && showObject != null) show = firstText(showObject, "name", "publisher");
				tracks.add(new SpotifyItem.Track(name, show, duration(entity)));
			}
			default -> {
				// artist or show: search by name
			}
		}
		return new SpotifyItem(type, name, tracks);
	}

	@NonNull
	private static SpotifyLink.Type typeOf(@NonNull JsonObject entity, @NonNull SpotifyLink.Type linked) {
		String uri = firstText(entity, "uri");
		String type = null;
		if (uri != null) {
			String[] parts = uri.split(":");
			if (parts.length >= 3) type = parts[1];
		}
		if (type == null) type = firstText(entity, "type");
		if (type == null) return linked;
		try {
			return SpotifyLink.Type.valueOf(type.toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			return linked;
		}
	}

	@Nullable
	private static String artists(@NonNull JsonObject object) {
		JsonElement artists = object.get("artists");
		if (artists == null || !artists.isJsonArray()) return null;
		StringBuilder names = new StringBuilder();
		for (JsonElement element : artists.getAsJsonArray()) {
			String name = element.isJsonObject() ? firstText(element.getAsJsonObject(), "name") : null;
			if (name == null) continue;
			if (names.length() > 0) names.append(", ");
			names.append(name);
		}
		return names.length() == 0 ? null : names.toString();
	}

	private static long duration(@NonNull JsonObject object) {
		for (String key : new String[]{"duration", "duration_ms"}) {
			JsonElement value = object.get(key);
			try {
				if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
					long ms = value.getAsLong();
					if (ms > 0L) return ms;
				}
				if (value != null && value.isJsonObject()) {
					JsonElement ms = value.getAsJsonObject().get("totalMilliseconds");
					if (ms != null && ms.isJsonPrimitive() && ms.getAsJsonPrimitive().isNumber()) return ms.getAsLong();
				}
			} catch (NumberFormatException | IllegalStateException e) {
				// not a number after all
			}
		}
		return 0L;
	}

	@Nullable
	private static String firstText(@NonNull JsonObject object, @NonNull String... keys) {
		for (String key : keys) {
			JsonElement value = object.get(key);
			if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
				String text = value.getAsString().trim();
				if (!text.isEmpty()) return text;
			}
		}
		return null;
	}

	@Nullable
	private static String oembedTitle(@NonNull OkHttpClient client, @NonNull SpotifyLink.Ref ref,
	                                  @NonNull String userAgent) {
		HttpUrl url = HttpUrl.get("https://open.spotify.com/oembed").newBuilder()
						.addQueryParameter("url", ref.openUrl()).build();
		try {
			WebPage page = WebPage.get(client, url.toString(), userAgent);
			if (page.code < 200 || page.code >= 300) return null;
			JsonElement json = JsonParser.parseString(page.text);
			return json.isJsonObject() ? firstText(json.getAsJsonObject(), "title") : null;
		} catch (IOException | JsonParseException | IllegalStateException e) {
			return null;
		}
	}
}
