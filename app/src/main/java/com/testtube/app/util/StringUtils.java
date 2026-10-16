package com.testtube.app.util;

import androidx.annotation.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class StringUtils {
	private static final Pattern DIGIT_PATTERN = Pattern.compile("\\d+");

	/** First number in the text, e.g. 1080 for "1080p". */
	public static int parseHeight(@Nullable String res) {
		if (res == null) return 0;
		Matcher matcher = DIGIT_PATTERN.matcher(res);
		return matcher.find() ? Integer.parseInt(matcher.group()) : 0;
	}
}
