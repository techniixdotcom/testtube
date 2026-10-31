package com.testtube.app.util;

import com.google.gson.Gson;

/** One Gson for the whole app; it's thread-safe and keeps what it learned about each type. */
public final class Json {
	public static final Gson GSON = new Gson();

	private Json() {
	}
}
