package com.testtube.app.player.controller.gesture;

import android.app.Activity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.ui.R;

import com.testtube.app.player.TestTubePlayerView;

import java.util.function.Consumer;

@UnstableApi
public class ZoomTouchListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
	private final ScaleGestureDetector detector;
	private final TestTubePlayerView playerView;
	private Consumer<Boolean> onShowReset;
	private float scaleFactor = 1.0f;
	private float lastX, lastY;
	private boolean panning;

	public ZoomTouchListener(Activity activity, TestTubePlayerView playerView) {
		this.playerView = playerView;
		this.detector = new ScaleGestureDetector(activity, this);
	}

	public void onTouch(MotionEvent event) {
		detector.onTouchEvent(event);

		if (event.getPointerCount() < 2) {
			if (panning) {
				panning = false;
				checkResetVisibility();
			}
			return;
		}

		switch (event.getActionMasked()) {
			case MotionEvent.ACTION_POINTER_DOWN:
				lastX = centerX(event);
				lastY = centerY(event);
				panning = true;
				break;

			case MotionEvent.ACTION_MOVE:
				if (panning && scaleFactor > 1.0f) {
					float cx = centerX(event);
					float cy = centerY(event);
					applyTranslation(cx - lastX, cy - lastY, false);
					lastX = cx;
					lastY = cy;
				}
				break;

			case MotionEvent.ACTION_POINTER_UP:
				if (event.getPointerCount() > 2) {
					// recenter when one finger lifts
					lastX = centerX(event);
					lastY = centerY(event);
				} else {
					panning = false;
				}
				break;
		}
	}

	@Override
	public boolean onScale(@NonNull ScaleGestureDetector detector) {
		scaleFactor = Math.max(1.0f, Math.min(scaleFactor * detector.getScaleFactor(), 5.0f));
		applyScale(scaleFactor);
		checkResetVisibility();
		return true;
	}

	public void reset() {
		scaleFactor = 1.0f;
		panning = false;
		applyScale(1f);
		applyTranslation(0, 0, true);
		checkResetVisibility();
	}

	private void checkResetVisibility() {
		View target = getTargetView();
		if (target == null) return;
		boolean hasTranslation = Math.abs(target.getTranslationX()) > 5 || Math.abs(target.getTranslationY()) > 5;
		if (onShowReset != null) onShowReset.accept(scaleFactor > 1.01f || hasTranslation);
	}

	private float centerX(MotionEvent e) {
		float sum = 0;
		int count = e.getPointerCount();
		for (int i = 0; i < count; i++) sum += e.getX(i);
		return sum / count;
	}

	private float centerY(MotionEvent e) {
		float sum = 0;
		int count = e.getPointerCount();
		for (int i = 0; i < count; i++) sum += e.getY(i);
		return sum / count;
	}

	private void applyScale(float scale) {
		View target = getTargetView();
		if (target != null) {
			target.setScaleX(scale);
			target.setScaleY(scale);
		}
	}

	private void applyTranslation(float dx, float dy, boolean isReset) {
		View target = getTargetView();
		if (target == null) return;

		if (isReset) {
			target.setTranslationX(0);
			target.setTranslationY(0);
		} else {
			float nextX = target.getTranslationX() + dx;
			float nextY = target.getTranslationY() + dy;

			// clamp so the video stays inside the visible crop
			float limitX = (target.getWidth() * scaleFactor - target.getWidth()) / 2f;
			float limitY = (target.getHeight() * scaleFactor - target.getHeight()) / 2f;

			target.setTranslationX(Math.max(-limitX, Math.min(limitX, nextX)));
			target.setTranslationY(Math.max(-limitY, Math.min(limitY, nextY)));
		}
	}

	private View getTargetView() {
		// prefer the content frame so we zoom the video area
		View contentFrame = playerView.findViewById(R.id.exo_content_frame);
		if (contentFrame != null) return contentFrame;

		View surface = playerView.getVideoSurfaceView();
		if (surface != null) return surface;

		// Last resort
		if (playerView.getChildCount() > 0) {
			return playerView.getChildAt(0);
		}

		return playerView;
	}

	public boolean isZoomed() {
		return scaleFactor > 1.01f;
	}

	public void setOnShowReset(Consumer<Boolean> onShowReset) {
		this.onShowReset = onShowReset;
	}
}
