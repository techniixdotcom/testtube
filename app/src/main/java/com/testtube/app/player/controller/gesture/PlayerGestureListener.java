package com.testtube.app.player.controller.gesture;

import android.app.Activity;
import android.os.Handler;
import android.view.GestureDetector;
import android.view.MotionEvent;

import androidx.annotation.NonNull;
import androidx.media3.common.util.UnstableApi;

import com.testtube.app.player.TestTubePlayerView;
import com.testtube.app.player.controller.Controller;
import com.testtube.app.player.engine.Engine;

import java.util.Locale;

/**
 * Fixed gestures: tap toggles the controls, double tap seeks ten seconds or toggles playback,
 * horizontal swipes seek, swiping down minimizes to the bottom bar and swiping up opens
 * fullscreen.
 */
@UnstableApi
public class PlayerGestureListener extends GestureDetector.SimpleOnGestureListener {
	private static final int AUTO_HIDE_DELAY_MS = 200;
	private static final int SEEK_WINDOW_MS = 600;

	private final TestTubePlayerView playerView;
	private final Engine engine;
	private final Controller controller;
	private final Handler handler;
	private final Runnable hideHint;

	private GestureMode gestureMode = GestureMode.NONE;
	private boolean gesturing, swipeTriggered;
	private long seekStartPos;

	private int seekAccum;
	private final Runnable resetSeek = () -> seekAccum = 0;
	private long lastTapTime;

	public PlayerGestureListener(Activity activity, TestTubePlayerView playerView, Engine engine, Controller controller) {
		this.playerView = playerView;
		this.engine = engine;
		this.controller = controller;
		this.handler = new Handler(activity.getMainLooper());
		this.hideHint = controller::hideHint;
	}

	private static DoubleTapAction getDoubleTapAction(float x, float width) {
		if (width <= 0f) return DoubleTapAction.TOGGLE_PLAYBACK;
		if (x < width / 3f) return DoubleTapAction.SEEK_BACKWARD;
		if (x > width * 2f / 3f) return DoubleTapAction.SEEK_FORWARD;
		return DoubleTapAction.TOGGLE_PLAYBACK;
	}

	public void onTouchRelease() {
		if (gesturing) {
			handler.postDelayed(hideHint, AUTO_HIDE_DELAY_MS);
			gesturing = false;
		}
	}

	@Override
	public boolean onDown(@NonNull MotionEvent e) {
		handler.removeCallbacks(hideHint);
		gestureMode = GestureMode.NONE;
		gesturing = false;
		swipeTriggered = false;
		seekStartPos = engine.position();
		return true;
	}

	@Override
	public boolean onSingleTapUp(@NonNull MotionEvent e) {
		long now = System.currentTimeMillis();
		float x = e.getX();
		float width = playerView.getWidth();
		DoubleTapAction action = getDoubleTapAction(x, width);

		if (seekAccum != 0 && (now - lastTapTime < SEEK_WINDOW_MS)) {
			if ((seekAccum < 0 && action == DoubleTapAction.SEEK_BACKWARD)
							|| (seekAccum > 0 && action == DoubleTapAction.SEEK_FORWARD)) {
				processSeek(action == DoubleTapAction.SEEK_BACKWARD);
				lastTapTime = now;
				return true;
			}
		}
		return super.onSingleTapUp(e);
	}

	@Override
	public boolean onSingleTapConfirmed(@NonNull MotionEvent e) {
		controller.setControlsVisible(!controller.isControlsVisible());
		return true;
	}

	@Override
	public boolean onDoubleTap(@NonNull MotionEvent e) {
		switch (getDoubleTapAction(e.getX(), playerView.getWidth())) {
			case SEEK_BACKWARD:
				processSeek(true);
				lastTapTime = System.currentTimeMillis();
				return true;
			case SEEK_FORWARD:
				processSeek(false);
				lastTapTime = System.currentTimeMillis();
				return true;
			case TOGGLE_PLAYBACK:
				if (engine.isPlaying()) {
					engine.pause();
				} else {
					engine.play();
				}
				controller.setControlsVisible(true);
				return true;
			default:
				return false;
		}
	}

	private void processSeek(boolean isLeft) {
		handler.removeCallbacks(resetSeek);
		if (isLeft) {
			seekAccum -= 10;
			engine.seekBy(-10000);
			controller.showHint(seekAccum + "s", 500);
		} else {
			seekAccum += 10;
			engine.seekBy(10000);
			controller.showHint("+" + seekAccum + "s", 500);
		}
		handler.postDelayed(resetSeek, SEEK_WINDOW_MS);
	}

	@Override
	public boolean onScroll(MotionEvent e1, @NonNull MotionEvent e2, float dx, float dy) {
		if (e1 == null || e2.getPointerCount() > 1) return false;
		if (gestureMode == GestureMode.NONE) {
			if (Math.abs(dy) > Math.abs(dx)) {
				gestureMode = GestureMode.MINIMIZE;
			} else if (Math.abs(dx) > Math.abs(dy)) {
				gestureMode = GestureMode.SEEK;
			}
			if (gestureMode == GestureMode.NONE) return false;
		}
		gesturing = true;
		handler.removeCallbacks(hideHint);
		switch (gestureMode) {
			case MINIMIZE:
				handleMinimizeGesture(e1, e2);
				break;
			case SEEK:
				adjustSeek(e1, e2);
				break;
			case NONE:
				return false;
		}
		handler.postDelayed(hideHint, AUTO_HIDE_DELAY_MS);
		return true;
	}

	private void adjustSeek(MotionEvent e1, MotionEvent e2) {
		float width = playerView.getWidth();
		long offset = (long) (((e2.getX() - e1.getX()) / width) * 120000);
		long pos = seekStartPos + offset;
		engine.seekTo(pos);
		controller.showHint(formatTime(pos), -1);
	}

	private String formatTime(long ms) {
		if (ms < 0) ms = 0;
		int seconds = (int) (ms / 1000) % 60;
		int minutes = (int) ((ms / (1000 * 60)) % 60);
		int hours = (int) ((ms / (1000 * 60 * 60)) % 24);
		if (hours > 0)
			return String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds);
		return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds);
	}

	private void handleMinimizeGesture(@NonNull MotionEvent e1, @NonNull MotionEvent e2) {
		if (swipeTriggered) return;
		float dy = e2.getY() - e1.getY();
		float threshold = playerView.getHeight() * 0.12f;
		if (Math.abs(dy) < threshold) return;
		if (controller.isFullscreen()) {
			if (dy > 0) {
				swipeTriggered = true;
				controller.exitFullscreen();
			}
			return;
		}
		swipeTriggered = true;
		if (dy > 0) {
			controller.minimizeToBar();
		} else {
			controller.enterFullscreen();
		}
	}

	private enum GestureMode {
		NONE,
		SEEK,
		MINIMIZE
	}

	private enum DoubleTapAction {
		SEEK_BACKWARD,
		TOGGLE_PLAYBACK,
		SEEK_FORWARD
	}
}
