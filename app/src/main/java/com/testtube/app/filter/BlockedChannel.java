package com.testtube.app.filter;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Blocked channel, matched by name and, when known, URL paths ("/@handle", "/channel/UC..."). */
public record BlockedChannel(@NonNull String name, @Nullable String path, @Nullable String altPath) {
}
