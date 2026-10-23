package com.testtube.app;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatDelegate;

import com.tencent.mmkv.MMKV;
import com.testtube.app.player.controller.gesture.GestureAction;

/**
 * Choices made in the first-start dialog, changeable later from the logo menu. Read from list
 * parsing threads too, so the values are kept in volatile fields.
 */
public final class AppSettings {
	private static final String KEY_SHORTS = "show_shorts";
	private static final String KEY_CHANNEL_PAGES = "full_channel_pages";
	private static final String KEY_FLOATING_PLAYER = "floating_player";
	/** Before gestures could be set up: false meant brightness and volume swipes. Read once to migrate. */
	private static final String KEY_SWIPE_MINIMIZES = "swipe_down_minimizes";
	private static final String KEY_GESTURES = "player_gestures";
	private static final String KEY_GESTURE_LEFT = "gesture_left";
	private static final String KEY_GESTURE_RIGHT = "gesture_right";
	private static final String KEY_GESTURE_HORIZONTAL = "gesture_horizontal";
	private static final String KEY_GESTURE_DOUBLE_TAP = "gesture_double_tap";
	private static final String KEY_COMMENTING = "commenting";
	/** Before there were three sizes: true was the large cache, false the small one. Read once to migrate. */
	private static final String KEY_FAST_VIDEO_CACHE = "fast_video_cache";
	private static final String KEY_VIDEO_CACHE = "video_cache";
	private static final String KEY_SUBSCRIPTIONS = "show_subscriptions";
	private static final String KEY_GREY_WATCHED = "grey_out_watched";
	private static final String KEY_WATCHED_DIM = "watched_dim_percent";
	private static final String KEY_THEME = "app_theme";
	private static final String KEY_WELCOME_VERSION = "welcome_version";
	private static final int MIN_WATCHED_DIM = 20;
	private static final int MAX_WATCHED_DIM = 90;
	private static final int WATCHED_DIM_STEP = 5;
	/** As dark as watched videos were before this became a setting. */
	private static final int DEFAULT_WATCHED_DIM = 70;
	/** Raised when the welcome dialog gets new choices, so everyone sees it once more. */
	private static final int WELCOME_VERSION = 5;

	private static volatile boolean loaded;
	private static volatile boolean shorts;
	private static volatile boolean channelPages = true;
	private static volatile boolean floatingPlayer;
	private static volatile boolean gestures;
	@NonNull
	private static volatile GestureAction.Vertical gestureLeft = GestureAction.Vertical.BRIGHTNESS;
	@NonNull
	private static volatile GestureAction.Vertical gestureRight = GestureAction.Vertical.VOLUME;
	@NonNull
	private static volatile GestureAction.Horizontal gestureHorizontal = GestureAction.Horizontal.SEEK;
	@NonNull
	private static volatile GestureAction.DoubleTap gestureDoubleTap = GestureAction.DoubleTap.SKIP;
	private static volatile boolean commenting;
	@NonNull
	private static volatile VideoCache videoCache = VideoCache.LARGE;
	private static volatile boolean subscriptions = true;
	private static volatile boolean greyWatched = true;
	private static volatile int watchedDim = DEFAULT_WATCHED_DIM;
	@NonNull
	private static volatile Theme theme = Theme.SYSTEM;

	private AppSettings() {
	}

	/** Shorts in Home, Subscriptions, search and on channels. Off by default. */
	public static boolean showShorts() {
		load();
		return shorts;
	}

	public static void setShowShorts(boolean show) {
		load();
		shorts = show;
		MMKV.defaultMMKV().encode(KEY_SHORTS, show);
	}

	/**
	 * Full channel pages (banner, handle, follow button, tabs). When off, a channel opens as a
	 * plain list of its videos.
	 */
	public static boolean fullChannelPages() {
		load();
		return channelPages;
	}

	public static void setFullChannelPages(boolean full) {
		load();
		channelPages = full;
		MMKV.defaultMMKV().encode(KEY_CHANNEL_PAGES, full);
	}

	/**
	 * Minimizing puts the video in a floating window over the app, and leaving the app while it
	 * plays keeps it in a picture-in-picture window. When off, minimizing moves it to the bottom bar
	 * and leaving the app keeps only the sound. Off by default.
	 */
	public static boolean floatingPlayer() {
		load();
		return floatingPlayer;
	}

	public static void setFloatingPlayer(boolean floating) {
		load();
		floatingPlayer = floating;
		MMKV.defaultMMKV().encode(KEY_FLOATING_PLAYER, floating);
	}

	/**
	 * Custom gestures on the video. Off (the default): swiping down minimizes, up opens fullscreen,
	 * sideways seeks and double-tapping the sides skips 10 seconds. On: the choices below apply.
	 */
	public static boolean gesturesEnabled() {
		load();
		return gestures;
	}

