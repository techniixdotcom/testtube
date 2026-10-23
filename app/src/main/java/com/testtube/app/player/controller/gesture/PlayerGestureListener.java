package com.testtube.app.player.controller.gesture;

import android.app.Activity;
import android.media.AudioManager;
import android.os.Handler;
import android.provider.Settings;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;

import com.testtube.app.AppSettings;
import com.testtube.app.R;
import com.testtube.app.player.TestTubePlayerView;
import com.testtube.app.player.controller.Controller;
import com.testtube.app.player.engine.Engine;

import java.util.Locale;

/**
 * Tap toggles the controls. The rest follows the gesture settings ({@link AppSettings}): by default
 * double tap skips ten seconds (or plays and pauses in the middle), horizontal swipes seek, and
 * vertical swipes drag the video down to minimize it (to the bottom bar or the floating window) or
 * up to fullscreen. With custom gestures each half of the video can instead change the brightness
 * or the volume, or do nothing. In fullscreen, vertical swipes always change the brightness (left)
 * and the volume (right).
 */
@UnstableApi
public class PlayerGestureListener extends GestureDetector.SimpleOnGestureListener {
	private static final int AUTO_HIDE_DELAY_MS = 200;
	private static final int SEEK_WINDOW_MS = 600;
	/** Fully dark would leave nothing to see the next swipe on. */
	private static final float MIN_BRIGHTNESS = 0.01f;

	private final Activity activity;
	private final TestTubePlayerView playerView;
	private final Engine engine;
	private final Controller controller;
	private final Handler handler;
	private final Runnable hideHint;

	private GestureMode gestureMode = GestureMode.NONE;
	private boolean gesturing, swipeTriggered;
	private long seekStartPos;
	/** Brightness (0 to 1) or volume (stream steps) when the current swipe started. */
	private float levelAtStart;

	private int seekAccum;
	private final Runnable resetSeek = () -> seekAccum = 0;
	private long lastTapTime;
	@Nullable
	private Runnable longPressAction;
	@Nullable
	private WatchDrag watchDrag;
	/** The finger is dragging the video; its moves go to {@link #onDragTouch} instead of the detector. */
	private boolean dragging;
	private float dragStartY;
	@Nullable
	private VelocityTracker velocity;

	public PlayerGestureListener(Activity activity, TestTubePlayerView playerView, Engine engine, Controller controller) {
		this.activity = activity;
		this.playerView = playerView;
		this.engine = engine;
		this.controller = controller;
		this.handler = new Handler(activity.getMainLooper());
		this.hideHint = controller::hideHint;
	}

	private static DoubleTapAction getDoubleTapAction(float x, float width) {
		switch (AppSettings.gestureDoubleTap()) {
			case NONE:
				return DoubleTapAction.NOTHING;
			case PLAY_PAUSE:
				return DoubleTapAction.TOGGLE_PLAYBACK;
			default:
				break;
		}
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

	public void setWatchDrag(@Nullable WatchDrag drag) {
		this.watchDrag = drag;
	}

	public boolean isDragging() {
		return dragging;
	}

	/**
	 * Follows the finger once a drag started. Screen coordinates are used because the video itself
	 * moves and shrinks under the finger.
	 */
	public void onDragTouch(@NonNull MotionEvent ev) {
		WatchDrag drag = watchDrag;
		if (drag == null) {
			stopDragging();
			return;
		}
		track(ev);
		switch (ev.getActionMasked()) {
			case MotionEvent.ACTION_MOVE -> drag.moveBy(ev.getRawY() - dragStartY);
			case MotionEvent.ACTION_UP -> {
				float speed = 0f;
				if (velocity != null) {
					velocity.computeCurrentVelocity(1000);
					speed = velocity.getYVelocity();
				}
				stopDragging();
				drag.release(speed);
			}
			case MotionEvent.ACTION_CANCEL -> {
				stopDragging();
				drag.release(0f);
			}
			default -> {
			}
		}
	}

	private void track(@NonNull MotionEvent ev) {
		if (velocity == null) velocity = VelocityTracker.obtain();
		MotionEvent screen = MotionEvent.obtain(ev);
		screen.setLocation(ev.getRawX(), ev.getRawY());
		velocity.addMovement(screen);
		screen.recycle();
	}

	private void stopDragging() {
		dragging = false;
		gesturing = false;
		if (velocity != null) {
			velocity.recycle();
			velocity = null;
		}
	}

	/** Long press on the video; opens the same menu as in the lists. */
	public void setLongPressAction(@Nullable Runnable action) {
		this.longPressAction = action;
	}

	@Override
	public void onLongPress(@NonNull MotionEvent e) {
		if (longPressAction == null || gesturing) return;
		playerView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
		longPressAction.run();
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
				gestureMode = verticalMode(e1);
			} else if (Math.abs(dx) > Math.abs(dy)) {
				gestureMode = AppSettings.gestureHorizontal() == GestureAction.Horizontal.SEEK
								? GestureMode.SEEK : GestureMode.IGNORED;
			}
			if (gestureMode == GestureMode.NONE) return false;
		}
		if (gestureMode == GestureMode.IGNORED) return true;
		gesturing = true;
		handler.removeCallbacks(hideHint);
		switch (gestureMode) {
			case MINIMIZE:
				handleMinimizeGesture(e1, e2);
				break;
			case SEEK:
				adjustSeek(e1, e2);
				break;
			case BRIGHTNESS:
				adjustBrightness(e1, e2);
				break;
			case VOLUME:
				adjustVolume(e1, e2);
				break;
			case NONE:
			case IGNORED:
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
		boolean fullscreen = controller.isFullscreen();
		WatchDrag drag = watchDrag;
		if (dy > 0 && !fullscreen && drag != null) {
			swipeTriggered = true;
			if (!drag.begin()) return;
			dragging = true;
			dragStartY = e2.getRawY();
			controller.setControlsVisible(false);
			controller.hideHint();
			track(e2);
			return;
		}
		if (Math.abs(dy) < playerView.getHeight() * 0.12f) return;
		if (fullscreen) {
			if (dy > 0) {
				swipeTriggered = true;
				controller.exitFullscreen();
			}
			return;
		}
		if (dy < 0) {
			swipeTriggered = true;
			controller.enterFullscreen();
		}
	}

