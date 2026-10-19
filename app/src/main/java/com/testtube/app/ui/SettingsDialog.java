package com.testtube.app.ui;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.slider.Slider;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.testtube.app.AppSettings;
import com.testtube.app.R;
import com.testtube.app.filter.AiChannelList;
import com.testtube.app.filter.ContentFilters;
import com.testtube.app.player.common.PlayerPreferences;
import com.testtube.app.update.UpdateManager;
import com.testtube.app.util.StringUtils;

/**
 * The welcome dialog on the first start and the menu behind the TestTube logo. Both show the same
 * choices; they are saved when the dialog closes.
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
		View view = activity.getLayoutInflater().inflate(R.layout.dialog_settings, null, false);
		view.findViewById(R.id.settings_message).setVisibility(welcome ? View.VISIBLE : View.GONE);
		SwitchMaterial shorts = bind(view, R.id.settings_shorts, AppSettings.showShorts());
		SwitchMaterial subscriptions = bind(view, R.id.settings_subscriptions, AppSettings.showSubscriptions());
		SwitchMaterial aiFilter = bind(view, R.id.settings_ai_filter, aiChannels.isEnabled());
		SwitchMaterial channelPages = bind(view, R.id.settings_channel_pages, AppSettings.fullChannelPages());
		SwitchMaterial greyWatched = bind(view, R.id.settings_grey_watched, AppSettings.greyOutWatched());
		TextView dimLabel = view.findViewById(R.id.settings_watched_dim_label);
		Slider dim = view.findViewById(R.id.settings_watched_dim);
		dim.setValue(AppSettings.watchedDimPercent());
		dimLabel.setText(activity.getString(R.string.setting_watched_dim, AppSettings.watchedDimPercent()));
		dim.addOnChangeListener((slider, value, fromUser) ->
						dimLabel.setText(activity.getString(R.string.setting_watched_dim, Math.round(value))));
		// The darkness only matters while watched videos are greyed out
		enableDim(dimLabel, dim, greyWatched.isChecked());
		greyWatched.setOnCheckedChangeListener((button, checked) -> enableDim(dimLabel, dim, checked));
		TextView thresholdLabel = view.findViewById(R.id.settings_watched_threshold_label);
		Slider threshold = view.findViewById(R.id.settings_watched_threshold);
		threshold.setValue(filters.watchedThreshold());
		thresholdLabel.setText(activity.getString(R.string.setting_watched_threshold, filters.watchedThreshold()));
		threshold.addOnChangeListener((slider, value, fromUser) ->
						thresholdLabel.setText(activity.getString(R.string.setting_watched_threshold, Math.round(value))));
		QualityRow wifi = new QualityRow(activity, view, R.id.settings_quality_wifi,
						R.string.setting_quality_wifi, prefs.getQuality(false));
		QualityRow mobile = new QualityRow(activity, view, R.id.settings_quality_mobile,
						R.string.setting_quality_mobile, prefs.getQuality(true));
		SwitchMaterial floating = bind(view, R.id.settings_floating_player, AppSettings.floatingPlayer());
		SwitchMaterial swipe = bind(view, R.id.settings_swipe_minimize, AppSettings.swipeDownMinimizes());
		SwitchMaterial commenting = bind(view, R.id.settings_commenting, AppSettings.commentingEnabled());
		SwitchMaterial autoUpdate = bind(view, R.id.settings_auto_update, updates.autoCheckEnabled());

		MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity)
						.setView(view)
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
							AppSettings.setFloatingPlayer(floating.isChecked());
							AppSettings.setSwipeDownMinimizes(swipe.isChecked());
							AppSettings.setCommentingEnabled(commenting.isChecked());
							updates.setAutoCheck(autoUpdate.isChecked());
							if (shortsChanged) onShortsChanged.run();
							onSaved.run();
						});
		if (welcome) {
			builder.setTitle(R.string.welcome_title)
							.setPositiveButton(R.string.welcome_start, null);
		} else {
			builder.setTitle(activity.getString(R.string.app_name) + " " + versionName(activity))
							.setPositiveButton(R.string.update_check_now, (dialog, which) -> updates.check(activity, true))
							.setNegativeButton(R.string.update_close, null);
		}
		builder.show();
	}

	@NonNull
	private static SwitchMaterial bind(@NonNull View root, @IdRes int id, boolean checked) {
		SwitchMaterial view = root.findViewById(id);
		view.setChecked(checked);
		return view;
	}

	private static void enableDim(@NonNull TextView label, @NonNull Slider slider, boolean enabled) {
		slider.setEnabled(enabled);
		label.setAlpha(enabled ? 1f : DISABLED_ALPHA);
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

	/** A row that shows a quality and opens the list of qualities when tapped. */
	private static final class QualityRow {
		@NonNull
		String quality;

		QualityRow(@NonNull Activity activity, @NonNull View root, @IdRes int rowId,
		           @StringRes int title, @NonNull String quality) {
			this.quality = quality;
			View row = root.findViewById(rowId);
			TextView valueView = root.findViewById(valueId(rowId));
			valueView.setText(quality);
			row.setOnClickListener(v -> {
				String[] options = PlayerPreferences.QUALITIES;
				new MaterialAlertDialogBuilder(activity)
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

		@IdRes
		private static int valueId(@IdRes int rowId) {
			return rowId == R.id.settings_quality_wifi ? R.id.settings_quality_wifi_value : R.id.settings_quality_mobile_value;
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
