package com.testtube.app.extractor;

import android.content.Context;
import android.content.pm.PackageManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tencent.mmkv.MMKV;

import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager;

/**
 * Keeps what the extractor pulls out of YouTube's player JS (sig and n functions) across
 * launches. Without it every cold start downloads a few MB of JS and regexes through it before
 * anything plays. Values are tied to the player URL, so a new player gets extracted fresh.
 * <p>
 * Wiped on install or update: a newer extractor may extract differently, and a broken value
 * from an old version would stick forever.
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

	/** Install time of this build, changes with every update. */
	private static long installed(@NonNull Context context) {
		try {
			return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).lastUpdateTime;
		} catch (PackageManager.NameNotFoundException e) {
			// can't happen for our own package; worst case no reset on update
			return 0L;
		}
	}
}
