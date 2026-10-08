package com.testtube.app.nav;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.testtube.app.player.queue.QueueItem;

public record MediaItemMenuPayload(@NonNull String videoId, @NonNull String videoUrl,
                                   @NonNull String title, @Nullable String author,
                                   @Nullable String thumbnailUrl, @Nullable String channelUrl) {

	@NonNull
	public QueueItem toQueueItem() {
		return new QueueItem(videoId, videoUrl, title, author, thumbnailUrl);
	}
}
