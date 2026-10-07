package com.testtube.app.extension;

import static com.testtube.app.Constant.ENABLE_AUTOPLAY_SUGGESTIONS;
import static com.testtube.app.Constant.ENABLE_BACKGROUND_PLAY;
import static com.testtube.app.Constant.ENABLE_IN_APP_MINI_PLAYER;
import static com.testtube.app.Constant.ENABLE_PIP;
import static com.testtube.app.Constant.REMEMBER_LAST_POSITION;
import static com.testtube.app.Constant.SKIP_POI_HIGHLIGHT;
import static com.testtube.app.Constant.SKIP_SELF_PROMO;
import static com.testtube.app.Constant.SKIP_SPONSORS;

import java.util.Map;

/**
 * Preference keys and their hardcoded values. All behaviour is fixed;
 * the map only exists so the JavaScript bridge can keep reading it.
 */
public final class Constant {
	public static final String ENABLE_HIDE_SHORTS = "enable_hide_shorts";
	public static final String ENABLE_GREY_WATCHED = "enable_grey_watched";
	public static final String GESTURE_SWIPE_DOWN_MINIMIZE = "gesture_swipe_down_minimize";
	public static final String REMEMBER_QUALITY = "remember_quality";
	/**
	 * Watched progress percentage at which a video is greyed out.
	 */
	public static final int WATCHED_THRESHOLD_PERCENT = 75;
	public static final Map<String, Boolean> DEFAULT_PREFERENCES = Map.ofEntries(
					Map.entry(ENABLE_HIDE_SHORTS, true),
					Map.entry(ENABLE_GREY_WATCHED, true),
					Map.entry(GESTURE_SWIPE_DOWN_MINIMIZE, true),
					Map.entry(SKIP_SPONSORS, true),
					Map.entry(SKIP_SELF_PROMO, true),
					Map.entry(SKIP_POI_HIGHLIGHT, true),
					Map.entry(REMEMBER_LAST_POSITION, true),
					Map.entry(ENABLE_AUTOPLAY_SUGGESTIONS, true),
					Map.entry(REMEMBER_QUALITY, true),
					Map.entry(ENABLE_BACKGROUND_PLAY, true),
					Map.entry(ENABLE_PIP, true),
					Map.entry(ENABLE_IN_APP_MINI_PLAYER, true)
	);

	private Constant() {
	}
}
