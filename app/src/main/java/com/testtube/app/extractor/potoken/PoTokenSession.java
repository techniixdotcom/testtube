package com.testtube.app.extractor.potoken;

final class PoTokenSession {
	private final long hostGeneration;
	private final long expiresAtMs;

	PoTokenSession(long hostGeneration,
	               final long expiresAtMs) {
		this.hostGeneration = hostGeneration;
		this.expiresAtMs = expiresAtMs;
	}

	boolean matches(long expectedHostGeneration) {
		return hostGeneration == expectedHostGeneration;
	}

	boolean isExpired(long nowMs) {
		return nowMs >= expiresAtMs;
	}
}
