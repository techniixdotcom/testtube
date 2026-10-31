package com.testtube.app.net;

import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.concurrent.Callable;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;

/**
 * Keeps the app's requests to YouTube at the pace of a person using the site rather than a
 * scraper: a few at a time, short bursts, a small random gap between background requests, and a
 * pause after YouTube says "slow down" (HTTP 429 or the "confirm you're not a bot" check).
 * <p>
 * What the person tapped (a video, a page) always goes first: background work such as refreshing
 * Subscriptions leaves part of the budget free and is the first to wait, or to give up during
 * a long pause. Video and audio downloads from googlevideo are not paced here.
 */
public final class RequestPacer {
	private static final String TAG = "RequestPacer";
	/** At most this many requests at once, like a browser's connections to one site. */
	private static final int MAX_PARALLEL = 6;
	/** Background work gets at most this many of them. */
	private static final int MAX_PARALLEL_BACKGROUND = 3;
	/** A burst of this many requests can start at once (a page load)… */
	private static final double BUCKET_SIZE = 10;
	/** …after which they start at this rate. */
	private static final double REFILL_PER_SECOND = 4;
	/** Background requests leave this many starts for what the person does next. */
	private static final double FOREGROUND_RESERVE = 3;
	private static final long BACKGROUND_GAP_MIN_MS = 60;
	private static final long BACKGROUND_GAP_MAX_MS = 260;
	/** Longest a tapped request waits out a pause before trying anyway. */
	private static final long FOREGROUND_MAX_WAIT_MS = 4_000;
	/** Longest pause background work waits out; on a longer one it fails and tries later. */
	private static final long BACKGROUND_MAX_WAIT_MS = 30_000;
	private static final long FIRST_PAUSE_MS = 5_000;
	private static final long MAX_PAUSE_MS = 120_000;
	/** After this long without trouble, the next pause starts short again. */
	private static final long CALM_AFTER_MS = 5 * 60_000;
	private static final long POLL_MS = 50;

	private static final ThreadLocal<Boolean> BACKGROUND = ThreadLocal.withInitial(() -> false);
	private static final Object LOCK = new Object();
	private static double tokens = BUCKET_SIZE;
	private static long refilledAt = SystemClock.elapsedRealtime();
	private static int running;
	private static int runningBackground;
	private static long pausedUntil;
	private static long lastTroubleAt;
	private static int strikes;

	private RequestPacer() {
	}

	/** Thrown to background requests while YouTube asked for a pause. */
	public static final class PausedException extends IOException {
		PausedException(long remainingMs) {
			super("YouTube asked for a pause; " + remainingMs + " ms left");
		}
	}

	@FunctionalInterface
	public interface IoCallable<T> {
		T call() throws Exception;
	}

	/** Runs work whose requests are background requests (lower priority, first to wait). */
	public static <T> T background(@NonNull IoCallable<T> work) throws Exception {
		boolean previous = BACKGROUND.get();
		BACKGROUND.set(true);
		try {
			return work.call();
		} finally {
			BACKGROUND.set(previous);
		}
	}

	/** Same as {@link #background(IoCallable)} for a {@link Callable}, e.g. a pool task. */
	@NonNull
	public static <T> Callable<T> inBackground(@NonNull IoCallable<T> work) {
		return () -> background(work);
	}

	public static boolean isBackground() {
		return BACKGROUND.get();
	}

	/** A started request; close it when the response has been read. */
	public static final class Slot implements AutoCloseable {
		private final boolean background;
		private boolean closed;

		private Slot(boolean background) {
			this.background = background;
		}

		@Override
		public void close() {
			synchronized (LOCK) {
				if (closed) return;
				closed = true;
				running--;
				if (background) runningBackground--;
				LOCK.notifyAll();
			}
		}
	}

