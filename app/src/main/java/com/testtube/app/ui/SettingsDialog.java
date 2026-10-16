package com.testtube.app.ui;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.view.View;

import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.testtube.app.AppSettings;
import com.testtube.app.R;
import com.testtube.app.filter.AiChannelList;
import com.testtube.app.update.UpdateManager;

/**
 * The welcome dialog on the first start and the menu behind the TestTube logo. Both show the same
 * switches; the choices are saved when the dialog closes.
 */
final class SettingsDialog {
	private SettingsDialog() {
	}

	/**
	 * @param onShortsChanged runs when Shorts were switched on or off, so the lists can reload
	 * @param onClosed        runs after the choices are saved
	 */
	static void showWelcome(@NonNull Activity activity,
	                        @NonNull UpdateManager updates,
	                        @NonNull AiChannelList aiChannels,
	                        @NonNull Runnable onShortsChanged,
	                        @NonNull Runnable onClosed) {
		show(activity, updates, aiChannels, true, onShortsChanged, onClosed);
	}

	static void showSettings(@NonNull Activity activity,
	                         @NonNull UpdateManager updates,
	                         @NonNull AiChannelList aiChannels,
	                         @NonNull Runnable onShortsChanged) {
		show(activity, updates, aiChannels, false, onShortsChanged, null);
	}

	private static void show(@NonNull Activity activity,
	                         @NonNull UpdateManager updates,
	                         @NonNull AiChannelList aiChannels,
	                         boolean welcome,
	                         @NonNull Runnable onShortsChanged,
	                         @Nullable Runnable onClosed) {
		View view = activity.getLayoutInflater().inflate(R.layout.dialog_settings, null, false);
		view.findViewById(R.id.settings_message).setVisibility(welcome ? View.VISIBLE : View.GONE);
		SwitchMaterial shorts = bind(view, R.id.settings_shorts, AppSettings.showShorts());
		SwitchMaterial aiFilter = bind(view, R.id.settings_ai_filter, aiChannels.isEnabled());
		SwitchMaterial channelPages = bind(view, R.id.settings_channel_pages, AppSettings.fullChannelPages());
		SwitchMaterial floating = bind(view, R.id.settings_floating_player, AppSettings.floatingPlayer());
		SwitchMaterial swipe = bind(view, R.id.settings_swipe_minimize, AppSettings.swipeDownMinimizes());
		SwitchMaterial autoUpdate = bind(view, R.id.settings_auto_update, updates.autoCheckEnabled());

		MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity)
						.setView(view)
						.setOnDismissListener(dialog -> {
							boolean shortsChanged = shorts.isChecked() != AppSettings.showShorts();
							AppSettings.setShowShorts(shorts.isChecked());
							// Downloads the list when switched on; the lists refresh once it is loaded
							aiChannels.setEnabled(aiFilter.isChecked());
							AppSettings.setFullChannelPages(channelPages.isChecked());
							AppSettings.setFloatingPlayer(floating.isChecked());
							AppSettings.setSwipeDownMinimizes(swipe.isChecked());
							updates.setAutoCheck(autoUpdate.isChecked());
							if (shortsChanged) onShortsChanged.run();
							if (onClosed != null) onClosed.run();
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

	@NonNull
	private static String versionName(@NonNull Activity activity) {
		try {
			String name = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionName;
			return name != null ? name : "";
		} catch (PackageManager.NameNotFoundException e) {
			return "";
		}
	}
}
