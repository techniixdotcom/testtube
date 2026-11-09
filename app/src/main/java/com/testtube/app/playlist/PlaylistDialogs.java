package com.testtube.app.playlist;

import android.content.Context;
import android.text.InputFilter;
import android.text.InputType;
import android.view.WindowManager;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;

import com.testtube.app.R;
import com.testtube.app.nav.MediaItemMenuPayload;
import com.testtube.app.ui.Dialogs;
import com.testtube.app.util.ToastUtils;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/** The small dialogs around playlists: save a video, name one, delete one. */
public final class PlaylistDialogs {
	private PlaylistDialogs() {
	}

	/**
	 * Save sheet: every playlist with a tick where the video already is, Watch later first.
	 * Ticks apply right away; New playlist creates one with the video in it.
	 */
	public static void save(@NonNull Context context, @NonNull MediaItemMenuPayload video) {
		LocalPlaylists store = LocalPlaylists.of(context);
		List<LocalPlaylists.Playlist> playlists = store.all();
		Set<String> holding = store.containing(video.videoId());
		String[] names = new String[playlists.size()];
		boolean[] checked = new boolean[playlists.size()];
		for (int i = 0; i < names.length; i++) {
			LocalPlaylists.Playlist playlist = playlists.get(i);
			names[i] = playlist.name(context);
			checked[i] = holding.contains(playlist.id());
		}
		Dialogs.builder(context)
						.setTitle(R.string.playlist_save_title)
						.setMultiChoiceItems(names, checked, (dialog, index, isChecked) -> {
							LocalPlaylists.Playlist playlist = playlists.get(index);
							if (isChecked) {
								if (store.add(playlist.id(), entry(video))) {
									ToastUtils.show(context, context.getString(R.string.playlist_saved_to, names[index]));
								} else {
									ToastUtils.show(context, R.string.playlist_full);
									((AlertDialog) dialog).getListView().setItemChecked(index, false);
								}
							} else {
								store.remove(playlist.id(), video.videoId());
								ToastUtils.show(context, context.getString(R.string.playlist_removed_from, names[index]));
							}
						})
						.setNeutralButton(R.string.playlist_new, (dialog, which) -> askName(context, R.string.playlist_new, null, name -> {
							String id = store.create(name);
							if (id == null) {
								ToastUtils.show(context, R.string.playlist_full);
								return;
							}
							store.add(id, entry(video));
							ToastUtils.show(context, context.getString(R.string.playlist_saved_to, name.trim()));
						}))
						.setPositiveButton(R.string.action_done, null)
						.show();
	}

	/** Asks for a name; {@code done} gets it only if it isn't blank. */
	public static void askName(@NonNull Context context, @StringRes int title, @Nullable String current,
	                           @NonNull Consumer<String> done) {
		EditText input = new EditText(context);
		input.setHint(R.string.playlist_name_hint);
		input.setSingleLine(true);
		input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
		input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(LocalPlaylists.MAX_NAME_LENGTH)});
		if (current != null) {
			input.setText(current);
			input.setSelection(current.length());
		}
		int padding = (int) (20 * context.getResources().getDisplayMetrics().density);
		input.setPadding(padding, padding, padding, padding);
		AlertDialog dialog = Dialogs.builder(context)
						.setTitle(title)
						.setView(input)
						.setNegativeButton(R.string.cancel, null)
						.setPositiveButton(R.string.action_done, (d, which) -> {
							String name = input.getText().toString();
							if (!name.isBlank()) done.accept(name);
						})
						.create();
		if (dialog.getWindow() != null) {
			dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
		}
		dialog.show();
		input.requestFocus();
	}

	/** Rename or delete, for the playlist row's menu. */
	public static void options(@NonNull Context context, @NonNull LocalPlaylists.Playlist playlist) {
		LocalPlaylists store = LocalPlaylists.of(context);
		String name = playlist.name(context);
		Dialogs.builder(context)
						.setTitle(name)
						.setItems(new String[]{context.getString(R.string.playlist_rename), context.getString(R.string.playlist_delete)},
										(dialog, which) -> {
											if (which == 0) {
												askName(context, R.string.playlist_rename, name, newName -> store.rename(playlist.id(), newName));
											} else {
												Dialogs.builder(context)
																.setMessage(context.getString(R.string.playlist_delete_confirm, name))
																.setNegativeButton(R.string.cancel, null)
																.setPositiveButton(R.string.action_delete, (d, w) -> store.delete(playlist.id()))
																.show();
											}
										})
						.show();
	}

	@NonNull
	private static LocalPlaylists.Entry entry(@NonNull MediaItemMenuPayload video) {
		return new LocalPlaylists.Entry(video.videoId(), video.title(), video.author(), video.channelUrl(), video.thumbnailUrl());
	}
}
