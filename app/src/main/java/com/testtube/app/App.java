package com.testtube.app;

import android.app.Application;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.webkit.WebSettings;
import android.webkit.WebView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;
import androidx.webkit.WebViewCompat;

import com.tencent.mmkv.MMKV;
import com.testtube.app.util.UserAgents;

@UnstableApi
public class App extends Application {
	private static final String KEY_WEBVIEW_CHROME = "webview_chrome_major";
	private AppGraph graph;

	@Override
	public void onCreate() {
		super.onCreate();
		MMKV.initialize(this);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
			String processName = getProcessName();
			if (!getPackageName().equals(processName)) {
				WebView.setDataDirectorySuffix(processName);
			}
		}
		graph = new AppGraph(this);
		initUserAgent();
	}

	/**
	 * The user agent follows the Chrome version of the phone's WebView. Reading it starts the
	 * whole WebView engine, so the result is cached per WebView version and only read again, in
	 * the background, after the WebView was updated.
	 */
	private void initUserAgent() {
		MMKV store = MMKV.defaultMMKV();
		String webViewVersion = webViewVersion();
		String cached = store.decodeString(KEY_WEBVIEW_CHROME, null);
		String cachedMajor = null;
		if (cached != null) {
			int split = cached.indexOf('|');
			if (split > 0) {
				cachedMajor = cached.substring(split + 1);
				applyChromeMajor(cachedMajor);
				if (webViewVersion != null && webViewVersion.equals(cached.substring(0, split))) return;
			}
		}
		String previousMajor = cachedMajor;
		Thread thread = new Thread(() -> {
			String userAgent;
			try {
				userAgent = WebSettings.getDefaultUserAgent(this);
			} catch (RuntimeException e) {
				// The WebView provider can be missing or updating.
				return;
			}
			String major = UserAgents.chromeMajor(userAgent);
			store.encode(KEY_WEBVIEW_CHROME, (webViewVersion != null ? webViewVersion : "?") + "|" + major);
			if (!major.equals(previousMajor)) applyChromeMajor(major);
		}, "testtube-user-agent");
		thread.setDaemon(true);
		thread.start();
	}

	@Nullable
	private String webViewVersion() {
		try {
			PackageInfo info = WebViewCompat.getCurrentWebViewPackage(this);
			if (info == null) return null;
			@SuppressWarnings("deprecation")
			long code = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? info.getLongVersionCode() : info.versionCode;
			return info.packageName + ":" + code;
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static void applyChromeMajor(@NonNull String major) {
		Constant.CHROME_MAJOR = major;
		Constant.USER_AGENT = UserAgents.mobile(major);
	}

	@NonNull
	AppGraph graph() {
		return graph;
	}
}
