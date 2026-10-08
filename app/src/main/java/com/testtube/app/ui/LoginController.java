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
 * Google sign-in overlay. Plain WebView on purpose (content blockers break Google sign-in).
 * Closes once we land on YouTube or a Google session cookie exists. Cookies are shared with the
 * other WebViews through the default CookieManager.
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

	public boolean isSignedIn() {
		if (store.decodeBool(KEY_DONE, false)) return true;
		String cookies = CookieManager.getInstance().getCookie("https://accounts.google.com");
		if (cookies != null && (cookies.contains("SID=") || cookies.contains("HSID="))) {
			store.encode(KEY_DONE, true);
			return true;
		}
		return false;
	}

	/** Signs out by removing the account cookies (shared by all WebViews). */
	public void signOut() {
		store.encode(KEY_DONE, false);
		CookieManager cookies = CookieManager.getInstance();
		cookies.removeAllCookies(null);
		cookies.flush();
	}

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
		settings.setAllowFileAccess(false);
		settings.setAllowContentAccess(false);
		settings.setGeolocationEnabled(false);
		settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
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
				// https only for sign-in pages; intent:, file:, javascript: and plain http are refused
				return !url.regionMatches(true, 0, "https://", 0, 8);
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
	 * Back while the overlay is up goes back in the WebView first.
	 *
	 * @return true if consumed
	 */
	public boolean handleBack() {
		WebView view = webView;
		if (view == null) return false;
		if (view.canGoBack()) {
			view.goBack();
			return true;
		}
		// sign-in is optional, backing out of the first page just closes the overlay
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
