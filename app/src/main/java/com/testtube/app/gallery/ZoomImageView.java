package com.testtube.app.gallery;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.ViewParent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;

/**
 * Image view with pinch zoom, panning and double tap zoom.
 */
final class ZoomImageView extends AppCompatImageView {
	private static final float MAX_SCALE = 5f;
	private static final float DOUBLE_TAP_SCALE = 2.5f;
	private final Matrix base = new Matrix();
	private final Matrix user = new Matrix();
	private final Matrix draw = new Matrix();
	private final RectF bounds = new RectF();
	private final float[] values = new float[9];
	private final ScaleGestureDetector scaleDetector;
	private final GestureDetector gestureDetector;

	ZoomImageView(@NonNull Context context) {
		super(context);
		setScaleType(ScaleType.MATRIX);
		scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
			@Override
			public boolean onScale(@NonNull ScaleGestureDetector detector) {
				float current = scale();
				float target = Math.max(1f, Math.min(MAX_SCALE, current * detector.getScaleFactor()));
				float factor = target / current;
				user.postScale(factor, factor, detector.getFocusX(), detector.getFocusY());
				apply();
				return true;
			}
		});
		gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
			@Override
			public boolean onDown(@NonNull MotionEvent e) {
				return true;
			}

			@Override
			public boolean onScroll(@Nullable MotionEvent e1, @NonNull MotionEvent e2, float dx, float dy) {
				if (scale() <= 1f) return false;
				user.postTranslate(-dx, -dy);
				apply();
				return true;
			}

			@Override
			public boolean onDoubleTap(@NonNull MotionEvent e) {
				if (scale() > 1.01f) {
					user.reset();
				} else {
					user.postScale(DOUBLE_TAP_SCALE, DOUBLE_TAP_SCALE, e.getX(), e.getY());
				}
				apply();
				return true;
			}
		});
	}

	private float scale() {
		user.getValues(values);
		return values[Matrix.MSCALE_X];
	}

	@SuppressLint("ClickableViewAccessibility")
	@Override
	public boolean onTouchEvent(@NonNull MotionEvent event) {
		scaleDetector.onTouchEvent(event);
		gestureDetector.onTouchEvent(event);
		ViewParent parent = getParent();
		if (parent != null) {
			parent.requestDisallowInterceptTouchEvent(scale() > 1f || event.getPointerCount() > 1);
		}
		return true;
	}

	@Override
	public void setImageDrawable(@Nullable Drawable drawable) {
		super.setImageDrawable(drawable);
		// Called by the super constructor before the fields exist.
		if (user != null) fit();
	}

	@Override
	protected void onSizeChanged(int w, int h, int oldw, int oldh) {
		super.onSizeChanged(w, h, oldw, oldh);
		fit();
	}

	private void fit() {
		Drawable drawable = getDrawable();
		int viewWidth = getWidth();
		int viewHeight = getHeight();
		user.reset();
		base.reset();
		if (drawable != null && viewWidth > 0 && viewHeight > 0
						&& drawable.getIntrinsicWidth() > 0 && drawable.getIntrinsicHeight() > 0) {
			base.setRectToRect(
							new RectF(0, 0, drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight()),
							new RectF(0, 0, viewWidth, viewHeight),
							Matrix.ScaleToFit.CENTER);
		}
		apply();
	}

	private void apply() {
		Drawable drawable = getDrawable();
		if (drawable != null && drawable.getIntrinsicWidth() > 0 && drawable.getIntrinsicHeight() > 0) {
			draw.set(base);
			draw.postConcat(user);
			bounds.set(0, 0, drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight());
			draw.mapRect(bounds);
			user.postTranslate(correction(bounds.left, bounds.right, getWidth()),
							correction(bounds.top, bounds.bottom, getHeight()));
		}
		draw.set(base);
		draw.postConcat(user);
		setImageMatrix(draw);
	}

	private static float correction(float start, float end, int size) {
		float length = end - start;
		if (length <= size) return (size - length) / 2f - start;
		if (start > 0) return -start;
		if (end < size) return size - end;
		return 0f;
	}
}
