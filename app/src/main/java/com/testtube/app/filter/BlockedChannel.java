package com.testtube.app.filter;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * A channel whose videos are hidden. A channel is identified by its display name and, when
 * known, by its URL paths ("/@handle" as shown in pages and "/channel/UC..." from the
 * extractor).
 */
public record BlockedChannel(@NonNull String name, @Nullable String path, @Nullable String altPath, long blockedAt) {
}
