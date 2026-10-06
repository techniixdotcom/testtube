package com.testtube.app.extractor.potoken;

import androidx.annotation.Nullable;

import org.schabi.newpipe.extractor.services.youtube.PoTokenProvider;
import org.schabi.newpipe.extractor.services.youtube.PoTokenResult;


/**
 * Provider that feeds PoToken data into extraction.
 */
public final class TestTubePoTokenProvider implements PoTokenProvider {
	private final PoTokenCoordinator coordinator;

	public TestTubePoTokenProvider(PoTokenCoordinator coordinator) {
		this.coordinator = coordinator;
	}

	@Override
	@Nullable
	public PoTokenResult getWebClientPoToken(String videoId) {
		return coordinator.getWebClientPoToken(videoId);
	}
}
