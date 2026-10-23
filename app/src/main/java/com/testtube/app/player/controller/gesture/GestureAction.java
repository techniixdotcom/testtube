package com.testtube.app.player.controller.gesture;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

import com.testtube.app.R;

/** What a gesture on the video does, as chosen in the settings. */
public final class GestureAction {
	private GestureAction() {
	}

	/** Swiping up or down on one half of the video. */
	public enum Vertical {
		BRIGHTNESS(R.string.gesture_brightness),
		VOLUME(R.string.gesture_volume),
		/** Down minimizes (to the bottom bar or the floating window), up opens fullscreen. */
		MINIMIZE(R.string.gesture_minimize),
		NONE(R.string.gesture_none);

		@StringRes
		public final int label;

		Vertical(@StringRes int label) {
			this.label = label;
		}
	}

	/** Swiping left or right on the video. */
	public enum Horizontal {
		SEEK(R.string.gesture_seek),
		NONE(R.string.gesture_none);

		@StringRes
		public final int label;

		Horizontal(@StringRes int label) {
			this.label = label;
		}
	}

	/** Double-tapping the video. */
	public enum DoubleTap {
		/** The left third goes back 10 seconds, the right third forward, the middle plays or pauses. */
		SKIP(R.string.gesture_double_tap_skip),
		PLAY_PAUSE(R.string.gesture_play_pause),
		NONE(R.string.gesture_none);

		@StringRes
		public final int label;

		DoubleTap(@StringRes int label) {
			this.label = label;
		}
	}

	/** The enum constant with this name, or the fallback for unknown or missing names. */
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
