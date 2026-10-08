package com.testtube.app.player.engine;

import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultLoadControl;

@OptIn(markerClass = UnstableApi.class)
class PlayerLoadControl {
	private PlayerLoadControl() {
	}

	static DefaultLoadControl create() {
		return new DefaultLoadControl.Builder()
						.setBufferDurationsMs(
										15_000, // min buffer
										30_000, // max buffer
										1_500,  // needed to start
										4_000   // needed after a rebuffer
						)
						.setPrioritizeTimeOverSizeThresholds(true)
						.build();
	}
}