	public static void setGesturesEnabled(boolean enabled) {
		load();
		gestures = enabled;
		MMKV.defaultMMKV().encode(KEY_GESTURES, enabled);
	}

	/** Swiping up or down on the left half; with gestures off it minimizes. */
	@NonNull
	public static GestureAction.Vertical gestureLeft() {
		load();
		return gestures ? gestureLeft : GestureAction.Vertical.MINIMIZE;
	}

	/** Swiping up or down on the right half; with gestures off it minimizes. */
	@NonNull
	public static GestureAction.Vertical gestureRight() {
		load();
		return gestures ? gestureRight : GestureAction.Vertical.MINIMIZE;
	}

	@NonNull
	public static GestureAction.Horizontal gestureHorizontal() {
		load();
		return gestures ? gestureHorizontal : GestureAction.Horizontal.SEEK;
	}

	@NonNull
	public static GestureAction.DoubleTap gestureDoubleTap() {
		load();
		return gestures ? gestureDoubleTap : GestureAction.DoubleTap.SKIP;
	}

	/** The saved choices, also while gestures are off (for the settings screen). */
	@NonNull
	public static GestureAction.Vertical savedGestureLeft() {
		load();
		return gestureLeft;
	}

	@NonNull
	public static GestureAction.Vertical savedGestureRight() {
		load();
		return gestureRight;
	}

	@NonNull
	public static GestureAction.Horizontal savedGestureHorizontal() {
		load();
		return gestureHorizontal;
	}

	@NonNull
	public static GestureAction.DoubleTap savedGestureDoubleTap() {
		load();
		return gestureDoubleTap;
	}

	public static void setGestures(@NonNull GestureAction.Vertical left, @NonNull GestureAction.Vertical right,
	                               @NonNull GestureAction.Horizontal horizontal,
	                               @NonNull GestureAction.DoubleTap doubleTap) {
		load();
		gestureLeft = left;
		gestureRight = right;
		gestureHorizontal = horizontal;
		gestureDoubleTap = doubleTap;
		MMKV store = MMKV.defaultMMKV();
		store.encode(KEY_GESTURE_LEFT, left.name());
		store.encode(KEY_GESTURE_RIGHT, right.name());
		store.encode(KEY_GESTURE_HORIZONTAL, horizontal.name());
		store.encode(KEY_GESTURE_DOUBLE_TAP, doubleTap.name());
	}

	/** How much video the player keeps on the phone, and whether it loads the next one ahead. */
	public enum VideoCache {
		/** Keeps a lot of what was watched; the next video's start is loaded ahead. */
		LARGE(150, true, R.string.video_cache_large, R.string.video_cache_large_short),
		/** Keeps less; the next video's start is still loaded ahead. */
		MEDIUM(100, true, R.string.video_cache_medium, R.string.video_cache_medium_short),
		/** Keeps little and loads nothing ahead; the next video takes a moment to start. */
		SMALL(50, false, R.string.video_cache_small, R.string.video_cache_small_short);

		/** Also reads the names used before the choices were renamed. */
		@NonNull
		static VideoCache parse(@Nullable String name) {
			if (name == null) return LARGE;
			switch (name) {
				case "FAST":
					return LARGE;
				case "BALANCED":
					return MEDIUM;
				case "SAVER":
					return SMALL;
				default:
					return GestureAction.parse(VideoCache.class, name, LARGE);
			}
		}

		public final long maxBytes;
		public final boolean preloadsNext;
		/** With the size, for the list of choices. */
		@StringRes
		public final int label;
		/** Just the name, shown next to the setting. */
		@StringRes
		public final int shortLabel;

		VideoCache(int megabytes, boolean preloadsNext, @StringRes int label, @StringRes int shortLabel) {
			this.maxBytes = megabytes * 1024L * 1024L;
			this.preloadsNext = preloadsNext;
			this.label = label;
			this.shortLabel = shortLabel;
		}
	}

	/** The "Video cache" setting; Large by default. */
	@NonNull
	public static VideoCache videoCache() {
		load();
		return videoCache;
	}

	public static void setVideoCache(@NonNull VideoCache cache) {
		load();
		videoCache = cache;
		MMKV.defaultMMKV().encode(KEY_VIDEO_CACHE, cache.name());
	}

	/** Light or dark colours for the app; the video and Shorts stay dark in both. */
	public enum Theme {
		/** Follows the phone's dark theme setting. */
		SYSTEM(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, R.string.theme_system),
		LIGHT(AppCompatDelegate.MODE_NIGHT_NO, R.string.theme_light),
		DARK(AppCompatDelegate.MODE_NIGHT_YES, R.string.theme_dark);

		/** For {@link AppCompatDelegate#setDefaultNightMode(int)}. */
		public final int nightMode;
		@StringRes
		public final int label;

		Theme(int nightMode, @StringRes int label) {
			this.nightMode = nightMode;
			this.label = label;
		}
	}

