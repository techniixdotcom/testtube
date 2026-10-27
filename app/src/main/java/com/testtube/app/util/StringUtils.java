package com.testtube.app.util;

import android.icu.text.CompactDecimalFormat;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class StringUtils {
	private static final Pattern DIGIT_PATTERN = Pattern.compile("\\d+");
	@Nullable
	private static CompactDecimalFormat compactFormat;
	@Nullable
	private static Locale compactLocale;

	/** Short counts for views, likes and subscribers: "1.2K", "3,4 M", "12万". Main thread only. */
	@NonNull
	public static String compactCount(long value) {
		Locale locale = Locale.getDefault();
		if (compactFormat == null || !locale.equals(compactLocale)) {
			compactFormat = CompactDecimalFormat.getInstance(locale, CompactDecimalFormat.CompactStyle.SHORT);
			compactLocale = locale;
		}
		return compactFormat.format(value);
	}

	/** First number in the text, e.g. 1080 for "1080p". */
	public static int parseHeight(@Nullable String res) {
		if (res == null) return 0;
		Matcher matcher = DIGIT_PATTERN.matcher(res);
		return matcher.find() ? Integer.parseInt(matcher.group()) : 0;
	}
}
