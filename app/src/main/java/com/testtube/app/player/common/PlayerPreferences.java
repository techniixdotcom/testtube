package com.testtube.app.player.common;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.google.gson.Gson;
import com.tencent.mmkv.MMKV;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Preference accessors for player behavior.
 * Position, quality and autoplay are always remembered; speed is fixed at 1x.
 */
public final class PlayerPreferences {
	private static final String KEY_VIDEO_QUALITY = "video_quality";
	private static final String KEY_LOOP_MODE = "loop_mode";
	private static final String KEY_LOOP_ENABLED = "loop_enabled";
	private static final String KEY_SUBTITLE_ENABLED = "subtitle_enabled";
	private static final String KEY_SUBTITLE_LANGUAGE = "subtitle_language";
	private static final String PREFIX_PROGRESS = "progress:";
	private static final String KEY_RECENTLY_PLAYED = "recently_played";
	private static final int RECENTLY_PLAYED_LIMIT = 500;
	private static final long RECENTLY_PLAYED_WINDOW_MS = 24L * 60 * 60 * 1000;

	private static final long EXPIRATION_DAYS_3 = 3L * 24 * 60 * 60 * 1000;

	@NonNull
	private final MMKV mmkv;
	@NonNull
	private final Gson gson;
	@NonNull
	private final MutableLiveData<PlayerLoopMode> loopModeState;

	public PlayerPreferences(@NonNull MMKV mmkv, @NonNull Gson gson) {
		this.mmkv = mmkv;
		this.gson = gson;
		this.loopModeState = new MutableLiveData<>(readLoopMode());
	}

	/**
	 * Quality to start videos in: the last quality picked in the player.
	 */
	@Nullable
	public String getPreferredQuality() {
		String quality = mmkv.decodeString(KEY_VIDEO_QUALITY, null);
		return quality == null || quality.isBlank() ? null : quality;
	}

	public void setPreferredQuality(@NonNull String quality) {
		mmkv.encode(KEY_VIDEO_QUALITY, quality);
	}

	@NonNull
	public PlayerLoopMode getLoopMode() {
		return readLoopMode();
	}

	public void setLoopMode(@NonNull PlayerLoopMode mode) {
		mmkv.encode(KEY_LOOP_MODE, mode.persistedValue());
		mmkv.encode(KEY_LOOP_ENABLED, mode == PlayerLoopMode.LOOP_ONE);
		loopModeState.postValue(mode);
	}

	@NonNull
	public LiveData<PlayerLoopMode> getLoopModeState() {
		return loopModeState;
	}

	@NonNull
	private PlayerLoopMode readLoopMode() {
		int persistedMode = mmkv.decodeInt(KEY_LOOP_MODE, Integer.MIN_VALUE);
		if (persistedMode != Integer.MIN_VALUE) {
			return PlayerLoopMode.fromPersistedValue(persistedMode);
		}
		return mmkv.decodeBool(KEY_LOOP_ENABLED, false) ? PlayerLoopMode.LOOP_ONE : PlayerLoopMode.PLAYLIST_NEXT;
	}

	public boolean isSubtitleEnabled() {
		return mmkv.decodeBool(KEY_SUBTITLE_ENABLED, false);
	}

	public void setSubtitleEnabled(boolean enabled) {
		mmkv.encode(KEY_SUBTITLE_ENABLED, enabled);
	}

	@Nullable
	public String getSubtitleLanguage() {
		return mmkv.decodeString(KEY_SUBTITLE_LANGUAGE, null);
	}

	public void setSubtitleLanguage(@Nullable String language) {
		mmkv.encode(KEY_SUBTITLE_LANGUAGE, language);
	}

	public long getResumePosition(@Nullable String videoId) {
		if (videoId == null) return 0;
		String key = PREFIX_PROGRESS + videoId;
		String json = mmkv.decodeString(key, null);
		if (json == null) return 0;
		Progress progress;
		try {
			progress = gson.fromJson(json, Progress.class);
		} catch (RuntimeException e) {
			progress = null;
		}
		if (progress == null || System.currentTimeMillis() - progress.timestamp > EXPIRATION_DAYS_3) {
			mmkv.removeValueForKey(key);
			return 0;
		}
		return progress.position;
	}

	public void persistProgress(@Nullable String videoId, long position, long duration, TimeUnit unit) {
		if (videoId == null) return;
		String key = PREFIX_PROGRESS + videoId;
		String json = gson.toJson(new Progress(position, unit.toMillis(duration), System.currentTimeMillis()));
		mmkv.encode(key, json);
	}

	/**
	 * Forgets the saved position of a video, used once a video has been watched to the end so
	 * that it starts from the beginning the next time it is opened.
	 */
	public void clearProgress(@Nullable String videoId) {
		if (videoId == null || videoId.isBlank()) return;
		mmkv.removeValueForKey(PREFIX_PROGRESS + videoId);
	}

	/**
	 * Remembers that a video has been played, so that autoplay never loops back into it.
	 */
	public synchronized void recordPlayed(@Nullable String videoId) {
		if (videoId == null || videoId.isBlank()) return;
		long now = System.currentTimeMillis();
		List<Played> entries = readRecentlyPlayed(now);
		entries.removeIf(entry -> videoId.equals(entry.id));
		entries.add(new Played(videoId, now));
		while (entries.size() > RECENTLY_PLAYED_LIMIT) {
			entries.remove(0);
		}
		mmkv.encode(KEY_RECENTLY_PLAYED, gson.toJson(entries.toArray(new Played[0])));
	}

	public synchronized boolean wasRecentlyPlayed(@Nullable String videoId) {
		if (videoId == null || videoId.isBlank()) return false;
		for (Played entry : readRecentlyPlayed(System.currentTimeMillis())) {
			if (videoId.equals(entry.id)) return true;
		}
		return false;
	}

	@NonNull
	private List<Played> readRecentlyPlayed(long now) {
		List<Played> out = new ArrayList<>();
		String json = mmkv.decodeString(KEY_RECENTLY_PLAYED, null);
		if (json == null || json.isBlank()) return out;
		try {
			Played[] entries = gson.fromJson(json, Played[].class);
			if (entries == null) return out;
			for (Played entry : entries) {
				if (entry != null && entry.id != null && now - entry.at <= RECENTLY_PLAYED_WINDOW_MS) {
					out.add(entry);
				}
			}
		} catch (RuntimeException ignored) {
			mmkv.removeValueForKey(KEY_RECENTLY_PLAYED);
		}
		return out;
	}

	@NonNull
	public Set<String> getSponsorBlockCategories() {
		return Set.of("sponsor", "selfpromo", "poi_highlight");
	}

/**
 * Component that handles app logic.
 */
	static class Progress {
		private long position;
		private long duration;
		private long timestamp;

		public Progress() {
		}

		public Progress(long position, long duration, long timestamp) {
			this.position = position;
			this.duration = duration;
			this.timestamp = timestamp;
		}

		public long getPosition() {
			return position;
		}

		public void setPosition(long position) {
			this.position = position;
		}

		public long getDuration() {
			return duration;
		}

		public void setDuration(long duration) {
			this.duration = duration;
		}

	}

/**
 * Entry of the recently played history.
 */
	static class Played {
		private String id;
		private long at;

		public Played() {
		}

		public Played(String id, long at) {
			this.id = id;
			this.at = at;
		}

		public String getId() {
			return id;
		}

		public void setId(String id) {
			this.id = id;
		}

		public long getAt() {
			return at;
		}

		public void setAt(long at) {
			this.at = at;
		}
	}
}
