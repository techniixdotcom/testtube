package com.testtube.app.player;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ActivityInfo;
import android.graphics.Outline;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.Rational;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewParent;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.view.ViewCompat;
import androidx.media3.common.text.Cue;
import androidx.media3.common.text.CueGroup;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.DefaultTimeBar;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.SubtitleView;

import com.testtube.app.R;
import com.testtube.app.player.common.Constant;
import com.testtube.app.player.controller.ControllerState;
import com.testtube.app.player.sponsor.SponsorBlockManager;
import com.testtube.app.player.sponsor.SponsorOverlayView;
import com.testtube.app.util.ViewUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import android.os.Build;

/**
 * Custom player view with fullscreen and PiP support.
 */
@UnstableApi
public class TestTubePlayerView extends PlayerView {
	private static final float SUBTITLE_LINE_FRACTION = 0.92f;
	private static final float SUBTITLE_POSITION_FRACTION = 0.5f;
	@NonNull
	private final Activity activity;
	@Nullable
	private SponsorBlockManager sponsor;
	@Nullable
	private SubtitleView subtitleView;
	private boolean isFs = false;
	private int playerWidth = 0;
	private int playerHeight = 0;
	private int normalHeight = 0;
	private boolean parentInsetsSuppressed;
	private int parentPaddingLeft;
	private int parentPaddingTop;
	private int parentPaddingRight;
	private int parentPaddingBottom;

