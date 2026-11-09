package com.testtube.app;

/**
 * TestTube+: YouTube plus Spotify, Facebook and SoundCloud links. TestTube has its own copy (src/standard).
 */
public final class Edition {
	/** This is TestTube+. */
	public static final boolean PLUS = true;
	/**
	 * APK name prefix in a release, before the version: testtubeplus3.0.4.apk. The updater only takes
	 * its own edition.
	 */
	public static final String APK_PREFIX = "testtubeplus";

	private Edition() {
	}
}
