package com.testtube.app.external;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.testtube.app.extractor.VideoDetails;

/**
 * A Facebook or SoundCloud item (TestTube+) that plays like a YouTube video: minimized,
 * floating, background, queue.
 * @param id         prefixed id ("fb:…", "sc:…"), can't be mistaken for a YouTube id
 * @param pageUrl    link it came from, what the queue and Back return to
 * @param streamUrl  file or HLS playlist; signed links expire, so it's re-read each time
 * @param hls        {@code streamUrl} is an .m3u8
 * @param durationMs 0 if unknown
 * @param userAgent  for stream requests
 * @param artwork    cover bytes, shown when there's only sound (SoundCloud); null for videos
 */
public record ExternalVideo(@NonNull String id,
                            @NonNull String pageUrl,
                            @NonNull String streamUrl,
                            boolean hls,
                            @Nullable String title,
                            @Nullable String author,
                            @Nullable String thumbnailUrl,
                            @Nullable String description,
                            long durationMs,
                            @NonNull String userAgent,
                            @Nullable byte[] artwork) {
	/**
	 * For the watch screen and notification. No views, likes, date or channel link, those are
	 * YouTube things.
	 */
	@NonNull
	public VideoDetails toDetails() {
		return new VideoDetails(id, title, author, description, durationMs > 0L ? durationMs / 1000L : 0L,
						thumbnailUrl, 0L, null, null, null, -1L);
	}
}
