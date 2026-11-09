package com.testtube.app.player.controller.gesture;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

import com.testtube.app.R;

/** What a video gesture does, per settings. */
public final class GestureAction {
	private GestureAction() {
	}

	/** Up/down on one half. */
	public enum Vertical {
		BRIGHTNESS(R.string.gesture_brightness),
		VOLUME(R.string.gesture_volume),
		/** Down minimizes (bar or floating window), up goes fullscreen. */
		MINIMIZE(R.string.gesture_minimize),
		NONE(R.string.gesture_none);

		@StringRes
		public final int label;

		Vertical(@StringRes int label) {
			this.label = label;
		}
	}

	/** Left/right swipe. */
	public enum Horizontal {
		SEEK(R.string.gesture_seek),
		NONE(R.string.gesture_none);

		@StringRes
		public final int label;

		Horizontal(@StringRes int label) {
			this.label = label;
		}
	}

	/** Double tap. */
	public enum DoubleTap {
		/** Left third back 10 s, right third forward, middle play/pause. */
		SKIP(R.string.gesture_double_tap_skip),
		PLAY_PAUSE(R.string.gesture_play_pause),
		NONE(R.string.gesture_none);

		@StringRes
		public final int label;

		DoubleTap(@StringRes int label) {
			this.label = label;
		}
	}

	/** Constant with this name, or the fallback. */
	@NonNull
	public static <E extends Enum<E>> E parse(@NonNull Class<E> type, String name, @NonNull E fallback) {
		if (name == null) return fallback;
		try {
			return Enum.valueOf(type, name);
		} catch (IllegalArgumentException e) {
			return fallback;
		}
	}
}
