package com.testtube.app.extractor.potoken;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Store for the latest PoToken context.
 */
public final class PoTokenContextStore {
	@NonNull
	private final AtomicReference<PoTokenWebViewContext> snapshot = new AtomicReference<>();

	public PoTokenContextStore() {
	}

	public void update(@Nullable PoTokenWebViewContext nextSnapshot) {
		if (nextSnapshot != null) {
			snapshot.set(nextSnapshot);
		}
	}

	@Nullable
	public PoTokenWebViewContext getSnapshot() {
		return snapshot.get();
	}
}
