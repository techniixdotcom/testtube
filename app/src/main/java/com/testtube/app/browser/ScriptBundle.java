package com.testtube.app.browser;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.testtube.app.util.StreamIOUtils;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Page scripts and styles, read from the assets once per process and joined into a single
 * script so each injection costs one WebView call instead of one per file.
 */
public final class ScriptBundle {
	private static final String TAG = "ScriptBundle";
	private static final String INIT_SCRIPT = "init.js";
	@Nullable
	private static volatile ScriptBundle instance;

	@NonNull
	public final String pageScript;
	@Nullable
	public final String bridgeShim;

	private ScriptBundle(@NonNull String pageScript, @Nullable String bridgeShim) {
		this.pageScript = pageScript;
		this.bridgeShim = bridgeShim;
	}

	@NonNull
	public static ScriptBundle get(@NonNull Context context) {
		ScriptBundle bundle = instance;
		if (bundle != null) return bundle;
		synchronized (ScriptBundle.class) {
			bundle = instance;
			if (bundle == null) {
				AssetManager assets = context.getApplicationContext().getAssets();
				bundle = new ScriptBundle(buildPageScript(assets), read(assets, "bridge/shim.js"));
				instance = bundle;
			}
			return bundle;
		}
	}

	@NonNull
	private static String buildPageScript(@NonNull AssetManager assets) {
		StringBuilder out = new StringBuilder(160 * 1024);
		for (String name : list(assets, "style")) {
			if (!name.endsWith(".css")) continue;
			String css = read(assets, "style/" + name);
			if (css == null) continue;
			String id = "testtube-css-" + name.substring(0, name.length() - 4);
			append(out, "(function(){if(document.getElementById('" + id + "'))return;"
							+ "var s=document.createElement('style');s.id='" + id + "';s.textContent="
							+ jsString(css) + ";var t=document.head||document.documentElement;"
							+ "if(t)t.appendChild(s);})();");
		}
		List<String> scripts = list(assets, "script");
		if (scripts.remove(INIT_SCRIPT)) scripts.add(0, INIT_SCRIPT);
		for (String name : scripts) {
			if (!name.endsWith(".js")) continue;
			String js = read(assets, "script/" + name);
			if (js != null) append(out, js);
		}
		return out.toString();
	}

	private static void append(@NonNull StringBuilder out, @NonNull String script) {
		// Each file stays isolated: one failing script cannot stop the others.
		out.append("try{\n").append(script).append("\n}catch(e){console.error(e);}\n");
	}

	@NonNull
	private static List<String> list(@NonNull AssetManager assets, @NonNull String dir) {
		try {
			String[] names = assets.list(dir);
			if (names == null) return new ArrayList<>();
			Arrays.sort(names);
			return new ArrayList<>(Arrays.asList(names));
		} catch (IOException e) {
			Log.e(TAG, "Unable to list " + dir, e);
			return new ArrayList<>();
		}
	}

	@Nullable
	private static String read(@NonNull AssetManager assets, @NonNull String path) {
		try {
			InputStream in = assets.open(path);
			return StreamIOUtils.readInputStream(in);
		} catch (IOException e) {
			Log.e(TAG, "Unable to read " + path, e);
			return null;
		}
	}

	@NonNull
	static String jsString(@NonNull String value) {
		StringBuilder out = new StringBuilder(value.length() + 16).append('"');
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			switch (c) {
				case '"' -> out.append("\\\"");
				case '\\' -> out.append("\\\\");
				case '\n' -> out.append("\\n");
				case '\r' -> out.append("\\r");
				case '\t' -> out.append("\\t");
				case '<' -> out.append("\\u003c");
				case '\u2028' -> out.append("\\u2028");
				case '\u2029' -> out.append("\\u2029");
				default -> {
					if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
					else out.append(c);
				}
			}
		}
		return out.append('"').toString();
	}
}
