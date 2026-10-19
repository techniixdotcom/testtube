package com.testtube.app;

import com.tencent.mmkv.MMKV;

/**
 * Choices made in the first-start dialog, changeable later from the logo menu. Read from list
 * parsing threads too, so the values are kept in volatile fields.
 */
public final class AppSettings {
	private static final String KEY_SHORTS = "show_shorts";
	private static final String KEY_CHANNEL_PAGES = "full_channel_pages";
	private static final String KEY_FLOATING_PLAYER = "floating_player";
	private static final String KEY_SWIPE_MINIMIZES = "swipe_down_minimizes";
	private static final String KEY_COMMENTING = "commenting";
	private static final String KEY_SUBSCRIPTIONS = "show_subscriptions";
	private static final String KEY_GREY_WATCHED = "grey_out_watched";
	private static final String KEY_WATCHED_DIM = "watched_dim_percent";
	private static final String KEY_WELCOME_VERSION = "welcome_version";
	private static final int MIN_WATCHED_DIM = 10;
	private static final int MAX_WATCHED_DIM = 90;
	private static final int WATCHED_DIM_STEP = 5;
	/** As dark as watched videos were before this became a setting. */
	private static final int DEFAULT_WATCHED_DIM = 70;
	/** Raised when the welcome dialog gets new choices, so everyone sees it once more. */
	private static final int WELCOME_VERSION = 4;

	private static volatile boolean loaded;
	private static volatile boolean shorts;
	private static volatile boolean channelPages = true;
	private static volatile boolean floatingPlayer;
	private static volatile boolean swipeMinimizes = true;
	private static volatile boolean commenting;
	private static volatile boolean subscriptions = true;
	private static volatile boolean greyWatched = true;
	private static volatile int watchedDim = DEFAULT_WATCHED_DIM;

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
	 * Leaving the app while a video plays keeps it in a picture-in-picture window. When off, only
	 * the sound keeps playing. Off by default.
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
	 * Swiping down on the video minimizes it. When off, vertical swipes change the brightness
	 * (left half) and the volume (right half).
	 */
	public static boolean swipeDownMinimizes() {
		load();
		return swipeMinimizes;
	}

	public static void setSwipeDownMinimizes(boolean minimizes) {
		load();
		swipeMinimizes = minimizes;
		MMKV.defaultMMKV().encode(KEY_SWIPE_MINIMIZES, minimizes);
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

	/** How much watched videos are darkened, in percent: 10 is barely, 90 almost gone. */
	public static int watchedDimPercent() {
		load();
		return watchedDim;
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
			swipeMinimizes = store.decodeBool(KEY_SWIPE_MINIMIZES, true);
			commenting = store.decodeBool(KEY_COMMENTING, false);
			subscriptions = store.decodeBool(KEY_SUBSCRIPTIONS, true);
			greyWatched = store.decodeBool(KEY_GREY_WATCHED, true);
			watchedDim = clampDim(store.decodeInt(KEY_WATCHED_DIM, DEFAULT_WATCHED_DIM));
			loaded = true;
		}
	}
}
