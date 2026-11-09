package com.testtube.app.extractor;

import android.content.Context;
import android.icu.text.CompactDecimalFormat;
import android.icu.util.ULocale;
import android.telephony.TelephonyManager;

import androidx.annotation.NonNull;

import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.localization.ContentCountry;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.utils.Utils;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** YouTube content language and country from the system locale. */
public final class ContentLanguage {
	private static volatile Locale applied;

	private ContentLanguage() {
	}

	/** Main thread. No-op if the locale didn't change. */
	public static void apply(@NonNull Context context) {
		Locale locale = context.getResources().getConfiguration().getLocales().get(0);
		if (locale == null) locale = Locale.getDefault();
		if (locale.equals(applied)) return;
		synchronized (ContentLanguage.class) {
			applied = locale;
			// English number words until this locale's are ready
			Utils.setNumberWords(Map.of());
		}
		// resolve both first so other threads never see half a locale
		Localization language = closest(new Localization(locale.getLanguage(), region(locale)));
		ContentCountry country = new ContentCountry(country(context, locale));
		NewPipe.setPreferredLocalization(language);
		NewPipe.setPreferredContentCountry(ServiceList.YouTube.getSupportedCountries().contains(country)
						? country : ContentCountry.DEFAULT);

		Locale started = locale;
		Thread words = new Thread(() -> {
			try {
				Map<String, Long> found = numberWords(language);
				synchronized (ContentLanguage.class) {
					// locale may have changed again meanwhile
					if (started.equals(applied)) Utils.setNumberWords(found);
				}
			} catch (RuntimeException ignored) {
				// no ICU data, keep English
			}
		}, "testtube-number-words");
		words.setDaemon(true);
		words.start();
	}

	/** Closest language YouTube supports (es-ES to "es", en-CA to "en"). */
	@NonNull
	private static Localization closest(@NonNull Localization wanted) {
		List<Localization> supported = ServiceList.YouTube.getSupportedLocalizations();
		if (supported.contains(wanted)) return wanted;
		for (Localization language : supported) {
			if (language.getLanguageCode().equals(wanted.getLanguageCode())) return language;
		}
		return Localization.DEFAULT;
	}

	/** YouTube has separate Latin American Spanish and Chinese variants. */
	@NonNull
	private static String region(@NonNull Locale locale) {
		String country = locale.getCountry();
		switch (locale.getLanguage()) {
			case "es":
				return country.isEmpty() || "ES".equals(country) || "US".equals(country) ? country : "419";
			case "zh":
				if ("HK".equals(country) || "MO".equals(country)) return "HK";
				return "TW".equals(country) || "Hant".equals(locale.getScript()) ? "TW" : "CN";
			default:
				return country;
		}
	}

	@NonNull
	private static String country(@NonNull Context context, @NonNull Locale locale) {
		String country = locale.getCountry();
		if (country.length() != 2) {
			country = null;
			try {
				TelephonyManager phone = context.getSystemService(TelephonyManager.class);
				if (phone != null) {
					country = phone.getNetworkCountryIso();
					if (country == null || country.isEmpty()) country = phone.getSimCountryIso();
				}
			} catch (RuntimeException ignored) {
				// newer Android throws on devices without telephony. Yes, really. A getter.
			}
		}
		return country == null ? "" : country.toUpperCase(Locale.ROOT);
	}

	/**
	 * Compact number suffixes ("mil", "M", "万"...), found by formatting every power of ten with
	 * ICU (1000 is "1 mil" in Spanish).
	 */
	@NonNull
	private static Map<String, Long> numberWords(@NonNull Localization language) {
		Map<String, Long> words = new HashMap<>();
		CompactDecimalFormat format = CompactDecimalFormat.getInstance(
						ULocale.forLanguageTag(language.getLocalizationCode()), CompactDecimalFormat.CompactStyle.SHORT);
		long power = 1_000L;
		for (int exponent = 3; exponent <= 15; exponent++, power *= 10) {
			String text = format.format(power);
			int first = -1;
			int last = -1;
			long shown = 0;
			for (int i = 0; i < text.length(); i++) {
				if (!Character.isDigit(text.charAt(i))) continue;
				if (first < 0) first = i;
				last = i;
				shown = shown * 10 + Character.digit(text.charAt(i), 10);
			}
			if (shown <= 0 || power % shown != 0) continue;
			words.putIfAbsent(text.substring(last + 1), power / shown);
			words.putIfAbsent(text.substring(0, first), power / shown);
		}
		return words;
	}
}
