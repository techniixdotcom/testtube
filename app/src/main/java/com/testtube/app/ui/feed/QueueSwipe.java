package com.testtube.app.ui.feed;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.testtube.app.R;
import com.testtube.app.extractor.FeedItem;

/**
 * Swiping a video to the right adds it to the queue. The row slides back into place afterwards.
 */
final class QueueSwipe extends ItemTouchHelper.SimpleCallback {
	interface Target {
		/**
		 * @return the video behind this row, or null when the row cannot be swiped
		 */
		@Nullable
		FeedItem itemFor(@NonNull RecyclerView.ViewHolder holder);

		void enqueue(@NonNull FeedItem item);

		/**
		 * Follows the channel of this video (swipe to the left).
		 */
		void follow(@NonNull FeedItem item);
	}

	@NonNull
	private final Target target;
	@NonNull
	private final ColorDrawable background;
	@Nullable
	private final Drawable logo;
	@NonNull
	private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	@NonNull
	private final String addedLabel;
	@NonNull
	private final String followLabel;
	private final float density;

	QueueSwipe(@NonNull Context context, @NonNull Target target) {
		super(0, ItemTouchHelper.RIGHT | ItemTouchHelper.LEFT);
		this.target = target;
		this.density = context.getResources().getDisplayMetrics().density;
		background = new ColorDrawable(Color.BLACK);
		logo = ContextCompat.getDrawable(context, R.drawable.ic_testtube_logo);
		addedLabel = context.getString(R.string.swipe_added);
		followLabel = context.getString(R.string.swipe_follow);
		labelPaint.setColor(Color.WHITE);
		labelPaint.setTextAlign(Paint.Align.CENTER);
		labelPaint.setTextSize(14 * density);
		labelPaint.setTypeface(Typeface.DEFAULT_BOLD);
	}

	@Override
	public int getSwipeDirs(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
		return target.itemFor(viewHolder) == null ? 0 : super.getSwipeDirs(recyclerView, viewHolder);
	}

	@Override
	public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder,
	                      @NonNull RecyclerView.ViewHolder other) {
		return false;
	}

	@Override
	public float getSwipeThreshold(@NonNull RecyclerView.ViewHolder viewHolder) {
		return 0.35f;
	}

	@Override
	public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
		FeedItem item = target.itemFor(viewHolder);
		if (item != null) {
			if (direction == ItemTouchHelper.RIGHT) target.enqueue(item);
			else target.follow(item);
		}
		RecyclerView.Adapter<?> adapter = viewHolder.getBindingAdapter();
		int position = viewHolder.getBindingAdapterPosition();
		if (adapter != null && position != RecyclerView.NO_POSITION) adapter.notifyItemChanged(position);
	}

	@Override
	public void onChildDraw(@NonNull Canvas canvas, @NonNull RecyclerView recyclerView,
	                        @NonNull RecyclerView.ViewHolder viewHolder, float dX, float dY,
	                        int actionState, boolean isCurrentlyActive) {
		if (dX != 0) {
			View row = viewHolder.itemView;
			boolean right = dX > 0;
			int width = (int) Math.abs(dX);
			int left = right ? row.getLeft() : row.getRight() - width;
			background.setBounds(left, row.getTop(), left + width, row.getBottom());
			background.draw(canvas);
			int logoSize = (int) (40 * density);
			int gap = (int) (6 * density);
			int block = logoSize + gap + (int) (16 * density);
			// The logo and its word appear once the row has slid far enough to hold them.
			if (logo != null && width > block + 16 * density) {
				float centerX = right ? row.getLeft() + 56 * density : row.getRight() - 56 * density;
				int top = row.getTop() + (row.getHeight() - block) / 2;
				logo.setBounds((int) centerX - logoSize / 2, top, (int) centerX + logoSize / 2, top + logoSize);
				logo.draw(canvas);
				canvas.drawText(right ? addedLabel : followLabel, centerX,
								top + logoSize + gap + 12 * density, labelPaint);
			}
		}
		super.onChildDraw(canvas, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive);
	}
}
