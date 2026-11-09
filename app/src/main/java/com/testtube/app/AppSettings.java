package com.testtube.app;

import androidx.annotation.ColorRes;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.annotation.StyleRes;
import androidx.appcompat.app.AppCompatDelegate;

import com.tencent.mmkv.MMKV;
import com.testtube.app.player.controller.gesture.GestureAction;

/** First-start choices, changeable from the logo menu. Read from parser threads too, hence volatile. */
public final class AppSettings {
	private static final String KEY_SHORTS = "show_shorts";
	private static final String KEY_CHANNEL_PAGES = "full_channel_pages";
	private static final String KEY_FLOATING_PLAYER = "floating_player";
	/** Old key: false meant brightness/volume swipes. Read once to migrate. */
	private static final String KEY_SWIPE_MINIMIZES = "swipe_down_minimizes";
	private static final String KEY_GESTURES = "player_gestures";
	private static final String KEY_GESTURE_LEFT = "gesture_left";
	private static final String KEY_GESTURE_RIGHT = "gesture_right";
	private static final String KEY_GESTURE_HORIZONTAL = "gesture_horizontal";
	private static final String KEY_GESTURE_DOUBLE_TAP = "gesture_double_tap";
	private static final String KEY_COMMENTING = "commenting";
	/** Old key: true was large cache, false small. Read once to migrate. */
	private static final String KEY_FAST_VIDEO_CACHE = "fast_video_cache";
	private static final String KEY_VIDEO_CACHE = "video_cache";
	private static final String KEY_SUBSCRIPTIONS = "show_subscriptions";
	private static final String KEY_GREY_WATCHED = "grey_out_watched";
	private static final String KEY_WATCHED_DIM = "watched_dim_percent";
	private static final String KEY_THEME = "app_theme";
	private static final String KEY_WELCOME_VERSION = "welcome_version";
	private static final String KEY_CLEAR_CACHE_ON_EXIT = "clear_cache_on_exit";
	private static final String KEY_SEARCH_ONLY_HOME = "search_only_home";
	private static final String KEY_ALLOW_HDR = "allow_hdr";
	private static final String KEY_PREFER_AVC = "prefer_avc";
	private static final int MIN_WATCHED_DIM = 20;
	private static final int MAX_WATCHED_DIM = 90;
	private static final int WATCHED_DIM_STEP = 5;
	/** The old fixed dim level. */
	private static final int DEFAULT_WATCHED_DIM = 70;
	/** Bump when the welcome dialog gets new choices so everyone sees it again. */
	private static final int WELCOME_VERSION = 6;

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
	private static volatile boolean clearCacheOnExit;
	private static volatile boolean searchOnlyHome;
	private static volatile boolean allowHdr;
	private static volatile boolean preferAvc;

	private AppSettings() {
	}

	/** Shorts in Home, Subscriptions, search and channels. Off by default. */
	public static boolean showShorts() {
		load();
		return shorts;
	}

	public static void setShowShorts(boolean show) {
		load();
		shorts = show;
		MMKV.defaultMMKV().encode(KEY_SHORTS, show);
	}

	/** Full channel pages (banner, handle, follow, tabs). Off: just a list of videos. */
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
	 * Minimize to a floating window, and PiP when leaving the app. Off: bottom bar, sound only in
	 * the background. Off by default.
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
	 * Custom video gestures. Off (default): down minimizes, up fullscreen, sideways seeks, double
	 * tap skips 10 s. On: the choices below.
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

	/** Left half up/down; minimizes when gestures are off. */
	@NonNull
	public static GestureAction.Vertical gestureLeft() {
		load();
		return gestures ? gestureLeft : GestureAction.Vertical.MINIMIZE;
	}

	/** Right half up/down; minimizes when gestures are off. */
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

	/** Saved choices, even with gestures off (settings screen). */
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

	/** How much video stays on the phone and whether the next one preloads. */
	public enum VideoCache {
		/** Keeps a lot, preloads the next start. */
		LARGE(150, true, R.string.video_cache_large, R.string.video_cache_large_short),
		/** Keeps less, still preloads. */
		MEDIUM(100, true, R.string.video_cache_medium, R.string.video_cache_medium_short),
		/** Keeps little, no preload. */
		SMALL(50, false, R.string.video_cache_small, R.string.video_cache_small_short);

		/** Also accepts the old names. */
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
		/** With the size, for the list. */
		@StringRes
		public final int label;
		/** Name only, next to the setting. */
		@StringRes
		public final int shortLabel;

		VideoCache(int megabytes, boolean preloadsNext, @StringRes int label, @StringRes int shortLabel) {
			this.maxBytes = megabytes * 1024L * 1024L;
			this.preloadsNext = preloadsNext;
			this.label = label;
			this.shortLabel = shortLabel;
		}
	}

	/** Video cache setting, Large by default. */
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

