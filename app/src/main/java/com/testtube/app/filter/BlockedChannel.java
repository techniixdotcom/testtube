package com.testtube.app.filter;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * A blocked channel, matched by display name and, if known, its URL paths
 * ("/@handle" from pages, "/channel/UC..." from the extractor).
 */
public record BlockedChannel(@NonNull String name, @Nullable String path, @Nullable String altPath, long blockedAt) {
}
