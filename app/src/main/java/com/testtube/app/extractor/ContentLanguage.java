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

/**
 * Asks YouTube for the phone's language and country, so titles, descriptions and dates come in
 * that language whenever YouTube has them (the original title otherwise).
 */
public final class ContentLanguage {
	private static volatile Locale applied;

	private ContentLanguage() {
	}

	/** Call on the main thread; does nothing when the phone's language has not changed. */
	public static void apply(@NonNull Context context) {
		Locale locale = context.getResources().getConfiguration().getLocales().get(0);
		if (locale == null) locale = Locale.getDefault();
		if (locale.equals(applied)) return;
		synchronized (ContentLanguage.class) {
			applied = locale;
			// Read with the English words until this language's are ready.
			Utils.setNumberWords(Map.of());
		}
		// Resolved first and set once each, so a request built meanwhile never sees a half-set value.
		Localization language = closest(new Localization(locale.getLanguage(), region(locale)));
		ContentCountry country = new ContentCountry(country(context, locale));
		NewPipe.setPreferredLocalization(language);
		NewPipe.setPreferredContentCountry(ServiceList.YouTube.getSupportedCountries().contains(country)
						? country : ContentCountry.DEFAULT);
		// Off the main thread; the first counts are only read once the first page has arrived.
		Locale started = locale;
		Thread words = new Thread(() -> {
			try {
				Map<String, Long> found = numberWords(language);
				synchronized (ContentLanguage.class) {
					// The language may have changed again meanwhile; its own words then win.
					if (started.equals(applied)) Utils.setNumberWords(found);
				}
			} catch (RuntimeException ignored) {
				// No CLDR data for this language on the phone: counts are read with the English words.
			}
		}, "testtube-number-words");
		words.setDaemon(true);
		words.start();
	}

	/** The closest language YouTube offers: "es" for Spanish (Spain), "en" for English (Canada)... */
	@NonNull
	private static Localization closest(@NonNull Localization wanted) {
		List<Localization> supported = ServiceList.YouTube.getSupportedLocalizations();
		if (supported.contains(wanted)) return wanted;
		for (Localization language : supported) {
			if (language.getLanguageCode().equals(wanted.getLanguageCode())) return language;
		}
		return Localization.DEFAULT;
	}

	/** YouTube has its own Latin American Spanish and three kinds of Chinese. */
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

	/** The phone's region, or where the phone is when the language has none ("Español (Latinoamérica)"). */
	@NonNull
	private static String country(@NonNull Context context, @NonNull Locale locale) {
		String country = locale.getCountry();
		if (country.length() != 2) {
			try {
				TelephonyManager phone = context.getSystemService(TelephonyManager.class);
				String network = phone == null ? null : phone.getNetworkCountryIso();
				country = network != null && !network.isEmpty() ? network : phone == null ? null : phone.getSimCountryIso();
			} catch (RuntimeException e) {
				// No mobile network on this device (newer Android throws then): YouTube's default country.
				country = null;
			}
		}
		return country == null ? "" : country.toUpperCase(Locale.ROOT);
	}

	/**
	 * The words this language uses in short counts ("mil", "M", "万"...), from the same CLDR data
	 * YouTube's formats come from: every power of ten written short, e.g. 1000 as "1 mil".
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
