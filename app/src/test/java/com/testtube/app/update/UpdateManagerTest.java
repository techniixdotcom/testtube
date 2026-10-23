package com.testtube.app.update;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class UpdateManagerTest {
	@Test
	public void releasesCompareByNumber() {
		assertTrue(UpdateManager.isNewer("v2.1.0", "v2.0.2"));
		assertTrue(UpdateManager.isNewer("v1.10.0", "v1.9.3"));
		assertFalse(UpdateManager.isNewer("v2.0.2", "v2.1.0"));
		assertFalse(UpdateManager.isNewer("v2.1.0", "v2.1.0"));
		assertFalse(UpdateManager.isNewer("v2.1", "v2.1.0"));
	}

	@Test
	public void preReleaseComesBeforeItsRelease() {
		assertTrue(UpdateManager.isNewer("v2.1.1-beta1", "v2.1.0"));
		assertTrue(UpdateManager.isNewer("v2.1.1", "v2.1.1-beta1"));
		assertFalse(UpdateManager.isNewer("v2.1.1-beta1", "v2.1.1"));
		assertTrue(UpdateManager.isNewer("v2.2.0", "v2.1.1-beta1"));
		assertFalse(UpdateManager.isNewer("v2.1.1-beta1", "v2.2.0"));
	}

	@Test
	public void preReleasesCompareByTheirNumber() {
		assertTrue(UpdateManager.isNewer("v2.1.1-beta2", "v2.1.1-beta1"));
		assertTrue(UpdateManager.isNewer("v2.1.1-beta10", "v2.1.1-beta9"));
		assertTrue(UpdateManager.isNewer("v2.1.1-rc1", "v2.1.1-beta3"));
	}

	@Test
	public void tagPrefixesAreIgnored() {
		assertTrue(UpdateManager.isNewer("v.2.1.1", "v2.1.0"));
		assertTrue(UpdateManager.isNewer("2.1.1", "v2.1.0"));
		assertTrue(UpdateManager.isNewer("V2.1.1", "v2.1.0"));
		assertTrue(UpdateManager.isNewer("version-2.1.1", "v2.1.0"));
		assertFalse(UpdateManager.isNewer("v.2.1.0", "v2.1.0"));
		assertFalse(UpdateManager.isNewer("v.2.1.0", "v2.1.1"));
	}

	@Test
	public void unreadableTagsAreNeverNewer() {
		assertFalse(UpdateManager.isNewer("garbage", "v2.1.0"));
	}
}