	/** App colours. Video and Shorts stay dark in all of them. */
	public enum Theme {
		/** Follows the phone's dark mode. */
		SYSTEM(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, R.style.Theme_TestTube, R.drawable.ic_logo_mark, 0, R.string.theme_system),
		LIGHT(AppCompatDelegate.MODE_NIGHT_NO, R.style.Theme_TestTube, R.drawable.ic_logo_mark, 0, R.string.theme_light),
		/** Black and grey, black and white controls */
		DARK(AppCompatDelegate.MODE_NIGHT_YES, R.style.Theme_TestTube, R.drawable.ic_logo_mark, 0, R.string.theme_dark),
		/** niixa.org look: black, dark glass with a pink edge, pink accents, JetBrains Mono. Dark only. */
		NIIX(AppCompatDelegate.MODE_NIGHT_YES, R.style.Theme_TestTube_Niix, R.drawable.ic_logo_mark_niix, R.color.niix_accent, R.string.theme_niix);

		/** For {@link AppCompatDelegate#setDefaultNightMode(int)}. */
		public final int nightMode;
		/** Activity theme, set before the screen is built. */
		@StyleRes
		public final int style;
		/** In-app logo (NiiX has the pink play). */
		@DrawableRes
		public final int logo;
		/** Menu icon colour, 0 for the text colour. */
		@ColorRes
		public final int menuIcon;
		@StringRes
		public final int label;

		Theme(int nightMode, @StyleRes int style, @DrawableRes int logo, @ColorRes int menuIcon,
		      @StringRes int label) {
			this.nightMode = nightMode;
			this.style = style;
			this.logo = logo;
			this.menuIcon = menuIcon;
			this.label = label;
		}
	}

	/** Theme setting, follows the phone by default. */
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

	/** Comment box under videos when signed in. Off by default. */
	public static boolean commentingEnabled() {
		load();
		return commenting;
	}

	public static void setCommentingEnabled(boolean enabled) {
		load();
		commenting = enabled;
		MMKV.defaultMMKV().encode(KEY_COMMENTING, enabled);
	}

	/** Subscriptions tab. On by default. */
	public static boolean showSubscriptions() {
		load();
		return subscriptions;
	}

	public static void setShowSubscriptions(boolean show) {
		load();
		subscriptions = show;
		MMKV.defaultMMKV().encode(KEY_SUBSCRIPTIONS, show);
	}

	/** Dim watched videos to black and white. On by default. */
	public static boolean greyOutWatched() {
		load();
		return greyWatched;
	}

	public static void setGreyOutWatched(boolean grey) {
		load();
		greyWatched = grey;
		MMKV.defaultMMKV().encode(KEY_GREY_WATCHED, grey);
	}

	/** Dim level in percent: 20 is just greyscale, 90 almost black. */
	public static int watchedDimPercent() {
		load();
		return watchedDim;
	}

	/** {@link #watchedDimPercent()} as 0..1. */
	public static float watchedDarkness() {
		return (watchedDimPercent() - MIN_WATCHED_DIM) / (float) (MAX_WATCHED_DIM - MIN_WATCHED_DIM);
	}

	public static void setWatchedDimPercent(int percent) {
		load();
		watchedDim = clampDim(percent);
		MMKV.defaultMMKV().encode(KEY_WATCHED_DIM, watchedDim);
	}

	/** Clamped and snapped to the slider's steps. */
	private static int clampDim(int percent) {
		int stepped = Math.round(percent / (float) WATCHED_DIM_STEP) * WATCHED_DIM_STEP;
		return Math.max(MIN_WATCHED_DIM, Math.min(MAX_WATCHED_DIM, stepped));
	}

	/** Empty video and picture caches on close. Off by default. */
	public static boolean clearCacheOnExit() {
		load();
		return clearCacheOnExit;
	}

	public static void setClearCacheOnExit(boolean clear) {
		load();
		clearCacheOnExit = clear;
		MMKV.defaultMMKV().encode(KEY_CLEAR_CACHE_ON_EXIT, clear);
	}

	/**
	 * Play HDR (VP9.2, AV1 10-bit). Off by default: lots of phones, Samsung especially, show blue
	 * skin and washed out colours, so SDR wins when there is one.
	 */
	public static boolean allowHdr() {
		load();
		return allowHdr;
	}

	public static void setAllowHdr(boolean allow) {
		load();
		allowHdr = allow;
		MMKV.defaultMMKV().encode(KEY_ALLOW_HDR, allow);
	}

	/**
	 * H.264 only when available, every decoder gets it right. Off by default since it stops at
	 * 1080p and uses more data than VP9. For phones still showing wrong colours with HDR off.
	 */
	public static boolean preferAvc() {
		load();
		return preferAvc;
	}

	public static void setPreferAvc(boolean prefer) {
		load();
		preferAvc = prefer;
		MMKV.defaultMMKV().encode(KEY_PREFER_AVC, prefer);
	}

	/** Home is just the logo and search, no suggestions. Off by default. */
	public static boolean searchOnlyHome() {
		load();
		return searchOnlyHome;
	}

	public static void setSearchOnlyHome(boolean searchOnly) {
		load();
		searchOnlyHome = searchOnly;
		MMKV.defaultMMKV().encode(KEY_SEARCH_ONLY_HOME, searchOnly);
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
			// swipe-down-to-minimize off meant brightness and volume swipes
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
			clearCacheOnExit = store.decodeBool(KEY_CLEAR_CACHE_ON_EXIT, false);
			searchOnlyHome = store.decodeBool(KEY_SEARCH_ONLY_HOME, false);
			allowHdr = store.decodeBool(KEY_ALLOW_HDR, false);
			preferAvc = store.decodeBool(KEY_PREFER_AVC, false);
			loaded = true;
		}
	}
}
