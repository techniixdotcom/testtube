package com.testtube.app.filter;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * A blocked channel, matched by display name and, when known, its URL paths ("/@handle" or
 * "/channel/UC...").
 */
public record BlockedChannel(@NonNull String name, @Nullable String path, @Nullable String altPath, long blockedAt) {
}
