package com.testtube.app.extension;

import com.tencent.mmkv.MMKV;

import java.util.Map;


/**
 * All preferences are hardcoded; this only exposes them to the
 * JavaScript bridge and tracks content filter changes.
 */
public class ExtensionManager {
	private static final String KEY_VERSION = "preferences:version";

	private final MMKV mmkv;

	public ExtensionManager(MMKV mmkv) {
		this.mmkv = mmkv;
	}

	public boolean isEnabled(String key) {
		return Boolean.TRUE.equals(Constant.DEFAULT_PREFERENCES.getOrDefault(key, false));
	}

	public Map<String, Boolean> getAllPreferences() {
		return Constant.DEFAULT_PREFERENCES;
	}

	/**
	 * Tells every page that content filter data (watched videos, blocked channels) changed.
	 */
	public void notifyChanged() {
		mmkv.encode(KEY_VERSION, version() + 1L);
	}

	public long version() {
		return mmkv.decodeLong(KEY_VERSION, 0L);
	}
}
