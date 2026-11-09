package com.testtube.app.util;

import android.icu.text.CompactDecimalFormat;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class StringUtils {
	/** Between meta line parts: "Channel • 12K views • 3 days ago" */
	public static final String META_SEPARATOR = " • ";
	private static final Pattern DIGIT_PATTERN = Pattern.compile("\\d+");
	private static final char[] HEX = "0123456789abcdef".toCharArray();
	@Nullable
	private static CompactDecimalFormat compactFormat;
	@Nullable
	private static Locale compactLocale;

	/** Compact counts: "1.2K", "3,4 M", "12万". Main thread only. */
	@NonNull
	public static String compactCount(long value) {
		Locale locale = Locale.getDefault();
		if (compactFormat == null || !locale.equals(compactLocale)) {
			compactFormat = CompactDecimalFormat.getInstance(locale, CompactDecimalFormat.CompactStyle.SHORT);
			compactLocale = locale;
		}
		return compactFormat.format(value);
	}

	/** First number in the text, 1080 for "1080p". */
	public static int parseHeight(@Nullable String res) {
		if (res == null) return 0;
		Matcher matcher = DIGIT_PATTERN.matcher(res);
		return matcher.find() ? Integer.parseInt(matcher.group()) : 0;
	}

	/** Lowercase hex. */
	@NonNull
	public static String hex(@NonNull byte[] bytes) {
		char[] out = new char[bytes.length * 2];
		for (int i = 0; i < bytes.length; i++) {
			out[i * 2] = HEX[(bytes[i] >> 4) & 0xF];
			out[i * 2 + 1] = HEX[bytes[i] & 0xF];
		}
		return new String(out);
	}
}
