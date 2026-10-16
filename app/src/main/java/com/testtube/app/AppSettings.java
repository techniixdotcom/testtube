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
	private static final String KEY_WELCOME_VERSION = "welcome_version";
	/** Raised when the welcome dialog gets new choices, so everyone sees it once more. */
	private static final int WELCOME_VERSION = 2;

	private static volatile boolean loaded;
	private static volatile boolean shorts;
	private static volatile boolean channelPages = true;
	private static volatile boolean floatingPlayer;
	private static volatile boolean swipeMinimizes = true;

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
	 * Minimizing the player (back or swipe down) opens a picture-in-picture window instead of the
	 * bottom bar. Off by default.
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
			loaded = true;
		}
	}
}
