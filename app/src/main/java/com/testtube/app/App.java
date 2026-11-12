package com.testtube.app;

import android.app.Application;
import android.content.pm.PackageInfo;
import android.content.res.Configuration;
import android.os.Build;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.media3.common.util.UnstableApi;
import androidx.webkit.WebViewCompat;

import com.tencent.mmkv.MMKV;
import com.testtube.app.extractor.ContentLanguage;
import com.testtube.app.util.CrashLog;
import com.testtube.app.util.Timing;
import com.testtube.app.util.UserAgents;

import org.schabi.newpipe.extractor.utils.NativeJavaScript;
import org.schabi.newpipe.extractor.utils.NativeRegex;

@UnstableApi
public class App extends Application {
	private static final String KEY_WEBVIEW_CHROME = "webview_chrome_major";
	private AppGraph graph;

	@Override
	public void onCreate() {
		super.onCreate();
		Timing.init(this);
		CrashLog.install(this);
		MMKV.initialize(this);
		// Before any screen exists, so the first one gets the right colours
		AppCompatDelegate.setDefaultNightMode(AppSettings.theme().nightMode);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
			String processName = getProcessName();
			if (!getPackageName().equals(processName)) {
				WebView.setDataDirectorySuffix(processName);
			}
		}
		ContentLanguage.apply(this);
		graph = new AppGraph(this);
		registerActivityLifecycleCallbacks(graph.screenTracker());
		// Wipe last run's cache before anything opens it
		graph.wipeCacheOnStart();
		initUserAgent();
		// Every YouTube request reads its cookies from WebView's CookieManager, and its first use
		// starts the WebView engine (a few hundred ms on many phones). Start it now, beside drawing
		// the first screen, instead of in front of Home's first request.
		graph.executor().execute(() -> {
			try {
				CookieManager.getInstance();
			} catch (RuntimeException missingOrUpdating) {
				// requests fall back to no cookies, as before
			}
		});
	}

	/**
	 * In the background and Android wants memory back: drop the player JavaScript QuickJS keeps
	 * evaluated (about 30 MB) and the texts kept for native regex searches. The next video loads
	 * them again, a fraction of a second.
	 */
	@Override
	@SuppressWarnings("deprecation") // TRIM_MEMORY_BACKGROUND is still delivered
	public void onTrimMemory(int level) {
		super.onTrimMemory(level);
		if (level >= TRIM_MEMORY_BACKGROUND && graph != null) {
			// off the main thread: an extraction may be holding the engine
			graph.executor().execute(() -> {
				NativeJavaScript.clear();
				NativeRegex.clear(); // the player file (a few MB) kept for regex searches
			});
		}
	}

	@Override
	public void onConfigurationChanged(@NonNull Configuration newConfig) {
		super.onConfigurationChanged(newConfig);
		ContentLanguage.apply(this);
	}

	/**
	 * User agent uses the WebView's Chrome version. Reading it starts the WebView, so it's
	 * cached per WebView version and refreshed in the background after an update.
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
				// WebView can be missing or mid-update
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
		Constant.DESKTOP_USER_AGENT = UserAgents.desktop(major);
	}

	@NonNull
	AppGraph graph() {
		return graph;
	}
}
