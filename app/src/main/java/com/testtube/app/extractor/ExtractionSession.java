package com.testtube.app.extractor;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

/** Auth state and cancellation of one extraction. */
public final class ExtractionSession {
	private final Object lock = new Object();
	private final List<Cancellable> cancellables = new ArrayList<>();
	@NonNull
	private final AuthContext auth;
	private boolean cancelled = false;
	/** Prefetching or batch work: its requests yield to what the person is doing (see RequestPacer). */
	private volatile boolean background;

	public ExtractionSession() {
		this(new AuthContext("none", null, null, null, null, null, false, false, 0L));
	}

	public ExtractionSession(@NonNull AuthContext auth) {
		this.auth = auth;
	}

	@NonNull
	public AuthContext getAuth() {
		return auth;
	}

	public boolean isBackground() {
		return background;
	}

	@NonNull
	public ExtractionSession setBackground(boolean background) {
		this.background = background;
		return this;
	}

	public boolean isCancelled() {
		synchronized (lock) {
			return cancelled;
		}
	}

	public void register(@NonNull Cancellable cancellable) {
		synchronized (lock) {
			if (!cancelled) {
				cancellables.add(cancellable);
				return;
			}
		}
		cancellable.cancel();
	}

	public void cancel() {
		List<Cancellable> pending;
		synchronized (lock) {
			if (cancelled) return;
			cancelled = true;
			pending = new ArrayList<>(cancellables);
			cancellables.clear();
		}
		for (Cancellable cancellable : pending) {
			cancellable.cancel();
		}
	}

	@FunctionalInterface
	public interface Cancellable {
		void cancel();
	}
}
