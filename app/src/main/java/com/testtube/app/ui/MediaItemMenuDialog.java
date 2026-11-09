package com.testtube.app.ui;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.media3.common.util.UnstableApi;

import com.testtube.app.AppSettings;
import com.testtube.app.R;
import com.testtube.app.downloader.ui.DownloadDialog;
import com.testtube.app.extractor.YoutubeExtractor;
import com.testtube.app.filter.ContentFilters;
import com.testtube.app.nav.MediaItemMenuPayload;
import com.testtube.app.player.TestTubePlayer;
import com.testtube.app.player.queue.QueueItem;
import com.testtube.app.player.queue.QueueRepository;
import com.testtube.app.playlist.LocalPlaylists;
import com.testtube.app.playlist.PlaylistDialogs;
import com.testtube.app.util.ImageUtils;
import com.testtube.app.util.ToastUtils;
import com.testtube.app.util.ViewUtils;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@UnstableApi
public final class MediaItemMenuDialog {
	private static final float MENU_ICON_DP = 24f;
	private static final int[] MENU_ROWS = {R.id.action_queue, R.id.action_play_next, R.id.action_save, R.id.action_share,
					R.id.action_download, R.id.action_toggle_watched, R.id.action_block_channel};
	@NonNull
	private final Context context;
	@NonNull
	private final MediaItemMenuPayload item;
	@NonNull
	private final YoutubeExtractor extractor;
	@NonNull
	private final QueueRepository queue;
	@NonNull
	private final TestTubePlayer player;
	@NonNull
	private final ContentFilters filters;
	@NonNull
	private final Runnable onFiltersChanged;

	public MediaItemMenuDialog(@NonNull Context context,
	                           @NonNull MediaItemMenuPayload item,
	                           @NonNull YoutubeExtractor extractor,
	                           @NonNull QueueRepository queue,
	                           @NonNull TestTubePlayer player,
	                           @NonNull ContentFilters filters,
	                           @NonNull Runnable onFiltersChanged) {
		this.context = context;
		// a local playlist link means nothing outside this app (share, download, queue)
		this.item = new MediaItemMenuPayload(item.videoId(), LocalPlaylists.withoutLocalList(item.videoUrl(), item.videoId()),
						item.title(), item.author(), item.thumbnailUrl(), item.channelUrl());
		this.extractor = extractor;
		this.queue = queue;
		this.player = player;
		this.filters = filters;
		this.onFiltersChanged = onFiltersChanged;
	}

	public void show() {
		View view = LayoutInflater.from(context).inflate(R.layout.dialog_media_item_menu, null, false);
		AlertDialog dialog = Dialogs.builder(context, R.style.ThemeOverlay_TestTube_AlertDialog_Page)
						.setView(view)
						.create();
		ImageView thumb = view.findViewById(R.id.media_item_thumb);
		TextView title = view.findViewById(R.id.media_item_title);
		TextView author = view.findViewById(R.id.media_item_author);
		String thumbUrl = item.thumbnailUrl();

		title.setText(item.title());
		author.setText(item.author());
		author.setVisibility(item.author() != null && !item.author().isBlank() ? View.VISIBLE : View.GONE);

		ImageUtils.load(thumb, thumbUrl, R.drawable.ic_thumbnail_placeholder);
		// icons come in two sizes, every row shows one. Text colour from the style unless the theme has
		// its own (pink in NiiX).
		int iconRes = AppSettings.theme().menuIcon;
		ColorStateList iconColor = iconRes != 0 ? ColorStateList.valueOf(ContextCompat.getColor(context, iconRes)) : null;
		for (int id : MENU_ROWS) {
			TextView row = view.findViewById(id);
			ViewUtils.sizeCompoundDrawables(row, MENU_ICON_DP);
			if (iconColor != null) row.setCompoundDrawableTintList(iconColor);
		}

		view.findViewById(R.id.media_item_close).setOnClickListener(v -> dialog.dismiss());
		view.findViewById(R.id.action_queue).setOnClickListener(v -> {
			addToQueue();
			dialog.dismiss();
		});
		view.findViewById(R.id.action_play_next).setOnClickListener(v -> {
			dialog.dismiss();
			playNext();
		});
		view.findViewById(R.id.action_save).setOnClickListener(v -> {
			dialog.dismiss();
			PlaylistDialogs.save(context, item);
		});
		view.findViewById(R.id.action_share).setOnClickListener(v -> {
			share();
			dialog.dismiss();
		});
		view.findViewById(R.id.action_download).setOnClickListener(v -> {
			dialog.dismiss();
			new DownloadDialog(item.videoUrl(), context, extractor).show();
		});

		boolean watched = filters.isWatched(item.videoId());
		TextView watchedButton = view.findViewById(R.id.action_toggle_watched);
		watchedButton.setText(watched ? R.string.mark_as_unwatched : R.string.mark_as_watched);
		watchedButton.setOnClickListener(v -> {
			if (watched) {
				filters.markUnwatched(item.videoId());
				ToastUtils.show(context, R.string.marked_as_unwatched);
			} else {
				filters.markWatched(item.videoId());
				ToastUtils.show(context, R.string.marked_as_watched);
			}
			onFiltersChanged.run();
			dialog.dismiss();
		});

		TextView blockButton = view.findViewById(R.id.action_block_channel);
		String pageChannel = item.author() == null ? null : item.author().split("[•·|]", 2)[0].trim();
		if (pageChannel != null && !pageChannel.isEmpty()) {
			blockButton.setText(context.getString(R.string.block_channel_named, pageChannel));
		}
		blockButton.setOnClickListener(v -> {
			dialog.dismiss();
			resolveChannelAndConfirm(pageChannel);
		});

		dialog.show();
	}

