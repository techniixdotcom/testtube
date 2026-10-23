package com.testtube.app.player;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Outline;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageButton;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tencent.mmkv.MMKV;
import com.testtube.app.R;

/**
 * The video in a small window over the app, which stays visible while browsing. The player view
 * itself is scaled into a corner (so the picture keeps playing without a second surface) and a
 * touch layer on top moves it: drag it anywhere and it settles in the nearest corner, which is
 * remembered; fling it off the side to close it. A tap shows expand, play/pause and close; a
 * double tap expands it.
 */
final class FloatingWindow {
	interface Actions {
		void expand();

		void playPause();

		void close();
	}

	private static final String KEY_CORNER = "floating_corner";
	private static final int TOP_START = 0;
	private static final int TOP_END = 1;
	private static final int BOTTOM_START = 2;
	private static final int BOTTOM_END = 3;
	private static final float WIDTH_PORTRAIT = 0.5f;
	private static final float WIDTH_LANDSCAPE = 0.3f;
	private static final float MIN_WIDTH_DP = 168f;
	private static final float MAX_WIDTH_DP = 320f;
	private static final float MARGIN_DP = 12f;
	private static final float RADIUS_DP = 12f;
	private static final float ELEVATION_DP = 10f;
	private static final long SNAP_MS = 240L;
	private static final long CONTROLS_FADE_MS = 150L;
	private static final long CONTROLS_VISIBLE_MS = 2_500L;
	/** How far ahead a fling throws the window when picking its corner. */
	private static final float FLING_SECONDS = 0.18f;

	@NonNull
	private final View player;
	@NonNull
	private final View overlay;
	@NonNull
	private final View controls;
	@NonNull
	private final ImageButton playPause;
	@Nullable
	private final View bottomNav;
	@NonNull
	private final View parent;
	@NonNull
	private final Actions actions;
	private final float density;
	private final int touchSlop;
	@NonNull
	private final Handler handler = new Handler(Looper.getMainLooper());
	@NonNull
	private final Runnable hideControls = () -> setControlsVisible(false);
	@NonNull
	private final GestureDetector taps;
	/** Keeps the window in its corner when the screen or the player is resized (e.g. rotation). */
	@NonNull
	private final View.OnLayoutChangeListener relayout;

	private boolean showing;
	/** The finger moves the window, or it settles into its corner. */
	private boolean moving;
	private int corner;
	/** Where the window is, in the parent's coordinates. */
	private float x;
	private float y;
	private float width;
	private float height;
	@Nullable
	private ValueAnimator snap;
	@Nullable
	private VelocityTracker velocity;
	private float downX;
	private float downY;
	private float startX;
	private float startY;
	@Nullable
	private ViewOutlineProvider savedOutline;
	private boolean savedClip;
	private float savedElevation;

	FloatingWindow(@NonNull Activity activity, @NonNull View player, @NonNull Actions actions) {
		this.player = player;
		this.actions = actions;
		this.parent = (View) player.getParent();
		overlay = activity.findViewById(R.id.floating_overlay);
		controls = activity.findViewById(R.id.floating_controls);
		playPause = activity.findViewById(R.id.floating_play_pause);
		bottomNav = activity.findViewById(R.id.bottom_nav);
		density = activity.getResources().getDisplayMetrics().density;
		touchSlop = ViewConfiguration.get(activity).getScaledTouchSlop();
		corner = MMKV.defaultMMKV().decodeInt(KEY_CORNER, BOTTOM_END);
		relayout = (v, l, t, r, b, oldL, oldT, oldR, oldB) -> {
			if (showing && !moving && (r - l != oldR - oldL || b - t != oldB - oldT)) place(false);
		};
		overlay.setClipToOutline(true);
		activity.findViewById(R.id.floating_expand).setOnClickListener(v -> actions.expand());
		activity.findViewById(R.id.floating_close).setOnClickListener(v -> actions.close());
		playPause.setOnClickListener(v -> {
			actions.playPause();
			showControlsForAWhile();
		});
		taps = new GestureDetector(activity, new GestureDetector.SimpleOnGestureListener() {
			@Override
			public boolean onDown(@NonNull MotionEvent e) {
				return true;
			}

			@Override
			public boolean onSingleTapConfirmed(@NonNull MotionEvent e) {
				if (controls.getVisibility() == View.VISIBLE) setControlsVisible(false);
				else showControlsForAWhile();
				return true;
			}

			@Override
			public boolean onDoubleTap(@NonNull MotionEvent e) {
				actions.expand();
				return true;
			}
		});
		listenToTouches();
	}

	boolean isShowing() {
		return showing;
	}

	/** Where the window is, or would appear, in the parent's coordinates. */
	@NonNull
	RectF bounds() {
		if (!showing) measureAtCorner();
		return new RectF(x, y, x + width, y + height);
	}

