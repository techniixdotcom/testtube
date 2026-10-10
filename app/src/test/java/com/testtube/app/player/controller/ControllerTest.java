package com.testtube.app.player.controller;

import static org.junit.Assert.assertEquals;

import android.content.pm.ActivityInfo;

import org.junit.Test;

public class ControllerTest {

	@Test
	public void fullscreenLocksToTheVideoOrientation() {
		assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT, Controller.fsOrientation(true));
		assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, Controller.fsOrientation(false));
	}
}
