package com.testtube.app.ui.widget;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RecordingCanvas;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.View;
import android.view.ViewTreeObserver;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import java.util.Arrays;

/**
 * Frosted glass for bars floating over the screens: what's behind, blurred, under a translucent
 * tint, with a hairline on top.
 * <p>
 * On 12+ it's a RenderEffect over the BlurTargets' RenderNodes, on the render thread. The bar only
 * redraws when it or a target moves, shows or hides, never just because a list scrolls behind it.
 * Below 12 there's no cheap blur, so screw it: solid colour. Same with nothing to blur or no
 * hardware acceleration.
 */
public final class GlassBackground extends Drawable {
	private static final int STATE_SLOTS_PER_TARGET = 5;

	@NonNull
	private final View host;
	@NonNull
	private final BlurTarget[] targets;
	@NonNull
	private final Paint glassPaint = new Paint();
	@NonNull
	private final Paint solidPaint = new Paint();
	@NonNull
	private final Paint hairlinePaint = new Paint();
	@ColorInt
	private final int glassColor;
	@ColorInt
	private final int solidColor;
	@ColorInt
	private final int hairlineColor;
	@ColorInt
	private final int baseColor;
	private final float blurRadiusPx;
	private final float hairlinePx;
	@NonNull
	private final int[] hostLocation = new int[2];
	@NonNull
	private final int[] targetLocation = new int[2];
	/** Where host and targets were at the last draw. */
	@NonNull
	private int[] drawnState = new int[0];
	@NonNull
	private int[] currentState = new int[0];
	@Nullable
	private Blur blur;
	private int alpha = 255;

	@NonNull
	private final ViewTreeObserver.OnPreDrawListener preDrawListener;

