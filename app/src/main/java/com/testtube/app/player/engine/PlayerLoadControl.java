package com.testtube.app.player.engine;

import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultLoadControl;

@OptIn(markerClass = UnstableApi.class)
class PlayerLoadControl {
	private PlayerLoadControl() {
	}

	/** Starts after 1 s of video; after a stall waits for 4 so it doesn't stall again right away. */
	private static final int START_BUFFER_MS = 1_000;
	private static final int REBUFFER_MS = 4_000;
	private static final int MIN_BUFFER_MS = 15_000;
	private static final int MAX_BUFFER_MS = 30_000;

	static DefaultLoadControl create() {
		return new DefaultLoadControl.Builder()
						.setBufferDurationsMs(MIN_BUFFER_MS, MAX_BUFFER_MS, START_BUFFER_MS, REBUFFER_MS)
						.setPrioritizeTimeOverSizeThresholds(true)
						.build();
	}
}
