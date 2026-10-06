package com.testtube.app;

/**
 * Shared constants used across the app.
 */
public final class Constant {
	public static final String HOME_URL = "https://m.youtube.com";
	public static final String YOUTUBE_DOMAIN = "youtube.com";
	public static final String YOUTUBE_MOBILE_HOST = "m.youtube.com";
	public static final String PAGE_HOME = "home";
	public static final String PAGE_SHORTS = "shorts";
	public static final String PAGE_WATCH = "watch";
	public static final String PAGE_SUBSCRIPTIONS = "subscriptions";
	public static final String PAGE_LIBRARY = "library";
	public static final String SKIP_SPONSORS = "skip_sponsors";
	public static final String SKIP_SELF_PROMO = "skip_self_promo";
	public static final String SKIP_POI_HIGHLIGHT = "skip_poi_highlight";
	public static final String REMEMBER_LAST_POSITION = "remember_last_position";
	public static final String ENABLE_BACKGROUND_PLAY = "enable_background_play";
	public static final String ENABLE_PIP = "enable_pip";
	public static final String ENABLE_IN_APP_MINI_PLAYER = "enable_in_app_mini_player";
	public static final String ENABLE_AUTOPLAY_SUGGESTIONS = "enable_autoplay_suggestions";
	/**
	 * Set once at startup from the phone's WebView version (see UserAgents).
	 */
	public static volatile String CHROME_MAJOR = "140";
	public static volatile String USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36";

	private Constant() {
	}
}
