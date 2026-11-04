package com.testtube.app.extractor;

import android.content.Context;
import android.content.pm.PackageManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tencent.mmkv.MMKV;

import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager;

/**
 * Keeps what the extractor pulls out of YouTube's player file (the signature and n-parameter
 * functions) between launches. Without this, the first video after every cold start downloads a
 * few megabytes of JavaScript and regexes through it before anything plays. The extractor ties
 * each value to the player's URL, so a new player version still gets extracted fresh.
 * <p>
 * Everything is thrown out when the app is installed or updated: a newer extractor may pull out
 * the functions differently, and a broken one from an old version would stick around for good.
 */
public final class PlayerFunctionStore implements YoutubeJavaScriptPlayerManager.PersistentCache {
	private static final String STORE_ID = "testtube_player_functions";
	private static final String KEY_INSTALLED = "installed";

	@NonNull
	private final MMKV store = MMKV.mmkvWithID(STORE_ID);

	public PlayerFunctionStore(@NonNull Context context) {
		long installed = installed(context);
		if (store.decodeLong(KEY_INSTALLED, Long.MIN_VALUE) != installed) {
			store.clearAll();
			store.encode(KEY_INSTALLED, installed);
		}
	}

	@Nullable
	@Override
	public String read(@NonNull String key) {
		return store.decodeString(key, null);
	}

	@Override
	public void write(@NonNull String key, @NonNull String value) {
		store.encode(key, value);
	}

	/** When this build was installed; changes with every update and reinstall. */
	private static long installed(@NonNull Context context) {
		try {
			return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).lastUpdateTime;
		} catch (PackageManager.NameNotFoundException e) {
			// Can't happen for our own package. Worst case the store isn't reset on updates
			return 0L;
		}
	}
}
