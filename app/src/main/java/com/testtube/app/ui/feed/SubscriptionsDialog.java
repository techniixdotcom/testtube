package com.testtube.app.ui.feed;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.widget.EditText;

import androidx.annotation.NonNull;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.testtube.app.R;
import com.testtube.app.extractor.FeedClient;
import com.testtube.app.extractor.LocalSubscriptions;
import com.testtube.app.util.ToastUtils;

import java.util.List;

final class SubscriptionsDialog {
	private SubscriptionsDialog() {
	}

	static void show(@NonNull Context context, @NonNull FeedClient client, @NonNull Runnable changed,
	                 @NonNull Runnable importList, @NonNull Runnable exportList) {
		LocalSubscriptions subscriptions = LocalSubscriptions.get();
		List<LocalSubscriptions.Channel> channels = subscriptions.all();
		MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context)
						.setTitle(context.getString(R.string.subs_manage_title, channels.size()))
						.setPositiveButton(R.string.subs_add_url, (dialog, which) -> askForChannel(context, client, changed, importList, exportList))
						.setNeutralButton(R.string.subs_import_export, (dialog, which) ->
										new MaterialAlertDialogBuilder(context)
														.setItems(new String[]{context.getString(R.string.subs_import),
																		context.getString(R.string.subs_export)}, (d, index) -> {
															if (index == 0) importList.run();
															else exportList.run();
														})
														.show())
						.setNegativeButton(android.R.string.cancel, null);
		if (channels.isEmpty()) {
			builder.setMessage(R.string.subs_manage_empty);
		} else {
			String[] names = new String[channels.size()];
			for (int i = 0; i < names.length; i++) {
				LocalSubscriptions.Channel channel = channels.get(i);
				names[i] = channel.name().isBlank() ? channel.id() : channel.name();
			}
			builder.setItems(names, (dialog, index) ->
							confirmUnfollow(context, client, channels.get(index), changed, importList, exportList));
		}
		builder.show();
	}

	private static void confirmUnfollow(@NonNull Context context, @NonNull FeedClient client,
	                                    @NonNull LocalSubscriptions.Channel channel, @NonNull Runnable changed,
	                                    @NonNull Runnable importList, @NonNull Runnable exportList) {
		String name = channel.name().isBlank() ? channel.id() : channel.name();
		new MaterialAlertDialogBuilder(context)
						.setMessage(context.getString(R.string.subs_unfollow_confirm, name))
						.setPositiveButton(R.string.subs_unfollow, (dialog, which) -> {
							LocalSubscriptions.get().remove(channel.id());
							changed.run();
							show(context, client, changed, importList, exportList);
						})
						.setNegativeButton(android.R.string.cancel, (dialog, which) -> show(context, client, changed, importList, exportList))
						.show();
	}

	private static void askForChannel(@NonNull Context context, @NonNull FeedClient client,
	                                  @NonNull Runnable changed, @NonNull Runnable importList, @NonNull Runnable exportList) {
		EditText input = new EditText(context);
		input.setHint(R.string.subs_add_hint);
		input.setSingleLine(true);
		input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
		int padding = (int) (20 * context.getResources().getDisplayMetrics().density);
		input.setPadding(padding, padding, padding, padding);
		new MaterialAlertDialogBuilder(context)
						.setTitle(R.string.subs_add_url)
						.setView(input)
						.setPositiveButton(android.R.string.ok, (dialog, which) -> {
							String text = input.getText().toString().trim();
							if (text.isEmpty()) {
								show(context, client, changed, importList, exportList);
								return;
							}
							ToastUtils.show(context, R.string.subs_looking_up);
							Handler main = new Handler(Looper.getMainLooper());
							client.resolveChannel(text).whenComplete((channel, error) -> main.post(() -> {
								if (error != null || channel == null) {
									ToastUtils.show(context, R.string.subs_add_failed);
								} else {
									LocalSubscriptions.get().add(channel);
									changed.run();
									ToastUtils.show(context, R.string.subs_followed);
								}
								show(context, client, changed, importList, exportList);
							}));
						})
						.setNegativeButton(android.R.string.cancel, (dialog, which) -> show(context, client, changed, importList, exportList))
						.show();
	}
}
