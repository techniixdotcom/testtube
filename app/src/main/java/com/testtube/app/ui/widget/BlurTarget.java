package com.testtube.app.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.RecordingCanvas;
import android.graphics.RenderNode;
import android.os.Build;
import android.util.AttributeSet;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

/**
 * A screen the frosted glass (GlassBackground) can blur. On 12+ the children go into our own
 * RenderNode, drawn here as usual and again, blurred, by the glass. Nothing gets copied: change a
 * child and both show it. Older Android just draws normally and the glass is solid.
 * <p>
 * Don't put the glass inside its own target or it blurs itself and the whole thing goes to hell.
 */
public class BlurTarget extends FrameLayout {
	@Nullable
	private Content content;
	/** Children recorded into the node at least once. */
	private boolean recorded;

	public BlurTarget(@NonNull Context context) {
		super(context);
	}

	public BlurTarget(@NonNull Context context, @Nullable AttributeSet attrs) {
		super(context, attrs);
	}

	public BlurTarget(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
		super(context, attrs, defStyleAttr);
	}

	public BlurTarget(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr, int defStyleRes) {
		super(context, attrs, defStyleAttr, defStyleRes);
	}

	@Override
	protected void dispatchDraw(@NonNull Canvas canvas) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && canvas.isHardwareAccelerated()
						&& getWidth() > 0 && getHeight() > 0) {
			if (content == null) content = new Content();
			content.draw(this, canvas);
		} else {
			super.dispatchDraw(canvas);
		}
	}

	private void drawChildren(@NonNull Canvas canvas) {
		super.dispatchDraw(canvas);
	}

	/** Children recorded for the glass (12+, after the first frame). */
	boolean hasContent() {
		return recorded;
	}

	/** Recorded children, or null before the first hardware frame and below 12. */
	@Nullable
	@RequiresApi(Build.VERSION_CODES.S)
	RenderNode contentNode() {
		Content current = content;
		return recorded && current != null ? current.node : null;
	}

	@RequiresApi(Build.VERSION_CODES.S)
	private static final class Content {
		final RenderNode node = new RenderNode("BlurTarget");

		void draw(@NonNull BlurTarget target, @NonNull Canvas canvas) {
			int width = target.getWidth();
			int height = target.getHeight();
			node.setPosition(0, 0, width, height);
			RecordingCanvas recording = node.beginRecording(width, height);
			try {
				target.drawChildren(recording);
			} finally {
				node.endRecording();
			}
			target.recorded = true;
			canvas.drawRenderNode(node);
		}
	}
}
