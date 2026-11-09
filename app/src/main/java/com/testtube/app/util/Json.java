package com.testtube.app.util;

import com.google.gson.Gson;

/** One Gson for the app; thread-safe and caches type adapters. */
public final class Json {
	public static final Gson GSON = new Gson();

	private Json() {
	}
}
