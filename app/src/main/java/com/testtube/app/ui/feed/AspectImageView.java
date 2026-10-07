package com.testtube.app.ui.feed;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;

/**
 * Image view that is always 16:9, so list rows have their final height before the image loads
 * and the list never jumps while scrolling.
 */
public final class AspectImageView extends AppCompatImageView {
	public AspectImageView(@NonNull Context context) {
		super(context);
	}

	public AspectImageView(@NonNull Context context, @Nullable AttributeSet attrs) {
		super(context, attrs);
	}

	public AspectImageView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
		super(context, attrs, defStyleAttr);
	}

	@Override
	protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
		int width = MeasureSpec.getSize(widthMeasureSpec);
		int height = width * 9 / 16;
		super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
						MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
	}
}
