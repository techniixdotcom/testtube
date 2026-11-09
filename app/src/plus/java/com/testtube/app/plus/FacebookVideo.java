package com.testtube.app.plus;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Public Facebook video: one MP4 with picture and sound from Facebook's servers. The file link is
 * signed and expires, so it's fetched at play time.
 * @param pageUrl    link that was opened
 * @param durationMs 0 if the page doesn't say
 */
record FacebookVideo(@NonNull String pageUrl,
                     @NonNull String streamUrl,
                     @Nullable String title,
                     @Nullable String author,
                     @Nullable String thumbnailUrl,
                     @Nullable String description,
                     long durationMs) {
}
