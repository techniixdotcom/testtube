package com.testtube.app.ui;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.TextView;

import androidx.annotation.LayoutRes;
import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatDelegate;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;
import com.testtube.app.AppGraph;
import com.testtube.app.AppSettings;
import com.testtube.app.R;
import com.testtube.app.filter.AiChannelList;
import com.testtube.app.filter.ContentFilters;
import com.testtube.app.player.common.PlayerPreferences;
import com.testtube.app.player.controller.gesture.GestureAction;
import com.testtube.app.update.UpdateManager;
import com.testtube.app.util.StringUtils;
import com.testtube.app.util.ToastUtils;

/**
 * The welcome dialog on the first start and the settings behind the TestTube logo. Both show the
 * same choices, grouped by topic; they are saved when the dialog closes.
 */
final class SettingsDialog {
	private static final float DISABLED_ALPHA = 0.38f;

	private SettingsDialog() {
	}

	/**
	 * @param onShortsChanged runs when Shorts were switched on or off, so the lists can reload
	 * @param onSaved         runs after the choices are saved
	 */
	static void showWelcome(@NonNull Activity activity,
	                        @NonNull UpdateManager updates,
	                        @NonNull AiChannelList aiChannels,
	                        @NonNull PlayerPreferences prefs,
	                        @NonNull ContentFilters filters,
	                        @NonNull Runnable onShortsChanged,
	                        @NonNull Runnable onSaved) {
		show(activity, updates, aiChannels, prefs, filters, true, onShortsChanged, onSaved);
	}

	static void showSettings(@NonNull Activity activity,
	                         @NonNull UpdateManager updates,
	                         @NonNull AiChannelList aiChannels,
	                         @NonNull PlayerPreferences prefs,
	                         @NonNull ContentFilters filters,
	                         @NonNull Runnable onShortsChanged,
	                         @NonNull Runnable onSaved) {
		show(activity, updates, aiChannels, prefs, filters, false, onShortsChanged, onSaved);
	}

