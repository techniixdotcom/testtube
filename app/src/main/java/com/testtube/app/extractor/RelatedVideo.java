package com.testtube.app.extractor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * A suggested video and its channel. Display fields may be null/-1 in entries cached by old
 * versions; the uploader image is null there too.
 */
public record RelatedVideo(@NonNull String id,
                           @Nullable String uploaderName,
                           @Nullable String uploaderUrl,
                           @Nullable String title,
                           long durationSeconds,
                           long viewCount,
                           @Nullable String published,
                           @Nullable String uploaderAvatarUrl) {
}
