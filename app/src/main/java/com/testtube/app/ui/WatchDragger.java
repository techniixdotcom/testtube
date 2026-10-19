package com.testtube.app.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.testtube.app.player.controller.gesture.WatchDrag;

/**
 * Drags the open video down into the bottom bar, and the bottom bar back up into the video, with the
 * finger. The video shrinks towards the bar's thumbnail on the way, the screen below shows through,
 * and on release it settles at whichever end is closer, or where it was flicked.
 */
final class WatchDragger implements WatchDrag {
	interface Host {
		boolean canMinimize();

		void minimize();

		void restore();

		/** Keeps the screen under the video visible (and Shorts paused) while the video moves. */
		void setRevealed(boolean revealed);
	}

	private static final long SETTLE_MS = 280L;
	private static final long MIN_SETTLE_MS = 120L;
	/** A flick this fast (dp per second) decides the direction, however far the video got. */
	private static final float FLING_DP_PER_SECOND = 900f;

	@NonNull
	private final View root;
	@NonNull
	private final View player;
	@NonNull
	private final View details;
	@NonNull
	private final View miniBar;
	@NonNull
	private final View bottomNav;
	@NonNull
	private final Host host;
	private final int touchSlop;
	private final float flingVelocity;
	private final DecelerateInterpolator interpolator = new DecelerateInterpolator(1.6f);

	/** 0 with the video on top, 1 when it sits on the bottom bar. */
	private float progress;
	private float startProgress;
	/** A drag or the settling after it runs; the views are moved by this class until it ends. */
	private boolean active;
	@Nullable
	private ValueAnimator settle;

	private float barDownY;
	private float barDragStartY;
	private boolean barTracking;
	private boolean barDragging;
	@Nullable
	private VelocityTracker barVelocity;

	WatchDragger(@NonNull View root, @NonNull View player, @NonNull View details, @NonNull View miniBar,
	             @NonNull View bottomNav, @NonNull Host host) {
		this.root = root;
		this.player = player;
		this.details = details;
		this.miniBar = miniBar;
		this.bottomNav = bottomNav;
		this.host = host;
		ViewConfiguration config = ViewConfiguration.get(root.getContext());
		touchSlop = config.getScaledTouchSlop();
		flingVelocity = FLING_DP_PER_SECOND * root.getResources().getDisplayMetrics().density;
		listenToMiniBar();
	}

	@Override
	public boolean begin() {
		if (active || !host.canMinimize()) return false;
		active = true;
		startProgress = 0f;
		host.setRevealed(true);
		apply(0f);
		return true;
	}

	@Override
	public void moveBy(float dy) {
		if (active && settle == null) apply(startProgress + dy / range());
	}

	@Override
	public void release(float velocityY) {
		if (!active || settle != null) return;
		float target;
		if (velocityY > flingVelocity) {
			target = 1f;
		} else if (velocityY < -flingVelocity) {
			target = 0f;
		} else {
			target = progress >= 0.5f ? 1f : 0f;
		}
		settleTo(target);
	}

	/** Tapping the bar: the video grows back from the bar instead of jumping up. */
	private void expand() {
		if (!startFromBar()) return;
		settleTo(0f);
	}

	private boolean startFromBar() {
		if (active) return false;
		active = true;
		startProgress = 1f;
		host.setRevealed(true);
		host.restore();
		apply(1f);
		return true;
	}

	private void settleTo(float target) {
		float from = progress;
		long duration = Math.max(MIN_SETTLE_MS, Math.round(SETTLE_MS * Math.abs(target - from)));
		ValueAnimator animator = ValueAnimator.ofFloat(from, target);
		animator.setDuration(duration);
		animator.setInterpolator(interpolator);
		animator.addUpdateListener(a -> apply((float) a.getAnimatedValue()));
		animator.addListener(new AnimatorListenerAdapter() {
			@Override
			public void onAnimationEnd(Animator animation) {
				settle = null;
				finish(target >= 1f);
			}
		});
		settle = animator;
		animator.start();
	}

