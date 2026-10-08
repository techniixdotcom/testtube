package com.testtube.app.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.media3.common.util.UnstableApi;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.squareup.picasso.Picasso;
import com.testtube.app.R;
import com.testtube.app.downloader.ui.DownloadDialog;
import com.testtube.app.extractor.YoutubeExtractor;
import com.testtube.app.filter.ContentFilters;
import com.testtube.app.nav.MediaItemMenuPayload;
import com.testtube.app.player.TestTubePlayer;
import com.testtube.app.player.queue.QueueItem;
import com.testtube.app.player.queue.QueueRepository;
import com.testtube.app.util.ToastUtils;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@UnstableApi
public final class MediaItemMenuDialog {
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
		this.item = item;
		this.extractor = extractor;
		this.queue = queue;
		this.player = player;
		this.filters = filters;
		this.onFiltersChanged = onFiltersChanged;
	}

	public void show() {
		View view = LayoutInflater.from(context).inflate(R.layout.dialog_media_item_menu, null, false);
		AlertDialog dialog = new MaterialAlertDialogBuilder(context)
						.setView(view)
						.create();
		ImageView thumb = view.findViewById(R.id.media_item_thumb);
		TextView title = view.findViewById(R.id.media_item_title);
		TextView author = view.findViewById(R.id.media_item_author);
		String thumbUrl = item.thumbnailUrl();

		title.setText(item.title());
		author.setText(item.author());
		author.setVisibility(item.author() != null && !item.author().isBlank() ? View.VISIBLE : View.GONE);

		Picasso.get()
						.load(thumbUrl)
						.placeholder(R.drawable.ic_thumbnail_placeholder)
						.error(R.drawable.ic_thumbnail_placeholder)
						.into(thumb);

		view.findViewById(R.id.media_item_close).setOnClickListener(v -> dialog.dismiss());
		view.findViewById(R.id.action_queue).setOnClickListener(v -> {
			addToQueue();
			dialog.dismiss();
		});
		view.findViewById(R.id.action_play_next).setOnClickListener(v -> {
			dialog.dismiss();
			playNext();
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
		MaterialButton watchedButton = view.findViewById(R.id.action_toggle_watched);
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

		MaterialButton blockButton = view.findViewById(R.id.action_block_channel);
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

	/**
	 * Looks the channel up from the video itself (pages don't always show it), then asks to confirm.
	 */
	private void resolveChannelAndConfirm(@Nullable String pageChannel) {
		ToastUtils.show(context, R.string.looking_up_channel);
		Handler main = new Handler(Looper.getMainLooper());
		AtomicBoolean handled = new AtomicBoolean(false);
		// use the name from the page if the lookup takes too long
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
		new MaterialAlertDialogBuilder(context)
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
		if (!queue.isEnabled()) {
			queue.setEnabled(true);
		}
		if (!queue.addNext(queueItem, player.getVideoId())) {
			ToastUtils.show(context, R.string.queue_item_already_playing);
			return;
		}
		player.refreshQueueNav();
		ToastUtils.show(context, R.string.queue_item_play_next);
	}

	private void addToQueue() {
		QueueItem queueItem = item.toQueueItem();
		String videoId = queueItem.getVideoId();
		if (queueItem.getVideoUrl() == null || videoId == null || videoId.isBlank()
						|| queueItem.getTitle() == null || queueItem.getTitle().isBlank()) {
			ToastUtils.show(context, R.string.queue_item_unavailable);
			return;
		}
		if (!queue.isEnabled()) {
			queue.setEnabled(true);
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
