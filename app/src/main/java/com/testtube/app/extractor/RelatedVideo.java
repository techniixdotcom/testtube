package com.testtube.app.extractor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Suggested video plus its channel. Display fields are null/-1 for entries cached by older versions.
 */
public record RelatedVideo(@NonNull String id,
                           @Nullable String uploaderName,
                           @Nullable String uploaderUrl,
                           @Nullable String title,
                           long durationSeconds,
                           long viewCount,
                           @Nullable String published) {
}