	/**
	 * @param host         view this is the background of
	 * @param glassColor   translucent tint over the blur
	 * @param solidColor   colour when there's nothing to blur
	 * @param baseColor    page colour under the screens, behind anything transparent in them
	 * @param hairline     top edge line, 0 for none
	 * @param blurRadiusPx blur radius in px
	 * @param targets      screens that can be behind the bar
	 */
	public GlassBackground(@NonNull View host,
	                       @ColorInt int glassColor,
	                       @ColorInt int solidColor,
	                       @ColorInt int baseColor,
	                       @ColorInt int hairline,
	                       float blurRadiusPx,
	                       @NonNull BlurTarget... targets) {
		this.host = host;
		this.targets = targets.clone();
		this.glassColor = glassColor;
		this.solidColor = solidColor;
		this.hairlineColor = hairline;
		this.baseColor = baseColor;
		this.blurRadiusPx = Math.max(0f, blurRadiusPx);
		this.hairlinePx = Math.max(1f, host.getResources().getDisplayMetrics().density);
		applyAlpha();
		preDrawListener = this::onPreDraw;
		host.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
			@Override
			public void onViewAttachedToWindow(@NonNull View v) {
				v.getViewTreeObserver().addOnPreDrawListener(preDrawListener);
			}

			@Override
			public void onViewDetachedFromWindow(@NonNull View v) {
				v.getViewTreeObserver().removeOnPreDrawListener(preDrawListener);
			}
		});
		if (host.isAttachedToWindow()) host.getViewTreeObserver().addOnPreDrawListener(preDrawListener);
	}

	/** Sets the glass as the host's background so they redraw together. */
	public static void install(@NonNull View host,
	                                      @ColorInt int glassColor,
	                                      @ColorInt int solidColor,
	                                      @ColorInt int baseColor,
	                                      @ColorInt int hairline,
	                                      float blurRadiusPx,
	                                      @NonNull BlurTarget... targets) {
		host.setBackground(new GlassBackground(host, glassColor, solidColor, baseColor, hairline,
						blurRadiusPx, targets));
	}

	/**
	 * Redraws the bar when it or a target moved, showed or hid since the last draw. Target content
	 * needs no redraw here, the blur reads their RenderNodes live.
	 */
	private boolean onPreDraw() {
		if (host.isShown() && !Arrays.equals(captureState(), drawnState)) invalidateSelf();
		return true;
	}

	@Override
	public void draw(@NonNull Canvas canvas) {
		Rect bounds = getBounds();
		if (bounds.isEmpty()) return;
		drawnState = captureState().clone();
		boolean blurred = false;
		// a solid glass colour (Dark, Light) hides the blur anyway, skip it
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && canvas.isHardwareAccelerated()
						&& blurRadiusPx > 0f && Color.alpha(glassColor) < 255) {
			if (blur == null) blur = new Blur(blurRadiusPx);
			blurred = blur.draw(this, canvas, bounds);
		}
		canvas.drawRect(bounds, blurred ? glassPaint : solidPaint);
		if (Color.alpha(hairlineColor) != 0 && alpha != 0) {
			canvas.drawRect(bounds.left, bounds.top, bounds.right, bounds.top + hairlinePx, hairlinePaint);
		}
	}

	/** Host position, then per target: on screen (and recorded), position, size. */
	@NonNull
	private int[] captureState() {
		int size = 2 + targets.length * STATE_SLOTS_PER_TARGET;
		if (currentState.length != size) currentState = new int[size];
		int[] state = currentState;
		host.getLocationInWindow(hostLocation);
		state[0] = hostLocation[0];
		state[1] = hostLocation[1];
		for (int i = 0; i < targets.length; i++) {
			BlurTarget target = targets[i];
			int at = 2 + i * STATE_SLOTS_PER_TARGET;
			boolean shown = target.isShown();
			// 2 once recorded, so the first frame with content draws the blur
			state[at] = shown ? (target.hasContent() ? 2 : 1) : 0;
			if (shown) {
				target.getLocationInWindow(targetLocation);
				state[at + 1] = targetLocation[0];
				state[at + 2] = targetLocation[1];
				state[at + 3] = target.getWidth();
				state[at + 4] = target.getHeight();
			} else {
				state[at + 1] = 0;
				state[at + 2] = 0;
				state[at + 3] = 0;
				state[at + 4] = 0;
			}
		}
		return state;
	}

	@Override
	public void setAlpha(int alpha) {
		int clamped = Math.max(0, Math.min(255, alpha));
		if (this.alpha == clamped) return;
		this.alpha = clamped;
		applyAlpha();
		invalidateSelf();
	}

	private void applyAlpha() {
		glassPaint.setColor(scaleAlpha(glassColor));
		solidPaint.setColor(scaleAlpha(solidColor));
		hairlinePaint.setColor(scaleAlpha(hairlineColor));
	}

	@ColorInt
	private int scaleAlpha(@ColorInt int color) {
		return (color & 0x00FFFFFF) | ((Color.alpha(color) * alpha / 255) << 24);
	}

	@Override
	public int getAlpha() {
		return alpha;
	}

	@Override
	public void setColorFilter(@Nullable ColorFilter colorFilter) {
		glassPaint.setColorFilter(colorFilter);
		solidPaint.setColorFilter(colorFilter);
		hairlinePaint.setColorFilter(colorFilter);
		invalidateSelf();
	}

	@Override
	@SuppressWarnings("deprecation")
	public int getOpacity() {
		return PixelFormat.TRANSLUCENT;
	}

	@RequiresApi(Build.VERSION_CODES.S)
	private static final class Blur {
		@NonNull
		private final RenderNode node = new RenderNode("GlassBackground");

		Blur(float radiusPx) {
			node.setRenderEffect(RenderEffect.createBlurEffect(radiusPx, radiusPx, Shader.TileMode.CLAMP));
		}

		/** @return false if no target is on screen */
		boolean draw(@NonNull GlassBackground glass, @NonNull Canvas canvas, @NonNull Rect bounds) {
			int[] state = glass.drawnState;
			int hostX = state[0];
			int hostY = state[1];
			boolean any = false;
			node.setPosition(bounds);
			node.setAlpha(glass.alpha / 255f);
			RecordingCanvas recording = node.beginRecording(bounds.width(), bounds.height());
			try {
				recording.drawColor(glass.baseColor);
				for (int i = 0; i < glass.targets.length; i++) {
					int at = 2 + i * STATE_SLOTS_PER_TARGET;
					if (state[at] == 0) continue;
					RenderNode content = glass.targets[i].contentNode();
					if (content == null) continue;
					int save = recording.save();
					recording.translate(state[at + 1] - hostX - bounds.left, state[at + 2] - hostY - bounds.top);
					recording.drawRenderNode(content);
					recording.restoreToCount(save);
					any = true;
				}
			} finally {
				node.endRecording();
			}
			if (any) canvas.drawRenderNode(node);
			return any;
		}
	}
}
