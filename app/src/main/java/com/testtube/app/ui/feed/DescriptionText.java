package com.testtube.app.ui.feed;

import android.text.Html;
import android.text.util.Linkify;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Shows a YouTube description: the extractor delivers HTML for videos and plain text elsewhere.
 */
final class DescriptionText {
	private DescriptionText() {
	}

	/**
	 * @return false when there is nothing to show
	 */
	static boolean apply(@NonNull TextView view, @Nullable String text) {
		if (text == null || text.isBlank()) {
			view.setText(null);
			return false;
		}
		if (looksLikeHtml(text)) {
			view.setText(Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY));
		} else {
			view.setText(text);
			Linkify.addLinks(view, Linkify.WEB_URLS);
		}
		return true;
	}

	private static boolean looksLikeHtml(@NonNull String text) {
		return text.contains("<br") || text.contains("<a ") || text.contains("</") || text.contains("&amp;")
						|| text.contains("&quot;") || text.contains("&#");
	}
}
