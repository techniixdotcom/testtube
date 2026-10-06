package com.testtube.app.browser;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.media3.common.util.UnstableApi;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.testtube.app.AppGraph;
import com.testtube.app.Constant;
import com.testtube.app.R;
import com.testtube.app.ui.ActivityGraph;
import com.testtube.app.ui.MainActivity;

import java.util.Objects;



/**
 * Fragment that hosts the YouTube WebView.
 */
@UnstableApi
public final class YoutubeFragment extends Fragment {

	private static final String ARG_URL = "url";
	private static final String ARG_TAG = "tag";


	@Nullable
	private String url;
	@Nullable
	private String tag;
	@Nullable
	private YoutubeWebview webView;

	@NonNull
	public static YoutubeFragment newInstance(@NonNull String url, @NonNull String tag) {
		YoutubeFragment fragment = new YoutubeFragment();
		Bundle args = new Bundle();
		args.putString(ARG_URL, url);
		args.putString(ARG_TAG, tag);
		fragment.setArguments(args);
		fragment.url = url;
		fragment.tag = tag;
		return fragment;
	}

	public void loadUrl(@Nullable String url) {
		this.url = url;
		YoutubeWebview webView = this.webView;
		if (webView != null && url != null && !Objects.equals(webView.getUrl(), url)) {
			webView.loadUrl(url);
		}
	}

	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		Bundle args = getArguments();
		if (args != null) {
			url = args.getString(ARG_URL);
			tag = args.getString(ARG_TAG);
		}
	}

	@NonNull
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
		View view = inflater.inflate(R.layout.fragment_webview, container, false);
		YoutubeWebview webView = view.findViewById(R.id.webview);
		this.webView = webView;
		SwipeRefreshLayout swipeRefreshLayout = view.findViewById(R.id.swipeRefreshLayout);

		swipeRefreshLayout.setColorSchemeResources(R.color.yt_red);
		swipeRefreshLayout.setOnRefreshListener(() -> webView.evaluateJavascript("window.dispatchEvent(new Event('onRefresh'));", value -> {
		}));
		swipeRefreshLayout.setProgressViewOffset(true, 86, 196);

		ActivityGraph graph = ((MainActivity) requireActivity()).graph();
		AppGraph app = graph.app;
		TabManager tabManager = graph.tabManager;
		webView.setYoutubeExtractor(app.youtubeExtractor());
		webView.setPlayer(graph.player);
		webView.setExtensionManager(app.extensionManager());
		webView.setTabManager(tabManager);
		webView.setQueueRepository(app.queueRepository());
		webView.setContentFilters(app.contentFilters());
		webView.setWatchHistory(app.watchHistory());
		webView.setOkHttpClient(app.okHttpClient(), app.webViewCachePolicy());
		webView.setPoTokenContextStore(app.poTokenContextStore());
		webView.setUpdateVisitedHistory(url -> {
			YoutubeFragment.this.url = url;
			tabManager.onUrlChanged(this, url);
		});
		webView.init();
		webView.setScriptActive(!isHidden());
		if (savedInstanceState != null) webView.restoreState(savedInstanceState);
		else if (url != null) loadUrl(url);

		return view;
	}

	@Override
	public void onResume() {
		super.onResume();
		YoutubeWebview webView = this.webView;
		if (webView == null || isHidden()) return;
		webView.setScriptActive(true);
		webView.syncPreferences();
		webView.onResume();
		webView.resumeTimers();
		webView.refreshPoTokenContext();
	}

	@Override
	public void onPause() {
		super.onPause();
		YoutubeWebview webView = this.webView;
		if (webView == null || isHidden()) return;
		if (Constant.PAGE_WATCH.equals(tag)) {
			return;
		}
		if (getActivity() != null && getActivity().isInPictureInPictureMode()) return;
		webView.setScriptActive(false);
		webView.onPause();
		webView.pauseTimers();
	}

	@Override
	public void onHiddenChanged(boolean hidden) {
		super.onHiddenChanged(hidden);
		YoutubeWebview webView = this.webView;
		if (webView == null) return;
		if (hidden) {
			if (Constant.PAGE_WATCH.equals(tag)) {
				return;
			}
			webView.setScriptActive(false);
			webView.onPause();
			webView.pauseTimers();
		} else {
			webView.setScriptActive(true);
			webView.syncPreferences();
			webView.onResume();
			webView.resumeTimers();
			webView.refreshPoTokenContext();
		}
	}

	@Override
	public void onDestroyView() {
		super.onDestroyView();
		YoutubeWebview webView = this.webView;
		if (webView == null) return;
		this.webView = null;
		webView.stopLoading();
		webView.clearHistory();
		webView.removeAllViews();
		webView.destroy();
	}

	@Override
	public void onSaveInstanceState(@NonNull Bundle outState) {
		super.onSaveInstanceState(outState);
		YoutubeWebview webView = this.webView;
		if (webView != null) webView.saveState(outState);
	}

	@Nullable
	public String getUrl() {
		return url;
	}

	@Nullable
	public String getTabTag() {
		return tag;
	}

	@Nullable
	public YoutubeWebview getWebView() {
		return webView;
	}

}
