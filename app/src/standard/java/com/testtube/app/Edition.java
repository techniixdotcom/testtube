package com.testtube.app;

/** TestTube: YouTube only. TestTube+ has its own copy (src/plus). */
public final class Edition {
	/** This is TestTube+. */
	public static final boolean PLUS = false;
	/**
	 * APK name prefix in a release, before the version: testtube3.0.4.apk. The updater only takes its
	 * own edition.
	 */
	public static final String APK_PREFIX = "testtube";

	private Edition() {
	}
}
