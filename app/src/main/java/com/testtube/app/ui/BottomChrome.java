package com.testtube.app.ui;

import android.view.View;
import android.view.ViewTreeObserver;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.testtube.app.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Mini player and bottom bar float over Home, Subscriptions, Library and channel pages (frosted
 * glass in NiiX) and those screens reach under them. This pads list ends and centred messages by
 * whatever height the bars take right now, which changes as they show and hide.
 */
final class BottomChrome implements ViewTreeObserver.OnGlobalLayoutListener {
	@NonNull
	private final View root;
	@NonNull
	private final View miniBar;
	@NonNull
	private final View bottomNav;
	@NonNull
	private final List<View> views = new ArrayList<>();
	private int inset;

	BottomChrome(@NonNull View root, @NonNull View miniBar, @NonNull View bottomNav) {
		this.root = root;
		this.miniBar = miniBar;
		this.bottomNav = bottomNav;
		root.getViewTreeObserver().addOnGlobalLayoutListener(this);
	}

	/** Keeps this view's bottom clear of the bars, on top of its own padding. */
	void add(@Nullable View view) {
		if (view == null || view.getTag(R.id.tag_bottom_chrome_padding) != null) return;
		view.setTag(R.id.tag_bottom_chrome_padding, view.getPaddingBottom());
		views.add(view);
		apply(view);
	}

	/** Same for each of these views under the root. */
	void addAll(@Nullable View parent, @NonNull int... ids) {
		if (parent == null) return;
		for (int id : ids) add(parent.findViewById(id));
	}

	@Override
	public void onGlobalLayout() {
		int next = measure();
		if (next == inset) return;
		inset = next;
		for (View view : views) apply(view);
	}

	void release() {
		ViewTreeObserver observer = root.getViewTreeObserver();
		if (observer.isAlive()) observer.removeOnGlobalLayoutListener(this);
		views.clear();
	}

	/** How high the visible bars reach from the bottom. */
	private int measure() {
		int bottom = root.getHeight() - root.getPaddingBottom();
		int top = bottom;
		if (bottomNav.getVisibility() == View.VISIBLE) top = Math.min(top, bottomNav.getTop());
		if (miniBar.getVisibility() == View.VISIBLE) top = Math.min(top, miniBar.getTop());
		return Math.max(0, bottom - top);
	}

	private void apply(@NonNull View view) {
		Object own = view.getTag(R.id.tag_bottom_chrome_padding);
		int padding = (own instanceof Integer ? (Integer) own : 0) + inset;
		if (view.getPaddingBottom() == padding) return;
		view.setPaddingRelative(view.getPaddingStart(), view.getPaddingTop(), view.getPaddingEnd(), padding);
	}
}
