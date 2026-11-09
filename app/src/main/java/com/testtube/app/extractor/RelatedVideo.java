package com.testtube.app.extractor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * A suggested video and its channel. Old cache entries may have null/-1 display fields and no
 * avatar.
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