	/**
	 * Waits until the request may start.
	 *
	 * @param cancelled      stops the wait when the request is no longer wanted
	 * @param backgroundWork the request belongs to background work even if this thread isn't
	 *                       marked with {@link #background(IoCallable)}
	 */
	@NonNull
	public static Slot acquire(@Nullable BooleanSupplier cancelled, boolean backgroundWork) throws IOException {
		boolean background = backgroundWork || BACKGROUND.get();
		// Never block the UI thread; such requests are rare and short
		if (Looper.myLooper() == Looper.getMainLooper()) return claim(background);
		if (background) sleep(randomBetween(BACKGROUND_GAP_MIN_MS, BACKGROUND_GAP_MAX_MS), cancelled);
		long foregroundDeadline = SystemClock.elapsedRealtime() + FOREGROUND_MAX_WAIT_MS;
		while (true) {
			if (cancelled != null && cancelled.getAsBoolean()) {
				throw new InterruptedIOException("Request canceled while waiting");
			}
			long waitMs;
			synchronized (LOCK) {
				long now = SystemClock.elapsedRealtime();
				refill(now);
				long pause = pausedUntil - now;
				// Background work waits out a short pause and gives up on a long one
				if (pause > BACKGROUND_MAX_WAIT_MS && background) throw new PausedException(pause);
				boolean pauseOver = pause <= 0 || (!background && now >= foregroundDeadline);
				boolean hasRoom = running < MAX_PARALLEL
								&& (!background || runningBackground < MAX_PARALLEL_BACKGROUND);
				double needed = background ? FOREGROUND_RESERVE + 1 : 1;
				if (pauseOver && hasRoom && tokens >= needed) {
					tokens -= 1;
					return claim(background);
				}
				if (!pauseOver) {
					waitMs = background ? pause : Math.min(pause, foregroundDeadline - now);
				} else if (tokens < needed) {
					waitMs = (long) Math.ceil((needed - tokens) / REFILL_PER_SECOND * 1000);
				} else {
					// All slots taken: Slot.close() wakes this the moment one is free
					try {
						LOCK.wait(POLL_MS * 2);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						throw new InterruptedIOException("Interrupted while waiting to send a request");
					}
					continue;
				}
			}
			sleep(Math.max(POLL_MS / 2, Math.min(waitMs, 500)), cancelled);
		}
	}

	/**
	 * YouTube answered 429 or asked to confirm this isn't a bot: pause for a while, longer each
	 * time it happens again soon.
	 *
	 * @param retryAfterMs the server's Retry-After, or 0
	 */
	public static void onSlowDown(long retryAfterMs) {
		synchronized (LOCK) {
			long now = SystemClock.elapsedRealtime();
			if (now - lastTroubleAt > CALM_AFTER_MS) strikes = 0;
			lastTroubleAt = now;
			long pause = Math.min(MAX_PAUSE_MS, FIRST_PAUSE_MS * (long) Math.pow(3, strikes));
			strikes++;
			// ±20 % so retries from many phones don't line up
			pause = Math.max(retryAfterMs, (long) (pause * (0.8 + 0.4 * ThreadLocalRandom.current().nextDouble())));
			pausedUntil = Math.max(pausedUntil, now + pause);
			tokens = 0;
			Log.i(TAG, "pausing YouTube requests for " + pause + " ms");
		}
	}

	/** Milliseconds left of the current pause, or 0. */
	public static long pauseRemainingMs() {
		synchronized (LOCK) {
			return Math.max(0L, pausedUntil - SystemClock.elapsedRealtime());
		}
	}

	@NonNull
	private static Slot claim(boolean background) {
		synchronized (LOCK) {
			running++;
			if (background) runningBackground++;
		}
		return new Slot(background);
	}

	private static void refill(long now) {
		tokens = Math.min(BUCKET_SIZE, tokens + (now - refilledAt) / 1000.0 * REFILL_PER_SECOND);
		refilledAt = now;
	}

	private static long randomBetween(long min, long max) {
		return ThreadLocalRandom.current().nextLong(min, max + 1);
	}

	private static void sleep(long ms, @Nullable BooleanSupplier cancelled) throws InterruptedIOException {
		long until = SystemClock.elapsedRealtime() + ms;
		try {
			while (true) {
				if (cancelled != null && cancelled.getAsBoolean()) {
					throw new InterruptedIOException("Request canceled while waiting");
				}
				long left = until - SystemClock.elapsedRealtime();
				if (left <= 0) return;
				Thread.sleep(Math.min(left, POLL_MS * 2));
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new InterruptedIOException("Interrupted while waiting to send a request");
		}
	}
}