	/**
	 * What a vertical swipe does on the half of the video where it started. In fullscreen it is
	 * always brightness (left) and volume (right), whatever the settings say; Back or the button
	 * in the corner leave fullscreen.
	 */
	@NonNull
	private GestureMode verticalMode(@NonNull MotionEvent start) {
		boolean left = start.getX() < playerView.getWidth() / 2f;
		GestureAction.Vertical action;
		if (controller.isFullscreen()) {
			action = left ? GestureAction.Vertical.BRIGHTNESS : GestureAction.Vertical.VOLUME;
		} else {
			action = left ? AppSettings.gestureLeft() : AppSettings.gestureRight();
		}
		switch (action) {
			case MINIMIZE:
				return GestureMode.MINIMIZE;
			case BRIGHTNESS:
				levelAtStart = currentBrightness();
				return GestureMode.BRIGHTNESS;
			case VOLUME: {
				AudioManager audio = audioManager();
				if (audio == null) return GestureMode.IGNORED;
				levelAtStart = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
				return GestureMode.VOLUME;
			}
			default:
				return GestureMode.IGNORED;
		}
	}

	/** Fraction of the player height swiped upwards since the gesture started (negative when down). */
	private float swipedUp(@NonNull MotionEvent e1, @NonNull MotionEvent e2) {
		float height = playerView.getHeight();
		return height <= 0f ? 0f : (e1.getY() - e2.getY()) / height;
	}

	private void adjustBrightness(@NonNull MotionEvent e1, @NonNull MotionEvent e2) {
		float level = Math.max(MIN_BRIGHTNESS, Math.min(1f, levelAtStart + swipedUp(e1, e2)));
		Window window = activity.getWindow();
		WindowManager.LayoutParams params = window.getAttributes();
		params.screenBrightness = level;
		window.setAttributes(params);
		controller.showHint(activity.getString(R.string.player_brightness, Math.round(level * 100)), -1);
	}

	private void adjustVolume(@NonNull MotionEvent e1, @NonNull MotionEvent e2) {
		AudioManager audio = audioManager();
		if (audio == null) return;
		int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
		if (max <= 0) return;
		int volume = Math.round(levelAtStart + swipedUp(e1, e2) * max);
		volume = Math.max(0, Math.min(max, volume));
		if (volume != audio.getStreamVolume(AudioManager.STREAM_MUSIC)) {
			audio.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0);
		}
		controller.showHint(activity.getString(R.string.player_volume, Math.round(volume * 100f / max)), -1);
	}

	/** The window's own brightness if one was set, otherwise the system setting. */
	private float currentBrightness() {
		float window = activity.getWindow().getAttributes().screenBrightness;
		if (window >= 0f) return window;
		try {
			int system = Settings.System.getInt(activity.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS);
			return Math.max(MIN_BRIGHTNESS, Math.min(1f, system / 255f));
		} catch (Settings.SettingNotFoundException e) {
			return 0.5f;
		}
	}

	/** Gives the brightness back to the system, e.g. when the player is minimized or closed. */
	public void resetBrightness() {
		Window window = activity.getWindow();
		WindowManager.LayoutParams params = window.getAttributes();
		if (params.screenBrightness == WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) return;
		params.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
		window.setAttributes(params);
	}

	@Nullable
	private AudioManager audioManager() {
		return activity.getSystemService(AudioManager.class);
	}

	private enum GestureMode {
		NONE,
		/** A swipe whose gesture is switched off: swallowed until the finger lifts. */
		IGNORED,
		SEEK,
		MINIMIZE,
		BRIGHTNESS,
		VOLUME
	}

	private enum DoubleTapAction {
		NOTHING,
		SEEK_BACKWARD,
		TOGGLE_PLAYBACK,
		SEEK_FORWARD
	}
}
