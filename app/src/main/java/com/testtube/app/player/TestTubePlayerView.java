package com.testtube.app.player;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.app.RemoteAction;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Outline;
import android.graphics.Rect;
import android.os.Build;
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
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.common.text.Cue;
import androidx.media3.common.text.CueGroup;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.DefaultTimeBar;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.SubtitleView;

import com.testtube.app.R;
import com.testtube.app.player.controller.ControllerState;
import com.testtube.app.player.sponsor.SponsorBlockManager;
import com.testtube.app.player.sponsor.SponsorOverlayView;
import com.testtube.app.util.ViewUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@UnstableApi
public class TestTubePlayerView extends PlayerView {
	private static final float SUBTITLE_LINE_FRACTION = 0.92f;
	private static final float SUBTITLE_POSITION_FRACTION = 0.5f;
	/** Widest and tallest shapes Android allows for a picture-in-picture window. */
	private static final float MAX_PIP_ASPECT = 2.39f;
	private static final float PIP_ELEVATION_DP = 64f;
	@NonNull
	private final Activity activity;
	@Nullable
	private SponsorBlockManager sponsor;
	@Nullable
	private SubtitleView subtitleView;
	private boolean isFs = false;
	private int normalHeight = 0;
	private boolean parentInsetsSuppressed;
	private int parentPaddingLeft;
	private int parentPaddingTop;
	private int parentPaddingRight;
	private int parentPaddingBottom;
	/** Buttons of the floating window; the system adds expand and close. */
	@Nullable
	private Supplier<List<RemoteAction>> pipActions;

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
		params.topMargin = 0;
		params.width = ConstraintLayout.LayoutParams.MATCH_PARENT;
		int screenWidth = ViewUtils.getScreenWidth(activity);
		params.height = (int) (screenWidth * 9 / 16.0);
		setLayoutParams(params);
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
			params.width = ConstraintLayout.LayoutParams.MATCH_PARENT;
			params.height = fullscreen
							? ConstraintLayout.LayoutParams.MATCH_PARENT
							: normalHeight > 0 ? normalHeight : (int) (ViewUtils.getScreenWidth(activity) * 9 / 16.0);
			params.topMargin = 0;
			params.rightMargin = 0;
			params.bottomMargin = 0;
			setLayoutParams(params);
		}
	}

	/**
	 * @return false when the device has no picture-in-picture, or it is turned off for the app
	 */
	public boolean enterPiP() {
		if (activity.isInPictureInPictureMode()) return true;
		if (!activity.getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return false;
		int previousHeight = normalHeight;
		if (!isFs) normalHeight = getHeight();
		try {
			if (activity.enterPictureInPictureMode(buildPiPParams(true))) return true;
		} catch (IllegalStateException e) {
			// Thrown on devices where picture-in-picture is unavailable after all
		}
		normalHeight = previousHeight;
		return false;
	}

	public void setPiPActions(@Nullable Supplier<List<RemoteAction>> actions) {
		this.pipActions = actions;
	}

	/**
	 * Updates the floating window: its buttons and shape, and whether leaving the app opens it by
	 * itself (Android 12 and newer; older versions are handled on leaving).
	 */
	public void updatePiPParams(boolean autoEnter) {
		if (!activity.getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return;
		try {
			activity.setPictureInPictureParams(buildPiPParams(autoEnter));
		} catch (IllegalArgumentException | IllegalStateException e) {
			// The activity is finishing or picture-in-picture is turned off for the app
		}
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
						.setAspectRatio(pipAspectRatio());
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
			builder.setAutoEnterEnabled(autoEnter);
			// Resizing a playing video smoothly costs more than it looks
			builder.setSeamlessResizeEnabled(false);
		}
		Supplier<List<RemoteAction>> actions = pipActions;
		if (actions != null) {
			List<RemoteAction> list = actions.get();
			int max = activity.getMaxNumPictureInPictureActions();
			builder.setActions(list.size() > max ? list.subList(0, max) : list);
		}
		Rect sourceRectHint = new Rect();
		if (getVisibility() == View.VISIBLE && getGlobalVisibleRect(sourceRectHint)) {
			builder.setSourceRectHint(sourceRectHint);
		}
		return builder.build();
	}

	/** The video's own shape, within what Android allows; 16:9 until the video is known. */
	@NonNull
	private Rational pipAspectRatio() {
		Player player = getPlayer();
		VideoSize size = player != null ? player.getVideoSize() : VideoSize.UNKNOWN;
		if (size.width <= 0 || size.height <= 0) return new Rational(16, 9);
		float ratio = size.width * size.pixelWidthHeightRatio / size.height;
		ratio = Math.max(1f / MAX_PIP_ASPECT, Math.min(MAX_PIP_ASPECT, ratio));
		return new Rational(Math.round(ratio * 10_000), 10_000);
	}

	private void applyNormalState() {
		setTranslationZ(0f);
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
		setTranslationZ(0f);
		setBottomNavVisible(false);
		isFs = true;
		if (previousState == ControllerState.Mode.NORMAL && !activity.isInPictureInPictureMode()) {
			normalHeight = getHeight();
		}
		setParentInsetsSuppressed(true);
		activity.setRequestedOrientation(fsOrientation);
		ViewUtils.setFullscreen(activity.getWindow().getDecorView(), true);
		updatePlayerLayout(true);
		updateFullscreenButton(true);
	}

	/** The floating window shows the video alone, above the bars and lists of the app. */
	private void applyPictureInPictureState(@NonNull ControllerState.Mode previousState) {
		setBottomNavVisible(false);
		setTranslationZ(ViewUtils.dpToPx(activity, PIP_ELEVATION_DP));
		isFs = false;
		setParentInsetsSuppressed(false);
		if (previousState == ControllerState.Mode.NORMAL) {
			normalHeight = getHeight();
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