	void show() {
		if (!showing) {
			showing = true;
			savedOutline = player.getOutlineProvider();
			savedClip = player.getClipToOutline();
			savedElevation = player.getElevation();
			player.addOnLayoutChangeListener(relayout);
			parent.addOnLayoutChangeListener(relayout);
			player.setOutlineProvider(new ViewOutlineProvider() {
				@Override
				public void getOutline(View view, Outline outline) {
					// Drawn scaled down, so the radius is scaled up to look the same
					float scale = view.getScaleX() > 0f ? view.getScaleX() : 1f;
					outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), RADIUS_DP * density / scale);
				}
			});
			player.setClipToOutline(true);
			player.setElevation(ELEVATION_DP * density);
		}
		setControlsVisible(false);
		overlay.setVisibility(View.VISIBLE);
		place(false);
	}

	/** Back to the normal player: the transform, outline and touch layer are undone. */
	void hide() {
		if (!showing) return;
		showing = false;
		cancelSnap();
		handler.removeCallbacks(hideControls);
		player.removeOnLayoutChangeListener(relayout);
		parent.removeOnLayoutChangeListener(relayout);
		overlay.setVisibility(View.GONE);
		player.setScaleX(1f);
		player.setScaleY(1f);
		player.setTranslationX(0f);
		player.setTranslationY(0f);
		player.setOutlineProvider(savedOutline);
		player.setClipToOutline(savedClip);
		player.setElevation(savedElevation);
	}

	void setPlaying(boolean playing) {
		playPause.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
		playPause.setContentDescription(playPause.getContext().getString(
						playing ? R.string.mini_bar_pause : R.string.mini_bar_play));
	}

	/** Puts the window in its corner, sliding there or right away. */
	private void place(boolean animate) {
		float fromX = x;
		float fromY = y;
		measureAtCorner();
		if (!animate) {
			apply(x, y);
			return;
		}
		float toX = x;
		float toY = y;
		cancelSnap();
		moving = true;
		ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
		animator.setDuration(SNAP_MS);
		animator.setInterpolator(new DecelerateInterpolator(1.5f));
		animator.addUpdateListener(a -> {
			float f = (float) a.getAnimatedValue();
			apply(fromX + (toX - fromX) * f, fromY + (toY - fromY) * f);
		});
		animator.addListener(new AnimatorListenerAdapter() {
			@Override
			public void onAnimationEnd(Animator animation) {
				if (snap == animation) {
					snap = null;
					moving = false;
				}
			}
		});
		snap = animator;
		animator.start();
	}

	/** Size and position for the saved corner, also used for the animation into the window. */
	private void measureAtCorner() {
		float playerWidth = playerWidth();
		boolean landscape = player.getResources().getConfiguration().orientation
						== Configuration.ORIENTATION_LANDSCAPE;
		float wanted = parent.getWidth() * (landscape ? WIDTH_LANDSCAPE : WIDTH_PORTRAIT);
		width = Math.max(MIN_WIDTH_DP * density, Math.min(MAX_WIDTH_DP * density, wanted));
		width = Math.min(width, Math.max(1f, parent.getWidth() - 2 * MARGIN_DP * density));
		height = playerWidth > 0f ? width * playerHeight() / playerWidth : width * 9f / 16f;
		boolean end = corner == TOP_END || corner == BOTTOM_END;
		boolean bottom = corner == BOTTOM_START || corner == BOTTOM_END;
		x = end ? rightEdge() - width : leftEdge();
		y = bottom ? bottomEdge() - height : topEdge();
	}

	private void apply(float left, float top) {
		x = left;
		y = top;
		float playerWidth = playerWidth();
		float scale = playerWidth > 0f ? width / playerWidth : 1f;
		player.setPivotX(0f);
		player.setPivotY(0f);
		player.setScaleX(scale);
		player.setScaleY(scale);
		player.setTranslationX(left - player.getLeft());
		player.setTranslationY(top - player.getTop());
		player.invalidateOutline();
		ViewGroup.LayoutParams params = overlay.getLayoutParams();
		int w = Math.round(width);
		int h = Math.round(height);
		if (params.width != w || params.height != h) {
			params.width = w;
			params.height = h;
			overlay.setLayoutParams(params);
		}
		// The touch layer is laid out at the parent's padding corner
		overlay.setTranslationX(left - parent.getPaddingLeft());
		overlay.setTranslationY(top - parent.getPaddingTop());
	}

	@SuppressLint("ClickableViewAccessibility")
	private void listenToTouches() {
		overlay.setOnTouchListener((v, ev) -> {
			switch (ev.getActionMasked()) {
				case MotionEvent.ACTION_DOWN -> {
					cancelSnap();
					moving = false;
					downX = ev.getRawX();
					downY = ev.getRawY();
					startX = x;
					startY = y;
					track(ev);
					taps.onTouchEvent(ev);
				}
				case MotionEvent.ACTION_MOVE -> {
					track(ev);
					float dx = ev.getRawX() - downX;
					float dy = ev.getRawY() - downY;
					if (!moving && Math.hypot(dx, dy) > touchSlop) {
						moving = true;
						MotionEvent cancel = MotionEvent.obtain(ev);
						cancel.setAction(MotionEvent.ACTION_CANCEL);
						taps.onTouchEvent(cancel);
						cancel.recycle();
					}
					if (moving) {
						float top = Math.max(topEdge(), Math.min(bottomEdge() - height, startY + dy));
						apply(startX + dx, top);
					} else {
						taps.onTouchEvent(ev);
					}
				}
				case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
					track(ev);
					float vx = 0f;
					float vy = 0f;
					if (velocity != null) {
						velocity.computeCurrentVelocity(1000);
						vx = velocity.getXVelocity();
						vy = velocity.getYVelocity();
						velocity.recycle();
						velocity = null;
					}
					if (moving) {
						release(vx, vy);
					} else {
						taps.onTouchEvent(ev);
					}
				}
				default -> {
				}
			}
			return true;
		});
	}

	/** Settles in the corner the window was dragged or flung towards, or closes off the side. */
	private void release(float vx, float vy) {
		float centerX = x + width / 2f + vx * FLING_SECONDS;
		float centerY = y + height / 2f + vy * FLING_SECONDS;
		if (centerX < leftEdge() - width * 0.1f || centerX > rightEdge() + width * 0.1f) {
			slideOut(centerX < leftEdge());
			return;
		}
		boolean end = centerX > (leftEdge() + rightEdge()) / 2f;
		boolean bottom = centerY > (topEdge() + bottomEdge()) / 2f;
		corner = bottom ? (end ? BOTTOM_END : BOTTOM_START) : (end ? TOP_END : TOP_START);
		MMKV.defaultMMKV().encode(KEY_CORNER, corner);
		place(true);
	}

	private void slideOut(boolean toLeft) {
		cancelSnap();
		moving = true;
		float fromX = x;
		float toX = toLeft ? -width - MARGIN_DP * density : parent.getWidth() + MARGIN_DP * density;
		ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
		animator.setDuration(SNAP_MS);
		animator.addUpdateListener(a -> apply(fromX + (toX - fromX) * (float) a.getAnimatedValue(), y));
		animator.addListener(new AnimatorListenerAdapter() {
			@Override
			public void onAnimationEnd(Animator animation) {
				if (snap != animation) return;
				snap = null;
				moving = false;
				actions.close();
			}
		});
		snap = animator;
		animator.start();
	}

	private void showControlsForAWhile() {
		setControlsVisible(true);
		handler.removeCallbacks(hideControls);
		handler.postDelayed(hideControls, CONTROLS_VISIBLE_MS);
	}

	private void setControlsVisible(boolean visible) {
		handler.removeCallbacks(hideControls);
		controls.animate().cancel();
		if (visible) {
			controls.setVisibility(View.VISIBLE);
			controls.animate().alpha(1f).setDuration(CONTROLS_FADE_MS).start();
		} else if (controls.getVisibility() == View.VISIBLE) {
			controls.animate().alpha(0f).setDuration(CONTROLS_FADE_MS)
							.withEndAction(() -> controls.setVisibility(View.GONE)).start();
		}
	}

	private void cancelSnap() {
		ValueAnimator running = snap;
		snap = null;
		if (running != null) running.cancel();
		moving = false;
	}

	private void track(@NonNull MotionEvent ev) {
		if (velocity == null) velocity = VelocityTracker.obtain();
		MotionEvent screen = MotionEvent.obtain(ev);
		screen.setLocation(ev.getRawX(), ev.getRawY());
		velocity.addMovement(screen);
		screen.recycle();
	}

	private float playerWidth() {
		return player.getWidth() > 0 ? player.getWidth() : parent.getWidth();
	}

	/** The player's normal height, as laid out outside fullscreen. */
	private float playerHeight() {
		int height = player.getLayoutParams().height;
		return height > 0 ? height : player.getHeight();
	}

	private float leftEdge() {
		return parent.getPaddingLeft() + MARGIN_DP * density;
	}

	private float rightEdge() {
		return parent.getWidth() - parent.getPaddingRight() - MARGIN_DP * density;
	}

	private float topEdge() {
		return parent.getPaddingTop() + MARGIN_DP * density;
	}

	/** Above the bottom bar, so its tabs stay reachable. */
	private float bottomEdge() {
		float bottom = bottomNav != null && bottomNav.getVisibility() == View.VISIBLE && bottomNav.getTop() > 0
						? bottomNav.getTop()
						: parent.getHeight() - parent.getPaddingBottom();
		return bottom - MARGIN_DP * density;
	}
}
