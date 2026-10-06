package com.testtube.app.player;

import android.app.Activity;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.squareup.picasso.Picasso;
import com.testtube.app.R;

/**
 * Bar at the bottom of the screen that holds the playing video after it was swiped down.
 */
final class MiniBar {
	private static final float DISABLED_ALPHA = 0.38f;

	@Nullable
	private final View root;
	@Nullable
	private final ImageView thumbnail;
	@Nullable
	private final TextView title;
	@Nullable
	private final TextView author;
	@Nullable
	private final ImageButton previous;
	@Nullable
	private final ImageButton playPause;
	@Nullable
	private final ImageButton next;
	@Nullable
	private final ImageButton close;
	@Nullable
	private String thumbnailUrl;

	MiniBar(@NonNull Activity activity) {
		root = activity.findViewById(R.id.mini_bar);
		thumbnail = activity.findViewById(R.id.mini_bar_thumbnail);
		title = activity.findViewById(R.id.mini_bar_title);
		author = activity.findViewById(R.id.mini_bar_author);
		previous = activity.findViewById(R.id.mini_bar_previous);
		playPause = activity.findViewById(R.id.mini_bar_play_pause);
		next = activity.findViewById(R.id.mini_bar_next);
		close = activity.findViewById(R.id.mini_bar_close);
	}

	void setActions(@NonNull Runnable onRestore,
	                @NonNull Runnable onPrevious,
	                @NonNull Runnable onPlayPause,
	                @NonNull Runnable onNext,
	                @NonNull Runnable onClose) {
		if (root != null) root.setOnClickListener(v -> onRestore.run());
		if (previous != null) previous.setOnClickListener(v -> onPrevious.run());
		if (playPause != null) playPause.setOnClickListener(v -> onPlayPause.run());
		if (next != null) next.setOnClickListener(v -> onNext.run());
		if (close != null) close.setOnClickListener(v -> onClose.run());
	}

	void show() {
		if (root != null) root.setVisibility(View.VISIBLE);
	}

	void hide() {
		if (root != null) root.setVisibility(View.GONE);
	}

	boolean isShown() {
		return root != null && root.getVisibility() == View.VISIBLE;
	}

	void setVideo(@Nullable String videoTitle,
	              @Nullable String videoAuthor,
	              @Nullable String videoThumbnailUrl) {
		if (title != null) title.setText(videoTitle == null ? "" : videoTitle);
		if (author != null) {
			author.setText(videoAuthor == null ? "" : videoAuthor);
			author.setVisibility(videoAuthor == null || videoAuthor.isBlank() ? View.GONE : View.VISIBLE);
		}
		if (thumbnail == null) return;
		if (videoThumbnailUrl == null || videoThumbnailUrl.isBlank()) {
			thumbnailUrl = null;
			thumbnail.setImageResource(R.drawable.ic_thumbnail_placeholder);
			return;
		}
		if (videoThumbnailUrl.equals(thumbnailUrl)) return;
		thumbnailUrl = videoThumbnailUrl;
		Picasso.get()
						.load(videoThumbnailUrl)
						.placeholder(R.drawable.ic_thumbnail_placeholder)
						.error(R.drawable.ic_thumbnail_placeholder)
						.into(thumbnail);
	}

	void setPlaying(boolean playing) {
		if (playPause == null) return;
		playPause.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
		playPause.setContentDescription(playPause.getContext().getString(
						playing ? R.string.mini_bar_pause : R.string.mini_bar_play));
	}

	void setNavigation(boolean previousEnabled, boolean nextEnabled) {
		setEnabled(previous, previousEnabled);
		setEnabled(next, nextEnabled);
	}

	private static void setEnabled(@Nullable ImageButton button, boolean enabled) {
		if (button == null) return;
		button.setEnabled(enabled);
		button.setAlpha(enabled ? 1.0f : DISABLED_ALPHA);
	}
}
