package com.testtube.app.ui;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tencent.mmkv.MMKV;
import com.testtube.app.Constant;
import com.testtube.app.R;

/**
 * First-run Google sign-in overlay. A plain WebView (no content blockers, those would break
 * Google sign-in) loads the YouTube account login page; once the user lands on YouTube, or a
 * Google session cookie exists, the overlay closes and the main app starts. Cookies are shared
 * with the app's other WebViews through the default CookieManager.
 */
public final class LoginController {
	private static final String STORE_ID = "testtube_login";
	private static final String KEY_DONE = "done";
	private static final String LOGIN_URL =
					"https://accounts.google.com/ServiceLogin?service=youtube&passive=true"
									+ "&continue=https%3A%2F%2Fm.youtube.com%2F";

	@NonNull
	private final Activity activity;
	@NonNull
	private final MMKV store;
	@Nullable
	private WebView webView;
	@Nullable
	private ViewGroup overlay;
	@Nullable
	private Runnable onCancel;

	public LoginController(@NonNull Activity activity) {
		this.activity = activity;
		this.store = MMKV.mmkvWithID(STORE_ID);
	}

	/**
	 * True when the user has already signed in, or a Google session cookie is present.
	 */
	public boolean isSignedIn() {
		if (store.decodeBool(KEY_DONE, false)) return true;
		String cookies = CookieManager.getInstance().getCookie("https://accounts.google.com");
		if (cookies != null && (cookies.contains("SID=") || cookies.contains("HSID="))) {
			store.encode(KEY_DONE, true);
			return true;
		}
		return false;
	}

	/**
	 * Shows the login overlay and calls back once sign-in completes.
	 */
	@SuppressLint("SetJavaScriptEnabled")
	public void show(@NonNull Runnable onComplete) {
		show(onComplete, null);
	}

	/**
	 * @param onCancel called when the user backs out of the sign-in without finishing it
	 */
	@SuppressLint("SetJavaScriptEnabled")
	public void show(@NonNull Runnable onComplete, @Nullable Runnable onCancel) {
		this.onCancel = onCancel;
		ViewGroup container = activity.findViewById(R.id.login_container);
		if (container == null) {
			onComplete.run();
			return;
		}
		View view = activity.getLayoutInflater().inflate(R.layout.view_login, container, false);
		container.addView(view);
		container.setVisibility(View.VISIBLE);
		overlay = container;

		CookieManager cookies = CookieManager.getInstance();
		cookies.setAcceptCookie(true);

		webView = view.findViewById(R.id.login_webview);
		WebSettings settings = webView.getSettings();
		settings.setJavaScriptEnabled(true);
		settings.setDomStorageEnabled(true);
		settings.setUserAgentString(Constant.USER_AGENT);
		settings.setMediaPlaybackRequiresUserGesture(false);
		cookies.setAcceptThirdPartyCookies(webView, true);
		webView.setWebViewClient(new WebViewClient() {
			@Override
			public void onPageFinished(@NonNull WebView view, @NonNull String url) {
				if (isYoutubeUrl(url)) complete(container, onComplete);
			}

			@Override
			public boolean shouldOverrideUrlLoading(@NonNull WebView view, @NonNull String url) {
				if (isYoutubeUrl(url)) {
					complete(container, onComplete);
					return true;
				}
				return false;
			}
		});
		webView.loadUrl(LOGIN_URL);
	}

	private void complete(@NonNull ViewGroup container, @NonNull Runnable onComplete) {
		if (webView == null) return;
		store.encode(KEY_DONE, true);
		WebView view = webView;
		webView = null;
		view.stopLoading();
		view.destroy();
		container.removeAllViews();
		container.setVisibility(View.GONE);
		onComplete.run();
	}

	/**
	 * Back pressed while the login overlay is up: navigate the login WebView first.
	 *
	 * @return true when the back press was consumed
	 */
	public boolean handleBack() {
		WebView view = webView;
		if (view == null) return false;
		if (view.canGoBack()) {
			view.goBack();
			return true;
		}
		// Signing in is optional: leaving the first page closes the overlay.
		ViewGroup container = overlay;
		Runnable cancel = onCancel;
		webView = null;
		overlay = null;
		onCancel = null;
		view.stopLoading();
		view.destroy();
		if (container != null) {
			container.removeAllViews();
			container.setVisibility(View.GONE);
		}
		if (cancel != null) cancel.run();
		return true;
	}

	public boolean isShowing() {
		return webView != null;
	}

	private static boolean isYoutubeUrl(@NonNull String url) {
		try {
			String host = Uri.parse(url).getHost();
			return host != null
							&& (host.equals(Constant.YOUTUBE_MOBILE_HOST)
							|| host.equals("www." + Constant.YOUTUBE_DOMAIN)
							|| host.equals(Constant.YOUTUBE_DOMAIN));
		} catch (RuntimeException ignored) {
			return false;
		}
	}
}
