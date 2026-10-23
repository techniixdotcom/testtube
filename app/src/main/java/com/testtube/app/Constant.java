package com.testtube.app;

public final class Constant {
	public static final String HOME_URL = "https://m.youtube.com";
	public static final String YOUTUBE_DOMAIN = "youtube.com";
	public static final String YOUTUBE_MOBILE_HOST = "m.youtube.com";
	public static final String PAGE_HOME = "home";
	public static final String PAGE_WATCH = "watch";
	public static final String PAGE_SUBSCRIPTIONS = "subscriptions";
	public static final String PAGE_LIBRARY = "library";
	/**
	 * Set once at startup from the phone's WebView version (see UserAgents). Every request claims
	 * this same Chrome version: the mobile form for m.youtube.com and the WebViews, the desktop
	 * form for www.youtube.com and the WEB InnerTube client (which BotGuard also runs as).
	 */
	public static volatile String CHROME_MAJOR = "140";
	public static volatile String USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36";
	public static volatile String DESKTOP_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36";

	private Constant() {
	}
}