	public TestTubePlayerView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
		super(context, attrs, defStyleAttr);
		activity = activityOf(context);
	}

	public TestTubePlayerView(Context context, @Nullable AttributeSet attrs) {
		super(context, attrs);
		activity = activityOf(context);
	}

	public TestTubePlayerView(Context context) {
		super(context);
		activity = activityOf(context);
	}

	@NonNull
	private static Activity activityOf(@NonNull Context context) {
		Context current = context;
		while (current instanceof ContextWrapper wrapper) {
			if (current instanceof Activity found) return found;
			current = wrapper.getBaseContext();
		}
		throw new IllegalStateException("TestTubePlayerView requires an Activity context");
	}

	public void bind(@NonNull SponsorBlockManager sponsor) {
		this.sponsor = sponsor;
	}

	public void setup() {
		setControllerAnimationEnabled(false);
		setControllerHideOnTouch(false);
		setControllerAutoShow(false);
		setControllerShowTimeoutMs(0);
		setOutlineProvider(new ViewOutlineProvider() {
			@Override
			public void getOutline(View view, Outline outline) {
				outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(),
								ViewUtils.dpToPx(activity, 16));
			}
		});
		setClipToOutline(false);
		setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
		ConstraintLayout.LayoutParams params = (ConstraintLayout.LayoutParams) getLayoutParams();
		params.topMargin = ViewUtils.dpToPx(activity, Constant.TOP_MARGIN_DP);
		params.width = ConstraintLayout.LayoutParams.MATCH_PARENT;
		int screenWidth = ViewUtils.getScreenWidth(activity);
		params.height = (int) (screenWidth * 9 / 16.0);
		setLayoutParams(params);

		addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
			if (right - left != playerWidth || bottom - top != playerHeight) {
				playerWidth = right - left;
				playerHeight = bottom - top;
			}
		});
	}

	public void applyControllerState(@NonNull ControllerState.Mode previousState,
	                                 @NonNull ControllerState.Mode newState,
	                                 int fsOrientation) {
		post(() -> {
			switch (newState) {
				case NORMAL, MINI_PLAYER -> applyNormalState();
				case FULLSCREEN_UNLOCK, FULLSCREEN_LOCK -> applyFullscreenState(previousState, fsOrientation);
				case PIP -> applyPictureInPictureState(previousState);
			}
		});
	}

	public void updatePlayerLayout(boolean fullscreen) {
		ViewGroup.LayoutParams layoutParams = getLayoutParams();
		if (layoutParams instanceof ConstraintLayout.LayoutParams params) {
			applyStandardPlayerAnchors(params);
			if (fullscreen) {
				params.width = ConstraintLayout.LayoutParams.MATCH_PARENT;
				params.height = ConstraintLayout.LayoutParams.MATCH_PARENT;
				params.topMargin = 0;
				params.rightMargin = 0;
				params.bottomMargin = 0;
			} else {
				params.width = ConstraintLayout.LayoutParams.MATCH_PARENT;
				params.height = normalHeight > 0 ? normalHeight : (int) (ViewUtils.getScreenWidth(activity) * 9 / 16.0);
				params.topMargin = ViewUtils.dpToPx(activity, Constant.TOP_MARGIN_DP);
				params.rightMargin = 0;
				params.bottomMargin = 0;
			}
			setLayoutParams(params);
		}
	}

	public void enterPiP() {
		if (activity.isInPictureInPictureMode()) return;
		if (!isFs) normalHeight = playerHeight;
		PictureInPictureParams params = buildPiPParams(true);
		activity.enterPictureInPictureMode(params);
	}

	public void disableAutoPiP() {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
		activity.setPictureInPictureParams(buildPiPParams(false));
	}

	private void applyStandardPlayerAnchors(@NonNull ConstraintLayout.LayoutParams params) {
		params.topToTop = ConstraintLayout.LayoutParams.PARENT_ID;
		params.startToStart = ConstraintLayout.LayoutParams.PARENT_ID;
		params.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID;
		params.topToBottom = ConstraintLayout.LayoutParams.UNSET;
		params.bottomToTop = ConstraintLayout.LayoutParams.UNSET;
		params.bottomToBottom = ConstraintLayout.LayoutParams.UNSET;
		params.leftToLeft = ConstraintLayout.LayoutParams.UNSET;
		params.leftToRight = ConstraintLayout.LayoutParams.UNSET;
		params.rightToLeft = ConstraintLayout.LayoutParams.UNSET;
		params.rightToRight = ConstraintLayout.LayoutParams.UNSET;
		params.startToEnd = ConstraintLayout.LayoutParams.UNSET;
		params.endToStart = ConstraintLayout.LayoutParams.UNSET;
	}

	@NonNull
	private PictureInPictureParams buildPiPParams(boolean autoEnter) {
		PictureInPictureParams.Builder builder = new PictureInPictureParams.Builder()
						.setAspectRatio(new Rational(16, 9));
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
			builder.setAutoEnterEnabled(autoEnter);
		}
		Rect sourceRectHint = new Rect();
		if (getGlobalVisibleRect(sourceRectHint)) {
			builder.setSourceRectHint(sourceRectHint);
		}
		return builder.build();
	}

	private void applyNormalState() {
		setBottomNavVisible(true);
		isFs = false;
		setParentInsetsSuppressed(false);
		activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
		ViewUtils.setFullscreen(activity.getWindow().getDecorView(), false);
		updatePlayerLayout(false);
		updateFullscreenButton(false);
	}

	private void applyFullscreenState(@NonNull ControllerState.Mode previousState,
	                                  int fsOrientation) {
		setBottomNavVisible(false);
		isFs = true;
		if (previousState == ControllerState.Mode.NORMAL && !activity.isInPictureInPictureMode()) {
			normalHeight = playerHeight;
		}
		setParentInsetsSuppressed(true);
		activity.setRequestedOrientation(fsOrientation);
		ViewUtils.setFullscreen(activity.getWindow().getDecorView(), true);
		updatePlayerLayout(true);
		updateFullscreenButton(true);
	}

	private void applyPictureInPictureState(@NonNull ControllerState.Mode previousState) {
		setBottomNavVisible(true);
		isFs = false;
		setParentInsetsSuppressed(false);
		if (previousState == ControllerState.Mode.NORMAL) {
			normalHeight = playerHeight;
		}
		updatePlayerLayout(true);
	}

	private void setBottomNavVisible(boolean visible) {
		View nav = activity.findViewById(R.id.bottom_nav);
		if (nav != null) nav.setVisibility(visible ? View.VISIBLE : View.GONE);
	}

	private void setParentInsetsSuppressed(boolean suppressed) {
		ViewParent parent = getParent();
		if (!(parent instanceof View parentView)) return;
		if (suppressed) {
			if (parentInsetsSuppressed) return;
			parentPaddingLeft = parentView.getPaddingLeft();
			parentPaddingTop = parentView.getPaddingTop();
			parentPaddingRight = parentView.getPaddingRight();
			parentPaddingBottom = parentView.getPaddingBottom();
			parentView.setPadding(0, 0, 0, 0);
			parentInsetsSuppressed = true;
			return;
		}
		if (!parentInsetsSuppressed) return;
		parentView.setPadding(parentPaddingLeft, parentPaddingTop, parentPaddingRight, parentPaddingBottom);
		parentInsetsSuppressed = false;
		ViewCompat.requestApplyInsets(parentView);
	}

	private void updateFullscreenButton(boolean fullscreen) {
		ImageButton fullscreenButton = findViewById(R.id.btn_fullscreen);
		if (fullscreenButton != null) {
			fullscreenButton.setImageResource(
							fullscreen ? R.drawable.ic_fullscreen_exit : R.drawable.ic_fullscreen);
		}
	}

	public void cueing(@NonNull CueGroup cueGroup) {
		if (subtitleView == null) {
			SubtitleView defaultSubtitleView = getSubtitleView();
			if (defaultSubtitleView != null) defaultSubtitleView.setVisibility(View.GONE);
			subtitleView = findViewById(R.id.custom_subtitle_view);
		}
		List<Cue> cues = new ArrayList<>();
		for (Cue cue : cueGroup.cues)
			cues.add(cue.buildUpon()
							.setLine(SUBTITLE_LINE_FRACTION, Cue.LINE_TYPE_FRACTION)
							.setLineAnchor(Cue.ANCHOR_TYPE_END)
							.setPosition(SUBTITLE_POSITION_FRACTION)
							.setPositionAnchor(Cue.ANCHOR_TYPE_MIDDLE)
							.build());
		subtitleView.setCues(cues);
	}

	public void show() {
		setVisibility(View.VISIBLE);
	}

	public void hide() {
		setVisibility(View.GONE);
	}

	public void setTitle(@Nullable String title) {
		TextView titleView = findViewById(R.id.tv_title);
		titleView.setText(title);
		titleView.setSelected(true);
	}

	public void updateSkipMarkers(long duration, TimeUnit unit) {
		List<long[]> segs = sponsor != null ? sponsor.getSegments() : List.of();
		List<long[]> validSegs = new ArrayList<>();
		for (long[] seg : segs) if (seg != null && seg.length >= 2) validSegs.add(seg);

		SponsorOverlayView layer = findViewById(R.id.sponsor_overlay);
		layer.setData(validSegs.isEmpty() ? null : validSegs, duration, unit);

		DefaultTimeBar bar = findViewById(R.id.exo_progress);
		if (validSegs.isEmpty()) {
			bar.setAdGroupTimesMs(null, null, 0);
		} else {
			long[] times = new long[validSegs.size() * 2];
			for (int i = 0; i < validSegs.size(); i++) {
				times[i * 2] = validSegs.get(i)[0];
				times[i * 2 + 1] = validSegs.get(i)[1];
			}
			bar.setAdGroupTimesMs(times, new boolean[times.length], times.length);
		}
	}

	private final OnLayoutChangeListener parentLayout = (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
		if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) post(this::fitToWidth);
	};

	@Override
	protected void onAttachedToWindow() {
		super.onAttachedToWindow();
		if (getParent() instanceof View parent) parent.addOnLayoutChangeListener(parentLayout);
	}

	@Override
	protected void onDetachedFromWindow() {
		if (getParent() instanceof View parent) parent.removeOnLayoutChangeListener(parentLayout);
		super.onDetachedFromWindow();
	}

	/**
	 * Sizes the player to 16:9 of the screen width, at most 60% of the screen height, outside
	 * fullscreen and picture-in-picture.
	 */
	public void fitToWidth() {
		if (activity.isInPictureInPictureMode() || isFs) return;
		View parent = getParent() instanceof View view ? view : null;
		int width = parent != null && parent.getWidth() > 0 ? parent.getWidth() : getResources().getDisplayMetrics().widthPixels;
		int height = parent != null && parent.getHeight() > 0 ? parent.getHeight() : getResources().getDisplayMetrics().heightPixels;
		int target = Math.min(Math.round(width * 9f / 16f), Math.round(height * 0.6f));
		if (target <= 0) return;
		normalHeight = target;
		ViewGroup.LayoutParams params = getLayoutParams();
		if (params == null || params.height == target) return;
		params.height = target;
		setLayoutParams(params);
	}
}