	private static void show(@NonNull Activity activity,
	                         @NonNull UpdateManager updates,
	                         @NonNull AiChannelList aiChannels,
	                         @NonNull PlayerPreferences prefs,
	                         @NonNull ContentFilters filters,
	                         boolean welcome,
	                         @NonNull Runnable onShortsChanged,
	                         @NonNull Runnable onSaved) {
		LayoutInflater inflater = activity.getLayoutInflater();
		View view = inflater.inflate(R.layout.dialog_settings, null, false);
		Form form = new Form(activity, inflater, view.findViewById(R.id.settings_content));

		ViewGroup appearance = form.section(R.string.settings_section_appearance);
		AppSettings.Theme[] themeValues = AppSettings.Theme.values();
		int[] themeLabels = new int[themeValues.length];
		for (int i = 0; i < themeValues.length; i++) themeLabels[i] = themeValues[i].label;
		ChoiceRow<AppSettings.Theme> theme = new ChoiceRow<>(form, appearance, R.string.setting_theme, 0,
						themeValues, themeLabels, AppSettings.theme());

		ViewGroup feeds = form.section(R.string.settings_section_content);
		MaterialSwitch shorts = form.toggle(feeds, R.string.setting_shorts, R.string.setting_shorts_summary,
						AppSettings.showShorts());
		MaterialSwitch subscriptions = form.toggle(feeds, R.string.setting_subscriptions,
						R.string.setting_subscriptions_summary, AppSettings.showSubscriptions());
		MaterialSwitch channelPages = form.toggle(feeds, R.string.setting_channel_pages,
						R.string.setting_channel_pages_summary, AppSettings.fullChannelPages());
		MaterialSwitch aiFilter = form.toggle(feeds, R.string.setting_ai_filter, R.string.setting_ai_filter_summary,
						aiChannels.isEnabled());

		ViewGroup watched = form.section(R.string.settings_section_watched);
		MaterialSwitch greyWatched = form.toggle(watched, R.string.setting_grey_watched,
						R.string.setting_grey_watched_summary, AppSettings.greyOutWatched());
		Slider dim = form.percentSlider(watched, R.string.setting_watched_dim_title, 0,
						R.string.setting_watched_dim_description, 20, 90, AppSettings.watchedDimPercent());
		Slider threshold = form.percentSlider(watched, R.string.setting_watched_threshold_title,
						R.string.setting_watched_threshold_summary, R.string.setting_watched_threshold_description,
						50, 100, filters.watchedThreshold());
		// The darkness only matters while watched videos are greyed out
		View dimRow = (View) dim.getParent();
		enable(dimRow, dim, greyWatched.isChecked());
		greyWatched.setOnCheckedChangeListener((button, checked) -> enable(dimRow, dim, checked));

		ViewGroup playback = form.section(R.string.settings_section_playback);
		QualityRow wifi = new QualityRow(form, playback, R.string.setting_quality_wifi, 0, prefs.getQuality(false));
		QualityRow mobile = new QualityRow(form, playback, R.string.setting_quality_mobile,
						R.string.setting_quality_summary, prefs.getQuality(true));
		ChoiceRow<Boolean> minimizeTo = new ChoiceRow<>(form, playback, R.string.setting_minimize_to,
						R.string.setting_minimize_summary, new Boolean[]{false, true},
						new int[]{R.string.minimize_bottom_bar, R.string.minimize_floating}, AppSettings.floatingPlayer());

		AppSettings.VideoCache[] cacheValues = AppSettings.VideoCache.values();
		int[] cacheLabels = new int[cacheValues.length];
		int[] cacheShortLabels = new int[cacheValues.length];
		for (int i = 0; i < cacheValues.length; i++) {
			cacheLabels[i] = cacheValues[i].label;
			cacheShortLabels[i] = cacheValues[i].shortLabel;
		}
		ChoiceRow<AppSettings.VideoCache> videoCache = new ChoiceRow<>(form, playback, R.string.setting_video_cache,
						R.string.setting_video_cache_summary, cacheValues, cacheLabels, cacheShortLabels,
						AppSettings.videoCache());

		View clearCache = form.value(playback, R.string.setting_clear_cache, R.string.setting_clear_cache_summary,
						PENDING_SIZE);
		TextView cacheSize = clearCache.findViewById(R.id.setting_value);
		showCacheSize(activity, cacheSize);
		clearCache.setOnClickListener(v -> new MaterialAlertDialogBuilder(activity)
						.setTitle(R.string.setting_clear_cache)
						.setMessage(activity.getString(R.string.clear_cache_confirm, cacheSize.getText()))
						.setNegativeButton(R.string.cancel, null)
						.setPositiveButton(R.string.clear, (dialog, which) -> clearCache(activity, cacheSize))
						.show());

		ViewGroup gestures = form.section(R.string.settings_section_gestures);
		MaterialSwitch customGestures = form.toggle(gestures, R.string.setting_gestures,
						R.string.setting_gestures_summary, AppSettings.gesturesEnabled());
		ChoiceRow<GestureAction.Vertical> left = verticalRow(form, gestures, R.string.setting_gesture_left,
						AppSettings.savedGestureLeft());
		ChoiceRow<GestureAction.Vertical> right = verticalRow(form, gestures, R.string.setting_gesture_right,
						AppSettings.savedGestureRight());
		GestureAction.Horizontal[] horizontalValues = GestureAction.Horizontal.values();
		ChoiceRow<GestureAction.Horizontal> horizontal = new ChoiceRow<>(form, gestures,
						R.string.setting_gesture_horizontal, 0, horizontalValues, labels(horizontalValues),
						AppSettings.savedGestureHorizontal());
		GestureAction.DoubleTap[] doubleTapValues = GestureAction.DoubleTap.values();
		ChoiceRow<GestureAction.DoubleTap> doubleTap = new ChoiceRow<>(form, gestures,
						R.string.setting_gesture_double_tap, 0, doubleTapValues, labels(doubleTapValues),
						AppSettings.savedGestureDoubleTap());
		// The choices only apply with custom gestures on
		ChoiceRow<?>[] gestureRows = {left, right, horizontal, doubleTap};
		for (ChoiceRow<?> row : gestureRows) row.setEnabled(customGestures.isChecked());
		customGestures.setOnCheckedChangeListener((button, checked) -> {
			for (ChoiceRow<?> row : gestureRows) row.setEnabled(checked);
		});

		ViewGroup comments = form.section(R.string.settings_section_comments);
		MaterialSwitch commenting = form.toggle(comments, R.string.setting_commenting,
						R.string.setting_commenting_summary, AppSettings.commentingEnabled());

		ViewGroup app = form.section(R.string.settings_section_updates);
		MaterialSwitch autoUpdate = form.toggle(app, R.string.update_auto, R.string.update_auto_summary,
						updates.autoCheckEnabled());
		MaterialSwitch prereleases = form.toggle(app, R.string.setting_prereleases,
						R.string.setting_prereleases_summary, updates.prereleasesEnabled());
		// Saved right away, so "Check for updates" below already uses it
		prereleases.setOnCheckedChangeListener((button, checked) -> updates.setPrereleases(checked));
		if (!welcome) {
			form.value(app, R.string.update_check_now, 0, "").setOnClickListener(v -> updates.check(activity, true));
		}

		View header = inflater.inflate(R.layout.dialog_settings_header, null, false);
		((TextView) header.findViewById(R.id.settings_title)).setText(welcome ? R.string.welcome_title : R.string.settings_title);
		((TextView) header.findViewById(R.id.settings_subtitle)).setText(welcome
						? activity.getString(R.string.welcome_message)
						: activity.getString(R.string.app_name) + " " + versionName(activity));

		new MaterialAlertDialogBuilder(activity)
						.setCustomTitle(header)
						.setView(view)
						.setPositiveButton(welcome ? R.string.welcome_start : R.string.update_close, null)
						.setOnDismissListener(dialog -> {
							boolean shortsChanged = shorts.isChecked() != AppSettings.showShorts();
							AppSettings.setShowShorts(shorts.isChecked());
							AppSettings.setShowSubscriptions(subscriptions.isChecked());
							// Downloads the list when switched on; the lists refresh once it is loaded
							aiChannels.setEnabled(aiFilter.isChecked());
							AppSettings.setFullChannelPages(channelPages.isChecked());
							AppSettings.setGreyOutWatched(greyWatched.isChecked());
							AppSettings.setWatchedDimPercent(Math.round(dim.getValue()));
							filters.setWatchedThreshold(Math.round(threshold.getValue()));
							prefs.setQuality(false, wifi.quality);
							prefs.setQuality(true, mobile.quality);
							AppSettings.setFloatingPlayer(minimizeTo.value);
							if (videoCache.value != AppSettings.videoCache()) {
								AppSettings.setVideoCache(videoCache.value);
								AppGraph.of(activity).applyVideoCacheSize();
							}
							AppSettings.setGesturesEnabled(customGestures.isChecked());
							AppSettings.setGestures(left.value, right.value, horizontal.value, doubleTap.value);
							AppSettings.setCommentingEnabled(commenting.isChecked());
							updates.setAutoCheck(autoUpdate.isChecked());
							updates.setPrereleases(prereleases.isChecked());
							if (shortsChanged) onShortsChanged.run();
							boolean themeChanged = theme.value != AppSettings.theme();
							AppSettings.setTheme(theme.value);
							onSaved.run();
							if (themeChanged) applyTheme(activity, theme.value);
						})
						.show();
	}

