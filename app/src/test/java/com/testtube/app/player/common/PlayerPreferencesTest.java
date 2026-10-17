package com.testtube.app.player.common;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gson.Gson;
import com.tencent.mmkv.MMKV;

import org.junit.Test;

public class PlayerPreferencesTest {

	@Test
	public void getPreferredQuality_usesTheWifiQualityOnWifi() {
		MMKV mmkv = mock(MMKV.class);
		PlayerPreferences prefs = new PlayerPreferences(mmkv, new Gson(), () -> false);
		when(mmkv.decodeString("video_quality_wifi", null)).thenReturn("1080p");
		when(mmkv.decodeString("video_quality_mobile", null)).thenReturn("360p");

		assertEquals("1080p", prefs.getPreferredQuality());
	}

	@Test
	public void getPreferredQuality_usesTheMobileQualityOnMobileData() {
		MMKV mmkv = mock(MMKV.class);
		PlayerPreferences prefs = new PlayerPreferences(mmkv, new Gson(), () -> true);
		when(mmkv.decodeString("video_quality_wifi", null)).thenReturn("1080p");
		when(mmkv.decodeString("video_quality_mobile", null)).thenReturn("360p");

		assertEquals("360p", prefs.getPreferredQuality());
	}

	@Test
	public void getPreferredQuality_startsFromTheQualityOfOlderVersions() {
		MMKV mmkv = mock(MMKV.class);
		PlayerPreferences prefs = new PlayerPreferences(mmkv, new Gson(), () -> true);
		when(mmkv.decodeString("video_quality", null)).thenReturn("1080p");

		assertEquals("1080p", prefs.getPreferredQuality());
	}

	@Test
	public void getQuality_defaultsPerNetworkWhenNothingStored() {
		MMKV mmkv = mock(MMKV.class);
		PlayerPreferences prefs = new PlayerPreferences(mmkv, new Gson(), () -> false);
		when(mmkv.decodeString("video_quality_wifi", null)).thenReturn(" ");

		assertEquals("720p", prefs.getQuality(false));
		assertEquals("480p", prefs.getQuality(true));
	}

	@Test
	public void setPreferredQuality_savesForTheCurrentNetwork() {
		MMKV mmkv = mock(MMKV.class);
		PlayerPreferences prefs = new PlayerPreferences(mmkv, new Gson(), () -> true);

		prefs.setPreferredQuality("240p");

		verify(mmkv).encode("video_quality_mobile", "240p");
	}
}
