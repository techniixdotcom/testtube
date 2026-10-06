package com.testtube.app.player.common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.gson.Gson;
import com.tencent.mmkv.MMKV;

import org.junit.Test;

public class PlayerPreferencesTest {

	@Test
	public void getPreferredQuality_returnsStoredVisibleQuality() {
		MMKV mmkv = mock(MMKV.class);
		PlayerPreferences prefs = new PlayerPreferences(mmkv, new Gson());
		when(mmkv.decodeString("video_quality", null)).thenReturn("1080p");

		assertEquals("1080p", prefs.getPreferredQuality());
	}

	@Test
	public void getPreferredQuality_treatsBlankStoredQualityAsEmpty() {
		MMKV mmkv = mock(MMKV.class);
		PlayerPreferences prefs = new PlayerPreferences(mmkv, new Gson());
		when(mmkv.decodeString("video_quality", null)).thenReturn(" ");

		assertNull(prefs.getPreferredQuality());
	}
}
