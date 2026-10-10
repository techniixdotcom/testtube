package com.testtube.app;

/**
 * Shared constants used across the app.
 */
public final class Constant {
	public static final String HOME_URL = "https://m.youtube.com";
	public static final String YOUTUBE_DOMAIN = "youtube.com";
	public static final String YOUTUBE_MOBILE_HOST = "m.youtube.com";
	public static final String PAGE_HOME = "home";
	public static final String PAGE_WATCH = "watch";
	public static final String PAGE_SUBSCRIPTIONS = "subscriptions";
	public static final String PAGE_LIBRARY = "library";
	/**
	 * Set once at startup from the phone's WebView version (see UserAgents).
	 */
	public static volatile String CHROME_MAJOR = "140";
	public static volatile String USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36";

	private Constant() {
	}
}
