package com.testtube.app.extractor;

import androidx.annotation.Nullable;


/**
 * Scope annotation for extractor session dependencies.
 */
public final class ExtractionSessionScope {
	private final InheritableThreadLocal<ExtractionSession> session = new InheritableThreadLocal<>();

	public ExtractionSessionScope() {
	}

	@Nullable
	public ExtractionSession get() {
		return session.get();
	}

	public void set(@Nullable ExtractionSession session) {
		if (session == null) {
			this.session.remove();
			return;
		}
		this.session.set(session);
	}
}
