package com.testtube.app.extractor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.tencent.mmkv.MMKV;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The channels the person follows without a YouTube account. Their latest videos come from the
 * public channel feeds, so nothing here needs a login.
 */
public final class LocalSubscriptions {
	private static final String KEY = "local_subscriptions";
	private static final Pattern CHANNEL_ID = Pattern.compile("UC[\\w-]{22}");
	private static final Pattern JSON_ENTRY = Pattern.compile(
					"\"url\"\\s*:\\s*\"[^\"]*(UC[\\w-]{22})[^\"]*\"[^}]*?\"name\"\\s*:\\s*\"([^\"]*)\"");
	private static final Pattern OPML_ENTRY = Pattern.compile(
					"title=\"([^\"]*)\"[^>]*channel_id=(UC[\\w-]{22})");

	private static LocalSubscriptions instance;

	public record Channel(@NonNull String id, @NonNull String name) {
	}

	@NonNull
	private final MMKV store;
	@NonNull
	private final Gson gson = new Gson();
	@NonNull
	private final List<Channel> channels = new ArrayList<>();

	private LocalSubscriptions(@NonNull MMKV store) {
		this.store = store;
		String json = store.decodeString(KEY, null);
		if (json == null) return;
		try {
			Channel[] saved = gson.fromJson(json, Channel[].class);
			if (saved == null) return;
			for (Channel channel : saved) {
				if (channel != null && channel.id() != null) {
					channels.add(new Channel(channel.id(), channel.name() == null ? "" : channel.name()));
				}
			}
		} catch (RuntimeException ignored) {
			// A damaged list is dropped rather than crashing the app.
		}
	}

	@NonNull
	public static synchronized LocalSubscriptions get() {
		if (instance == null) instance = new LocalSubscriptions(MMKV.defaultMMKV());
		return instance;
	}

	@NonNull
	public synchronized List<Channel> all() {
		return new ArrayList<>(channels);
	}

	public synchronized boolean isFollowing(@Nullable String id) {
		return indexOf(id) >= 0;
	}

	/**
	 * @return true when the channel was new
	 */
	public synchronized boolean add(@NonNull Channel channel) {
		int index = indexOf(channel.id());
		if (index >= 0) {
			if (channels.get(index).name().isBlank() && !channel.name().isBlank()) {
				channels.set(index, channel);
				save();
			}
			return false;
		}
		channels.add(channel);
		save();
		return true;
	}

	/**
	 * @return how many of the channels were new
	 */
	public synchronized int addAll(@NonNull List<Channel> incoming) {
		int added = 0;
		for (Channel channel : incoming) {
			if (indexOf(channel.id()) < 0) {
				channels.add(channel);
				added++;
			}
		}
		if (added > 0) save();
		return added;
	}

	public synchronized void remove(@NonNull String id) {
		int index = indexOf(id);
		if (index < 0) return;
		channels.remove(index);
		save();
	}

	/**
	 * Fills in a name that was missing in the imported list.
	 */
	public synchronized void rename(@NonNull String id, @NonNull String name) {
		int index = indexOf(id);
		if (index < 0 || name.isBlank() || !channels.get(index).name().isBlank()) return;
		channels.set(index, new Channel(id, name));
		save();
	}

	private int indexOf(@Nullable String id) {
		if (id == null) return -1;
		for (int i = 0; i < channels.size(); i++) {
			if (channels.get(i).id().equals(id)) return i;
		}
		return -1;
	}

	private void save() {
		store.encode(KEY, gson.toJson(channels));
	}

	/**
	 * The followed channels in the layout of a Google Takeout subscriptions.csv, which
	 * {@link #parseImport} reads back.
	 */
	@NonNull
	public synchronized String exportCsv() {
		StringBuilder out = new StringBuilder("Channel Id,Channel Url,Channel Title\n");
		for (Channel channel : channels) {
			out.append(channel.id()).append(",http://www.youtube.com/channel/").append(channel.id()).append(",\"")
							.append(channel.name().replace("\"", "\"\"")).append("\"\n");
		}
		return out.toString();
	}

	@Nullable
	public static String channelIdOf(@Nullable String text) {
		if (text == null) return null;
		Matcher matcher = CHANNEL_ID.matcher(text);
		return matcher.find() ? matcher.group() : null;
	}

	/**
	 * Reads the channels out of an exported list: a Google Takeout subscriptions.csv, a NewPipe
	 * export, an OPML file or just a text with channel links.
	 */
	@NonNull
	public static List<Channel> parseImport(@NonNull String text) {
		Map<String, String> found = new LinkedHashMap<>();
		Matcher json = JSON_ENTRY.matcher(text);
		while (json.find()) keep(found, json.group(1), json.group(2));
		Matcher opml = OPML_ENTRY.matcher(text);
		while (opml.find()) keep(found, opml.group(2), opml.group(1));
		for (String line : text.split("\\r?\\n")) {
			Matcher matcher = CHANNEL_ID.matcher(line);
			while (matcher.find()) {
				String name = "";
				if (line.startsWith(matcher.group())) {
					// Takeout rows look like: id,url,title
					String[] columns = line.split(",", 3);
					if (columns.length == 3) name = columns[2].trim().replaceAll("^\"|\"$", "");
				}
				keep(found, matcher.group(), name);
			}
		}
		Matcher any = CHANNEL_ID.matcher(text);
		while (any.find()) keep(found, any.group(), "");
		List<Channel> out = new ArrayList<>(found.size());
		for (Map.Entry<String, String> entry : found.entrySet()) out.add(new Channel(entry.getKey(), entry.getValue()));
		return out;
	}

	private static void keep(@NonNull Map<String, String> found, @NonNull String id, @NonNull String name) {
		String existing = found.get(id);
		if (existing == null || (existing.isBlank() && !name.isBlank())) found.put(id, name);
	}
}
