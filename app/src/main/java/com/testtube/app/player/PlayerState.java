package com.testtube.app.player;

import androidx.annotation.Nullable;

/**
 * State snapshot for the player surface.
 */
public record PlayerState(@Nullable String videoId, boolean miniPlayer) {
}
