package com.testtube.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class UrlUtilsTest {

	@Test
	public void isYoutubeLink_acceptsOnlyHttpsYoutubeHosts() {
		assertFalse(UrlUtils.isYoutubeLink(null));
		assertFalse(UrlUtils.isYoutubeLink(""));
		assertFalse(UrlUtils.isYoutubeLink("invalid-url"));
		assertFalse(UrlUtils.isYoutubeLink("http://m.youtube.com/watch?v=abc"));
		assertFalse(UrlUtils.isYoutubeLink("https://malicious.com/phishing"));

		assertTrue(UrlUtils.isYoutubeLink("https://m.youtube.com/watch?v=abc"));
		assertTrue(UrlUtils.isYoutubeLink("https://youtu.be/abc"));
	}

	@Test
	public void getPageClass_recognisesTheNativeScreens() {
		assertEquals("home", UrlUtils.getPageClass("https://m.youtube.com/"));
		assertEquals("watch", UrlUtils.getPageClass("https://m.youtube.com/watch?v=abc"));
		assertEquals("watch", UrlUtils.getPageClass("https://m.youtube.com/shorts/abc"));
		assertEquals("subscriptions", UrlUtils.getPageClass("https://m.youtube.com/feed/subscriptions"));
		assertEquals("searching", UrlUtils.getPageClass("https://m.youtube.com/results?search_query=a"));
	}

	@Test
	public void isNativeChannelUrl_coversEveryChannelTab() {
		assertTrue(UrlUtils.isNativeChannelUrl("https://m.youtube.com/@name"));
		assertTrue(UrlUtils.isNativeChannelUrl("https://m.youtube.com/@name/shorts"));
		assertTrue(UrlUtils.isNativeChannelUrl("https://m.youtube.com/channel/UCabcdefghijklmnopqrstuv/playlists"));
		assertFalse(UrlUtils.isNativeChannelUrl("https://m.youtube.com/watch?v=abc"));
	}
}