	private void finish(boolean minimized) {
		if (minimized) host.minimize();
		apply(0f);
		active = false;
		host.setRevealed(false);
	}

	private void apply(float value) {
		progress = Math.max(0f, Math.min(1f, value));
		float height = playerHeight();
		float offset = range() * progress;
		float smallest = height > 0f ? Math.min(1f, miniBarHeight() / height) : 1f;
		float scale = 1f - (1f - smallest) * progress;
		player.setPivotX(0f);
		player.setPivotY(0f);
		player.setScaleX(scale);
		player.setScaleY(scale);
		player.setTranslationY(offset);
		// The details stay glued under the shrinking video and fade out
		details.setTranslationY(offset - height * (1f - scale));
		details.setAlpha(1f - progress);
	}

	/** How far the top of the video travels to reach the top of the bottom bar. */
	private float range() {
		int bottom = bottomNav.getVisibility() == View.VISIBLE
						? bottomNav.getTop()
						: root.getHeight() - root.getPaddingBottom();
		return Math.max(1f, bottom - miniBarHeight() - root.getPaddingTop());
	}

	private float miniBarHeight() {
		int height = miniBar.getLayoutParams().height;
		return height > 0 ? height : miniBar.getHeight();
	}

	/** The player's normal height, also while it is hidden in the bar and not laid out. */
	private float playerHeight() {
		int height = player.getLayoutParams().height;
		return height > 0 ? height : player.getHeight();
	}

	@SuppressLint("ClickableViewAccessibility")
	private void listenToMiniBar() {
		miniBar.setOnTouchListener((v, ev) -> {
			switch (ev.getActionMasked()) {
				case MotionEvent.ACTION_DOWN -> {
					barTracking = !active;
					barDragging = false;
					barDownY = ev.getRawY();
					trackBar(ev);
					return false;
				}
				case MotionEvent.ACTION_MOVE -> {
					if (!barTracking) return false;
					trackBar(ev);
					if (!barDragging) {
						if (barDownY - ev.getRawY() <= touchSlop) return false;
						cancelPress(v, ev);
						if (!startFromBar()) {
							barTracking = false;
							return true;
						}
						barDragging = true;
						barDragStartY = ev.getRawY();
					}
					moveBy(ev.getRawY() - barDragStartY);
					return true;
				}
				case MotionEvent.ACTION_UP -> {
					if (!barTracking) return false;
					trackBar(ev);
					float speed = 0f;
					if (barVelocity != null) {
						barVelocity.computeCurrentVelocity(1000);
						speed = barVelocity.getYVelocity();
					}
					stopBarTracking();
					if (barDragging) {
						barDragging = false;
						release(speed);
						return true;
					}
					long held = ev.getEventTime() - ev.getDownTime();
					if (held < ViewConfiguration.getLongPressTimeout()) {
						cancelPress(v, ev);
						expand();
						return true;
					}
					return false;
				}
				case MotionEvent.ACTION_CANCEL -> {
					stopBarTracking();
					if (barDragging) {
						barDragging = false;
						release(0f);
						return true;
					}
					return false;
				}
				default -> {
					return false;
				}
			}
		});
	}

	private void trackBar(@NonNull MotionEvent ev) {
		if (barVelocity == null) barVelocity = VelocityTracker.obtain();
		MotionEvent screen = MotionEvent.obtain(ev);
		screen.setLocation(ev.getRawX(), ev.getRawY());
		barVelocity.addMovement(screen);
		screen.recycle();
	}

	private void stopBarTracking() {
		barTracking = false;
		if (barVelocity != null) {
			barVelocity.recycle();
			barVelocity = null;
		}
	}

	/** Ends the bar's own press (ripple, click) once this class takes over the touch. */
	private static void cancelPress(@NonNull View view, @NonNull MotionEvent ev) {
		MotionEvent cancel = MotionEvent.obtain(ev);
		cancel.setAction(MotionEvent.ACTION_CANCEL);
		view.onTouchEvent(cancel);
		cancel.recycle();
	}
}