	/** Looks up the video's channel (lists don't always have it), then asks to confirm. */
	private void resolveChannelAndConfirm(@Nullable String pageChannel) {
		ToastUtils.show(context, R.string.looking_up_channel);
		Handler main = new Handler(Looper.getMainLooper());
		AtomicBoolean handled = new AtomicBoolean(false);
		// lookup too slow: use the name on the page
		main.postDelayed(() -> {
			if (!handled.compareAndSet(false, true)) return;
			if (pageChannel != null && !pageChannel.isEmpty()) {
				confirmBlock(pageChannel, null);
			} else {
				ToastUtils.show(context, R.string.channel_lookup_failed);
			}
		}, TimeUnit.SECONDS.toMillis(20));
		extractor.getInfo(item.videoUrl(), null)
						.whenComplete((details, error) -> main.post(() -> {
							if (!handled.compareAndSet(false, true)) return;
							String name = null;
							String uploaderUrl = null;
							if (error == null && details != null && details.video() != null) {
								name = details.video().getAuthor();
								uploaderUrl = details.video().getUploaderUrl();
							}
							if (name == null || name.isBlank()) {
								name = pageChannel;
							}
							if (name == null || name.isBlank()) {
								ToastUtils.show(context, R.string.channel_lookup_failed);
								return;
							}
							confirmBlock(name.trim(), uploaderUrl);
						}));
	}

	private void confirmBlock(@NonNull String channel, @Nullable String uploaderUrl) {
		Dialogs.builder(context)
						.setTitle(R.string.block_channel)
						.setMessage(context.getString(R.string.block_channel_confirm, channel))
						.setPositiveButton(R.string.block, (d, w) -> {
							if (filters.blockChannel(channel, item.channelUrl(), uploaderUrl)) {
								ToastUtils.show(context, context.getString(R.string.channel_blocked, channel));
								onFiltersChanged.run();
							}
						})
						.setNegativeButton(R.string.cancel, null)
						.show();
	}

	private void playNext() {
		QueueItem queueItem = item.toQueueItem();
		String videoId = queueItem.getVideoId();
		if (queueItem.getVideoUrl() == null || videoId == null || videoId.isBlank()
						|| queueItem.getTitle() == null || queueItem.getTitle().isBlank()) {
			ToastUtils.show(context, R.string.queue_item_unavailable);
			return;
		}
		if (!queue.addNext(queueItem, player.getVideoId())) {
			ToastUtils.show(context, R.string.queue_item_already_playing);
			return;
		}
		player.refreshQueueNav();
		ToastUtils.show(context, R.string.queue_item_play_next);
	}

	private void addToQueue() {
		// reject queue items missing basic metadata
		QueueItem queueItem = item.toQueueItem();
		String videoId = queueItem.getVideoId();
		if (queueItem.getVideoUrl() == null || videoId == null || videoId.isBlank()
						|| queueItem.getTitle() == null || queueItem.getTitle().isBlank()) {
			ToastUtils.show(context, R.string.queue_item_unavailable);
			return;
		}
		queue.add(queueItem);
		player.refreshQueueNav();
		ToastUtils.show(context, R.string.queue_item_added);
	}

	private void share() {
		Intent send = new Intent(Intent.ACTION_SEND);
		send.putExtra(Intent.EXTRA_TEXT, item.videoUrl());
		send.setType("text/plain");
		context.startActivity(Intent.createChooser(send, context.getString(R.string.share)));
	}
}