	/**
	 * Switches the colours right away. The screen is built again when they change, and an open
	 * video reopens where it was.
	 */
	private static void applyTheme(@NonNull Activity activity, @NonNull AppSettings.Theme theme) {
		boolean wasNight = isNight(activity.getResources().getConfiguration());
		AppCompatDelegate.setDefaultNightMode(theme.nightMode);
		boolean night = theme == AppSettings.Theme.DARK
						|| theme == AppSettings.Theme.SYSTEM && isNight(Resources.getSystem().getConfiguration());
		if (night == wasNight || activity.isFinishing() || activity.isDestroyed()) return;
		if (activity instanceof MainActivity) {
			((MainActivity) activity).recreateForTheme();
		} else {
			activity.recreate();
		}
	}

	private static boolean isNight(@NonNull Configuration config) {
		return (config.uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
	}

	/** Shown while the cache size is being added up. */
	private static final String PENDING_SIZE = "…";

	/** Adds up the cache in the background and shows it, e.g. "87 MB". */
	private static void showCacheSize(@NonNull Activity activity, @NonNull TextView view) {
		AppGraph app = AppGraph.of(activity);
		app.executor().execute(() -> {
			long bytes = app.cacheSize();
			activity.runOnUiThread(() -> view.setText(Formatter.formatShortFileSize(activity, bytes)));
		});
	}

	/**
	 * Empties the WebViews' cache (which has to happen on the main thread) and then the app's own
	 * caches in the background; downloads, history, the queue, sign-in and settings stay.
	 */
	private static void clearCache(@NonNull Activity activity, @NonNull TextView sizeView) {
		try {
			WebView web = new WebView(activity);
			web.clearCache(true);
			web.destroy();
		} catch (RuntimeException e) {
			// No WebView provider right now; the rest is still cleared
		}
		sizeView.setText(PENDING_SIZE);
		AppGraph app = AppGraph.of(activity);
		app.executor().execute(() -> {
			app.clearCaches();
			long bytes = app.cacheSize();
			activity.runOnUiThread(() -> {
				sizeView.setText(Formatter.formatShortFileSize(activity, bytes));
				ToastUtils.show(activity, R.string.cache_cleared);
			});
		});
	}

	private static void enable(@NonNull View row, @NonNull Slider slider, boolean enabled) {
		slider.setEnabled(enabled);
		row.setAlpha(enabled ? 1f : DISABLED_ALPHA);
	}

	@NonNull
	private static String versionName(@NonNull Activity activity) {
		try {
			String name = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionName;
			return name != null ? name : "";
		} catch (PackageManager.NameNotFoundException e) {
			return "";
		}
	}

	/** Builds the groups and rows of the dialog from small row layouts. */
	private record Form(@NonNull Activity activity, @NonNull LayoutInflater inflater, @NonNull ViewGroup content) {
		/** Adds a titled group and returns the container for its rows. */
		@NonNull
		ViewGroup section(@StringRes int title) {
			View section = inflater.inflate(R.layout.item_settings_section, content, false);
			((TextView) section.findViewById(R.id.section_title)).setText(title);
			content.addView(section);
			return section.findViewById(R.id.section_rows);
		}

		@NonNull
		MaterialSwitch toggle(@NonNull ViewGroup group, @StringRes int title, @StringRes int summary, boolean checked) {
			View row = row(group, R.layout.item_settings_switch, title, summary);
			MaterialSwitch toggle = row.findViewById(R.id.setting_switch);
			toggle.setChecked(checked);
			row.setOnClickListener(v -> toggle.toggle());
			return toggle;
		}

		/** A 0–100 % slider whose value shows next to the title. */
		@NonNull
		Slider percentSlider(@NonNull ViewGroup group, @StringRes int title, @StringRes int summary,
		                     @StringRes int description, int from, int to, int value) {
			View row = row(group, R.layout.item_settings_slider, title, summary);
			TextView valueView = row.findViewById(R.id.setting_value);
			Slider slider = row.findViewById(R.id.setting_slider);
			slider.setValueFrom(from);
			slider.setValueTo(to);
			slider.setStepSize(5);
			// The slider only accepts whole steps inside its range
			int clamped = Math.max(from, Math.min(to, value));
			slider.setValue(from + Math.round((clamped - from) / 5f) * 5);
			slider.setContentDescription(activity.getString(description));
			valueView.setText(activity.getString(R.string.percent_value, Math.round(slider.getValue())));
			slider.addOnChangeListener((s, v, fromUser) ->
							valueView.setText(activity.getString(R.string.percent_value, Math.round(v))));
			return slider;
		}

		/** A row showing a value; returns the row so the caller can make it open a choice. */
		@NonNull
		View value(@NonNull ViewGroup group, @StringRes int title, @StringRes int summary, @NonNull String value) {
			View row = row(group, R.layout.item_settings_value, title, summary);
			((TextView) row.findViewById(R.id.setting_value)).setText(value);
			return row;
		}

		@NonNull
		private View row(@NonNull ViewGroup group, @LayoutRes int layout, @StringRes int title, @StringRes int summary) {
			View row = inflater.inflate(layout, group, false);
			((TextView) row.findViewById(R.id.setting_title)).setText(title);
			TextView summaryView = row.findViewById(R.id.setting_summary);
			if (summary != 0) summaryView.setText(summary);
			else summaryView.setVisibility(View.GONE);
			group.addView(row);
			return row;
		}
	}

	@NonNull
	private static ChoiceRow<GestureAction.Vertical> verticalRow(@NonNull Form form, @NonNull ViewGroup group,
	                                                            @StringRes int title,
	                                                            @NonNull GestureAction.Vertical current) {
		GestureAction.Vertical[] values = GestureAction.Vertical.values();
		return new ChoiceRow<>(form, group, title, 0, values, labels(values), current);
	}

	@NonNull
	private static int[] labels(@NonNull GestureAction.Vertical[] values) {
		int[] out = new int[values.length];
		for (int i = 0; i < values.length; i++) out[i] = values[i].label;
		return out;
	}

	@NonNull
	private static int[] labels(@NonNull GestureAction.Horizontal[] values) {
		int[] out = new int[values.length];
		for (int i = 0; i < values.length; i++) out[i] = values[i].label;
		return out;
	}

	@NonNull
	private static int[] labels(@NonNull GestureAction.DoubleTap[] values) {
		int[] out = new int[values.length];
		for (int i = 0; i < values.length; i++) out[i] = values[i].label;
		return out;
	}

	/** A row showing one of a few choices, which opens the list of them when tapped. */
	private static final class ChoiceRow<T> {
		@NonNull
		T value;
		@NonNull
		private final View row;

		ChoiceRow(@NonNull Form form, @NonNull ViewGroup group, @StringRes int title, @StringRes int summary,
		          @NonNull T[] values, @NonNull int[] labels, @NonNull T current) {
			this(form, group, title, summary, values, labels, labels, current);
		}

		/**
		 * @param labels      the choices as listed when picking one
		 * @param shortLabels the same, short enough to show next to the title
		 */
		ChoiceRow(@NonNull Form form, @NonNull ViewGroup group, @StringRes int title, @StringRes int summary,
		          @NonNull T[] values, @NonNull int[] labels, @NonNull int[] shortLabels, @NonNull T current) {
			this.value = current;
			Activity activity = form.activity();
			String[] names = new String[labels.length];
			String[] shortNames = new String[shortLabels.length];
			for (int i = 0; i < labels.length; i++) {
				names[i] = activity.getString(labels[i]);
				shortNames[i] = activity.getString(shortLabels[i]);
			}
			row = form.value(group, title, summary, shortNames[indexOf(values, current)]);
			TextView valueView = row.findViewById(R.id.setting_value);
			row.setOnClickListener(v -> new MaterialAlertDialogBuilder(activity)
							.setTitle(title)
							.setSingleChoiceItems(names, indexOf(values, value), (dialog, which) -> {
								value = values[which];
								valueView.setText(shortNames[which]);
								dialog.dismiss();
							})
							.setNegativeButton(R.string.cancel, null)
							.show());
		}

		void setEnabled(boolean enabled) {
			row.setEnabled(enabled);
			row.setAlpha(enabled ? 1f : DISABLED_ALPHA);
		}

		private static <T> int indexOf(@NonNull T[] values, @NonNull T value) {
			for (int i = 0; i < values.length; i++) {
				if (values[i].equals(value)) return i;
			}
			return 0;
		}
	}

	/** A row that shows a quality and opens the list of qualities when tapped. */
	private static final class QualityRow {
		@NonNull
		String quality;

		QualityRow(@NonNull Form form, @NonNull ViewGroup group, @StringRes int title,
		           @StringRes int summary, @NonNull String quality) {
			this.quality = quality;
			View row = form.value(group, title, summary, quality);
			TextView valueView = row.findViewById(R.id.setting_value);
			row.setOnClickListener(v -> {
				String[] options = PlayerPreferences.QUALITIES;
				new MaterialAlertDialogBuilder(form.activity())
								.setTitle(title)
								.setSingleChoiceItems(options, closest(options, this.quality), (dialog, which) -> {
									this.quality = options[which];
									valueView.setText(this.quality);
									dialog.dismiss();
								})
								.setNegativeButton(R.string.cancel, null)
								.show();
			});
		}

		/** The offered quality nearest to the saved one, which may be a player pick like "720p60". */
		private static int closest(@NonNull String[] options, @NonNull String quality) {
			int height = StringUtils.parseHeight(quality);
			int best = -1;
			for (int i = 0; i < options.length; i++) {
				if (StringUtils.parseHeight(options[i]) <= height) best = i;
			}
			return best;
		}
	}
}
