package com.testtube.app.extractor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * A suggested video together with the channel that uploaded it. The display fields are null or
 * -1 for entries cached by older versions.
 */
public record RelatedVideo(@NonNull String id,
                           @Nullable String uploaderName,
                           @Nullable String uploaderUrl,
                           @Nullable String title,
                           long durationSeconds,
                           long viewCount,
                           @Nullable String published) {
}
