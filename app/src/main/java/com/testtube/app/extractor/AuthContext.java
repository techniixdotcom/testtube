package com.testtube.app.extractor;

import androidx.annotation.Nullable;

/** Cookies and page details a YouTube request is signed and identified with. */
public record AuthContext(
				@Nullable String cookies,
				@Nullable String visitorData,
				@Nullable String dataSyncId,
				@Nullable String clientVersion,
				@Nullable String sessionIndex,
				boolean loggedIn) {
}
