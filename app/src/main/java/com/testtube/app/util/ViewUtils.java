package com.testtube.app.util;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.AttrRes;
import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;

public final class ViewUtils {
	private static final float ALPHA_VISIBLE = 1.0f;
	private static final float ALPHA_INVISIBLE = 0.0f;
	/** Length of the fade used when a screen or a page is swapped. */
	private static final long FADE_IN_MS = 160L;

	private ViewUtils() {
	}

	/** A colour of the current theme, such as {@code R.attr.colorPrimary}. */
	@ColorInt
	public static int themeColor(@NonNull Context context, @AttrRes int attr) {
		TypedValue value = new TypedValue();
		context.getTheme().resolveAttribute(attr, value, true);
		return value.data;
	}

	/** Gives a TextView's icons (drawableStart, drawableTop…) one square size. */
	public static void sizeCompoundDrawables(@NonNull TextView view, float sizeDp) {
		int size = dpToPx(view.getContext(), sizeDp);
		Drawable[] drawables = view.getCompoundDrawablesRelative();
		for (Drawable drawable : drawables) {
			if (drawable != null) drawable.setBounds(0, 0, size, size);
		}
		view.setCompoundDrawablesRelative(drawables[0], drawables[1], drawables[2], drawables[3]);
	}

	/** Shows a view that was just swapped in with a short fade, so screens don't pop. */
	public static void fadeIn(@NonNull View view) {
		view.animate().cancel();
		view.setAlpha(ALPHA_INVISIBLE);
		view.animate().alpha(ALPHA_VISIBLE).setDuration(FADE_IN_MS).start();
	}

	public static int dpToPx(@NonNull Context context, float dp) {
		return (int) (dp * context.getResources().getDisplayMetrics().density);
	}

	public static int getScreenWidth(@NonNull Context context) {
		return context.getResources().getDisplayMetrics().widthPixels;
	}

	public static void animateViewAlpha(@NonNull View v, float alpha, int visibilityIfGone) {
		if (Float.compare(alpha, ALPHA_VISIBLE) == 0) {
			v.animate().cancel();
			v.setAlpha(ALPHA_VISIBLE);
			v.setVisibility(View.VISIBLE);
		} else if (v.getVisibility() != View.VISIBLE) {
			v.setAlpha(ALPHA_INVISIBLE);
			v.setVisibility(visibilityIfGone);
		} else {
			v.setVisibility(View.VISIBLE);
			v.animate().alpha(alpha).setDuration(100L).withEndAction(() -> {
				if (Float.compare(alpha, ALPHA_INVISIBLE) == 0) {
					v.setVisibility(visibilityIfGone);
				}
			}).start();
		}
	}

	public static void setFullscreen(@NonNull View view, boolean fullscreen) {
		if (fullscreen) {
			view.setSystemUiVisibility(
							View.SYSTEM_UI_FLAG_LOW_PROFILE
											| View.SYSTEM_UI_FLAG_FULLSCREEN
											| View.SYSTEM_UI_FLAG_LAYOUT_STABLE
											| View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
											| View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
											| View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
			);
		} else {
			view.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
		}
	}
}
