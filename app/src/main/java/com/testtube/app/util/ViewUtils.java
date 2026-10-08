package com.testtube.app.util;

import android.content.Context;
import android.view.View;

import androidx.annotation.NonNull;

public final class ViewUtils {
	private static final float ALPHA_VISIBLE = 1.0f;
	private static final float ALPHA_INVISIBLE = 0.0f;

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
