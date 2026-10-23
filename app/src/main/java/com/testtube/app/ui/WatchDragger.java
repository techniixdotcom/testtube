package com.testtube.app.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.graphics.RectF;
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
 * finger. Only the video moves: it stays centred, shrinks to half its size and fades into the bar at
 * the end, while the description and comments fade out where they are and the screen below shows
 * through. With the floating window chosen instead, the video shrinks straight into the window's
 * corner and stays visible. On release it settles at whichever end is closer, or where it was
 * flicked.
 */
final class WatchDragger implements WatchDrag {
	interface Host {
		boolean canMinimize();

		void minimize();

		void restore();

		/** Keeps the screen under the video visible (and Shorts paused) while the video moves. */
		void setRevealed(boolean revealed);

		/** Where the floating window is, when minimizing goes there; null for the bottom bar. */
		@Nullable
		RectF floatingTarget();
	}

	private static final long SETTLE_MS = 280L;
	private static final long MIN_SETTLE_MS = 120L;
	/** A flick this fast (dp per second) decides the direction, however far the video got. */
	private static final float FLING_DP_PER_SECOND = 900f;
	/** Size of the video, compared to its normal size, when it reaches the bar. */
	private static final float END_SCALE = 0.5f;
	/** The last part of the way, where the video fades into the bar. */
	private static final float FADE_FROM = 0.7f;

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
	/** The floating window this drag moves into or out of; null for the bottom bar. */
	@Nullable
	private RectF floating;

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
		floating = host.floatingTarget();
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

	/** Tapping the bar or the floating window: the video grows back instead of jumping up. */
	@Override
	public boolean expand() {
		if (!startFromBar()) return false;
		settleTo(0f);
		return true;
	}

	private boolean startFromBar() {
		if (active) return false;
		active = true;
		// Read before restoring, while the window still knows where it is
		floating = host.floatingTarget();
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
		if (minimized && floating != null) {
			// The floating window now holds the video where the drag left it
			details.setAlpha(1f);
		} else {
			apply(0f);
		}
		active = false;
		floating = null;
		host.setRevealed(false);
	}

	private void apply(float value) {
		progress = Math.max(0f, Math.min(1f, value));
		RectF target = floating;
		if (target != null) {
			applyFloating(target);
			return;
		}
		float scale = 1f - (1f - END_SCALE) * progress;
		player.setPivotX((player.getWidth() > 0 ? player.getWidth() : root.getWidth()) / 2f);
		player.setPivotY(0f);
		player.setScaleX(scale);
		player.setScaleY(scale);
		player.setTranslationX(0f);
		player.setTranslationY(range() * progress);
		player.setAlpha(progress <= FADE_FROM ? 1f : (1f - progress) / (1f - FADE_FROM));
		// The details stay where they are and fade out twice as fast as the video moves
		details.setAlpha(Math.max(0f, 1f - progress * 2f));
	}

	/** Shrinks the video from its place on top straight into the floating window. */
	private void applyFloating(@NonNull RectF target) {
		float width = player.getWidth() > 0 ? player.getWidth() : root.getWidth();
		float endScale = width > 0f ? target.width() / width : END_SCALE;
		float scale = 1f - (1f - endScale) * progress;
		player.setPivotX(0f);
		player.setPivotY(0f);
		player.setScaleX(scale);
		player.setScaleY(scale);
		player.setTranslationX((target.left - player.getLeft()) * progress);
		player.setTranslationY((target.top - player.getTop()) * progress);
		player.setAlpha(1f);
		details.setAlpha(Math.max(0f, 1f - progress * 2f));
	}

	/** How far the top of the video travels until its bottom, at its end size, meets the bar's. */
	private float range() {
		int bottom = bottomNav.getVisibility() == View.VISIBLE
						? bottomNav.getTop()
						: root.getHeight() - root.getPaddingBottom();
		return Math.max(1f, bottom - root.getPaddingTop() - playerHeight() * END_SCALE);
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