	/** The "Theme" setting; follows the phone by default. */
	@NonNull
	public static Theme theme() {
		load();
		return theme;
	}

	public static void setTheme(@NonNull Theme value) {
		load();
		theme = value;
		MMKV.defaultMMKV().encode(KEY_THEME, value.name());
	}

	/** A box to write comments under videos, for signed-in accounts. Off by default. */
	public static boolean commentingEnabled() {
		load();
		return commenting;
	}

	public static void setCommentingEnabled(boolean enabled) {
		load();
		commenting = enabled;
		MMKV.defaultMMKV().encode(KEY_COMMENTING, enabled);
	}

	/** The Subscriptions tab in the bottom bar. On by default. */
	public static boolean showSubscriptions() {
		load();
		return subscriptions;
	}

	public static void setShowSubscriptions(boolean show) {
		load();
		subscriptions = show;
		MMKV.defaultMMKV().encode(KEY_SUBSCRIPTIONS, show);
	}

	/** Watched videos are dimmed and shown in black and white in the lists. On by default. */
	public static boolean greyOutWatched() {
		load();
		return greyWatched;
	}

	public static void setGreyOutWatched(boolean grey) {
		load();
		greyWatched = grey;
		MMKV.defaultMMKV().encode(KEY_GREY_WATCHED, grey);
	}

	/** How dark watched videos are, in percent: 20 is black and white only, 90 almost black. */
	public static int watchedDimPercent() {
		load();
		return watchedDim;
	}

	/** {@link #watchedDimPercent()} as 0 (black and white only) to 1 (almost black). */
	public static float watchedDarkness() {
		return (watchedDimPercent() - MIN_WATCHED_DIM) / (float) (MAX_WATCHED_DIM - MIN_WATCHED_DIM);
	}

	public static void setWatchedDimPercent(int percent) {
		load();
		watchedDim = clampDim(percent);
		MMKV.defaultMMKV().encode(KEY_WATCHED_DIM, watchedDim);
	}

	/** Within the slider's range and on one of its steps, so it can show the saved value. */
	private static int clampDim(int percent) {
		int stepped = Math.round(percent / (float) WATCHED_DIM_STEP) * WATCHED_DIM_STEP;
		return Math.max(MIN_WATCHED_DIM, Math.min(MAX_WATCHED_DIM, stepped));
	}

	public static boolean welcomeShown() {
		return MMKV.defaultMMKV().decodeInt(KEY_WELCOME_VERSION, 0) >= WELCOME_VERSION;
	}

	public static void setWelcomeShown() {
		MMKV.defaultMMKV().encode(KEY_WELCOME_VERSION, WELCOME_VERSION);
	}

	private static void load() {
		if (loaded) return;
		synchronized (AppSettings.class) {
			if (loaded) return;
			MMKV store = MMKV.defaultMMKV();
			shorts = store.decodeBool(KEY_SHORTS, false);
			channelPages = store.decodeBool(KEY_CHANNEL_PAGES, true);
			floatingPlayer = store.decodeBool(KEY_FLOATING_PLAYER, false);
			// Whoever had turned "swipe down to minimize" off had brightness and volume swipes
			gestures = store.containsKey(KEY_GESTURES)
							? store.decodeBool(KEY_GESTURES, false)
							: !store.decodeBool(KEY_SWIPE_MINIMIZES, true);
			gestureLeft = GestureAction.parse(GestureAction.Vertical.class,
							store.decodeString(KEY_GESTURE_LEFT, null), GestureAction.Vertical.BRIGHTNESS);
			gestureRight = GestureAction.parse(GestureAction.Vertical.class,
							store.decodeString(KEY_GESTURE_RIGHT, null), GestureAction.Vertical.VOLUME);
			gestureHorizontal = GestureAction.parse(GestureAction.Horizontal.class,
							store.decodeString(KEY_GESTURE_HORIZONTAL, null), GestureAction.Horizontal.SEEK);
			gestureDoubleTap = GestureAction.parse(GestureAction.DoubleTap.class,
							store.decodeString(KEY_GESTURE_DOUBLE_TAP, null), GestureAction.DoubleTap.SKIP);
			commenting = store.decodeBool(KEY_COMMENTING, false);
			videoCache = store.containsKey(KEY_VIDEO_CACHE)
							? VideoCache.parse(store.decodeString(KEY_VIDEO_CACHE, null))
							: store.decodeBool(KEY_FAST_VIDEO_CACHE, true) ? VideoCache.LARGE : VideoCache.SMALL;
			subscriptions = store.decodeBool(KEY_SUBSCRIPTIONS, true);
			greyWatched = store.decodeBool(KEY_GREY_WATCHED, true);
			watchedDim = clampDim(store.decodeInt(KEY_WATCHED_DIM, DEFAULT_WATCHED_DIM));
			theme = GestureAction.parse(Theme.class, store.decodeString(KEY_THEME, null), Theme.SYSTEM);
			loaded = true;
		}
	}
}
