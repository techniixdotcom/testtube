package com.testtube.app.browser;

import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.net.Uri;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.util.Consumer;
import androidx.media3.common.util.UnstableApi;

import com.testtube.app.Constant;
import com.testtube.app.R;
import com.testtube.app.filter.ContentFilters;
import com.testtube.app.cache.WebViewCachePolicy;
import com.testtube.app.extension.ExtensionManager;
import com.testtube.app.extractor.YoutubeExtractor;
import com.testtube.app.extractor.potoken.PoTokenContextStore;
import com.testtube.app.extractor.potoken.PoTokenJsonUtils;
import com.testtube.app.extractor.potoken.PoTokenWebViewContext;
import com.testtube.app.player.TestTubePlayer;
import com.testtube.app.player.queue.QueueRepository;
import com.testtube.app.ui.MainActivity;
import com.testtube.app.ui.widget.LoadingProgressBar;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import com.testtube.app.util.ToastUtils;
import com.testtube.app.util.UrlUtils;
import com.testtube.app.util.ViewUtils;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

import okhttp3.OkHttpClient;
import okhttp3.Response;

/**
 * WebView wrapper that constrains navigation and injects page hooks.
 */
@UnstableApi
public class YoutubeWebview extends WebView {

	private static final String PO_TOKEN_CONTEXT_SCRIPT = """
					(function(){
					try{
					var ytcfgObject=globalThis.ytcfg||null;
					var ytcfgData=ytcfgObject&&ytcfgObject.data_?ytcfgObject.data_:null;
					var getCfg=function(key){
					try{
					if(ytcfgObject&&typeof ytcfgObject.get==='function'){
					var value=ytcfgObject.get(key);
					if(value!==undefined&&value!==null&&value!==''){return value;}
					}
					}catch(ignored){}
					return ytcfgData&&ytcfgData[key]!==undefined?ytcfgData[key]:null;
					};
					var initialDataContext=globalThis.ytInitialData&&globalThis.ytInitialData.responseContext?globalThis.ytInitialData.responseContext:null;
					var initialPlayerContext=globalThis.ytInitialPlayerResponse&&globalThis.ytInitialPlayerResponse.responseContext?globalThis.ytInitialPlayerResponse.responseContext:null;
					var innertubeContext=getCfg('INNERTUBE_CONTEXT')||initialDataContext||initialPlayerContext||null;
					var client=innertubeContext&&innertubeContext.client?innertubeContext.client:null;
					var initialData=globalThis.ytInitialData||null;
					var rawFlags=getCfg('EXPERIMENT_FLAGS')||getCfg('serializedExperimentFlags')||null;
					var serializedExperimentFlags=null;
					if(typeof rawFlags==='string'){serializedExperimentFlags=rawFlags;}
					else if(rawFlags&&typeof rawFlags==='object'){
					try{serializedExperimentFlags=Object.keys(rawFlags).map(function(key){return key+'='+rawFlags[key];}).join(',');}catch(ignored){}
					}
					var premium=false;
					try{
					var topbar=initialData&&initialData.topbar&&initialData.topbar.desktopTopbarRenderer?initialData.topbar.desktopTopbarRenderer:null;
					var logo=topbar&&topbar.logo&&topbar.logo.topbarLogoRenderer?topbar.logo.topbarLogoRenderer:null;
					var iconType=logo&&logo.iconImage?logo.iconImage.iconType:null;
					var tooltip=logo&&typeof logo.tooltipText==='string'?logo.tooltipText.toLowerCase():null;
					premium=!!(getCfg('IS_SUBSCRIBED_TO_PREMIUM')||getCfg('IS_PREMIUM_USER')||iconType==='YOUTUBE_PREMIUM_LOGO'||(tooltip&&tooltip.indexOf('premium')>=0));
					}catch(ignored){}
					return JSON.stringify({
					url:location.href,
					visitorData:getCfg('VISITOR_DATA')||(client?client.visitorData:null),
					dataSyncId:getCfg('DATASYNC_ID')||getCfg('DELEGATED_SESSION_ID')||null,
					clientVersion:getCfg('INNERTUBE_CLIENT_VERSION')||getCfg('INNERTUBE_CONTEXT_CLIENT_VERSION')||(client?client.clientVersion:null),
					sessionIndex:getCfg('SESSION_INDEX')||null,
					serializedExperimentFlags:serializedExperimentFlags,
					loggedIn:!!(getCfg('LOGGED_IN')||getCfg('DATASYNC_ID')||getCfg('DELEGATED_SESSION_ID')),
					premium:premium
					});
					}catch(error){
					return JSON.stringify({error:String(error&&error.stack?error.stack:error)});
					}
					})();
					""";
	@Nullable
	private ScriptBundle scriptBundle;
	@NonNull
	private final Frame frame = new Frame();
	@Nullable
	public View fullscreen;
	@Nullable
	private OkHttpWebViewInterceptor okHttpWebViewInterceptor;
	@Nullable
	private Consumer<String> updateVisitedHistory;
	private YoutubeExtractor youtubeExtractor;
	private TestTubePlayer player;
	private ExtensionManager extensionManager;
	private TabManager tabManager;
	private QueueRepository queueRepository;
	@Nullable
	private ContentFilters contentFilters;
	@Nullable
	private com.testtube.app.history.WatchHistory watchHistory;

	public void setWatchHistory(@NonNull com.testtube.app.history.WatchHistory watchHistory) {
		this.watchHistory = watchHistory;
	}
	@Nullable
	private LoadingProgressBar progressBar;
	@Nullable
	private PoTokenContextStore poTokenContextStore;
	private volatile boolean initialized;
	@Nullable
	private volatile String poTokenInflightKey;
	@Nullable
	private volatile String poTokenDoneKey;
	private volatile long prefVersion = -1L;

	public YoutubeWebview(@NonNull Context context) {
		this(context, null);
	}

	public YoutubeWebview(@NonNull Context context, @Nullable AttributeSet attrs) {
		this(context, attrs, 0);
	}

	public YoutubeWebview(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
		super(context, attrs, defStyleAttr);
	}

	static boolean canLoad(@NonNull String url) {
		if (UrlUtils.externalUri(url) != null) return false;
		if (UrlUtils.isAllowedUrl(url)) return true;
		String scheme = scheme(url);
		return isScheme(scheme, "file") || isScheme(scheme, "about") || isScheme(scheme, "data") || isScheme(scheme, "javascript");
	}

	static boolean canOpenExternal(@NonNull String url) {
		if (UrlUtils.externalUri(url) != null) return true;
		if (UrlUtils.isAllowedUrl(url)) return false;
		String scheme = scheme(url);
		return isScheme(scheme, "http") || isScheme(scheme, "https");
	}

	@Nullable
	private static String scheme(@NonNull String url) {
		try {
			return URI.create(url).getScheme();
		} catch (IllegalArgumentException ignored) {
			return null;
		}
	}

	private static boolean isScheme(@Nullable String actual, @NonNull String expected) {
		return expected.equalsIgnoreCase(actual);
	}

	@NonNull
	static String sanitizeLoadUrl(@NonNull String url, boolean queueEnabled) {
		if (!queueEnabled || !Constant.PAGE_WATCH.equals(UrlUtils.getPageClass(url))) {
			return url;
		}
		try {
			URI uri = URI.create(url);
			String rawQuery = uri.getRawQuery();
			if (rawQuery == null || rawQuery.isEmpty()) {
				return url;
			}
			boolean removed = false;
			StringBuilder filteredQuery = new StringBuilder();
			for (String part : rawQuery.split("&")) {
				if (part.isEmpty()) continue;
				int separatorIndex = part.indexOf('=');
				String key = separatorIndex >= 0 ? part.substring(0, separatorIndex) : part;
				if ("list".equalsIgnoreCase(key)) {
					removed = true;
					continue;
				}
				if (filteredQuery.length() > 0) filteredQuery.append('&');
				filteredQuery.append(part);
			}
			if (!removed) {
				return url;
			}
			return new URI(uri.getScheme(), uri.getRawAuthority(), uri.getRawPath(), filteredQuery.length() > 0 ? filteredQuery.toString() : null, uri.getRawFragment()).toString();
		} catch (Exception ignored) {
			return url;
		}
	}

	public void setOkHttpClient(@NonNull OkHttpClient okHttpClient, @NonNull WebViewCachePolicy webViewCachePolicy) {
		okHttpWebViewInterceptor = new OkHttpWebViewInterceptor(okHttpClient, webViewCachePolicy);
	}

	public void setUpdateVisitedHistory(@Nullable Consumer<String> updateVisitedHistory) {
		this.updateVisitedHistory = updateVisitedHistory;
	}


	public void setYoutubeExtractor(@NonNull YoutubeExtractor youtubeExtractor) {
		this.youtubeExtractor = youtubeExtractor;
	}

	public void setPlayer(@NonNull TestTubePlayer player) {
		this.player = player;
	}

	public void setExtensionManager(@NonNull ExtensionManager extensionManager) {
		this.extensionManager = extensionManager;
	}

	public void setTabManager(@NonNull TabManager tabManager) {
		this.tabManager = tabManager;
	}

	/**
	 * The native bridge only answers while the top-level page is a YouTube page served over
	 * https, so other sites that end up in this WebView cannot use it.
	 */
	private volatile boolean trustedPage;
	@Nullable
	private volatile String pageUrl;

	private static final java.util.Set<String> TRACKING_HOSTS = java.util.Set.of(
					"googleads.g.doubleclick.net", "static.doubleclick.net", "ad.doubleclick.net",
					"pagead2.googlesyndication.com", "tpc.googlesyndication.com", "www.googleadservices.com");

	/**
	 * Telemetry and ad requests made by YouTube's page. Playback and watch-time reports
	 * (/api/stats/playback, /api/stats/watchtime) are not on this list: they keep the account's
	 * YouTube history working.
	 */
	static boolean isTrackingRequest(@NonNull Uri uri, @Nullable String path) {
		String host = uri.getHost();
		if (host == null) return false;
		if (TRACKING_HOSTS.contains(host)) return true;
		if (path == null) return false;
		if (host.equals("play.google.com") && path.startsWith("/log")) return true;
		if (!host.endsWith("youtube.com") && !host.endsWith("googlevideo.com")) return false;
		return path.startsWith("/youtubei/v1/log_event")
						|| path.startsWith("/ptracking")
						|| path.startsWith("/generate_204")
						|| path.startsWith("/api/stats/ads")
						|| path.startsWith("/api/stats/qoe")
						|| path.startsWith("/pagead/")
						|| path.startsWith("/pcs/activeview");
	}

	/**
	 * Video data requested by the page itself while it shows a regular video page. Shorts are
	 * left alone because the page's player plays them.
	 */
	private boolean isHiddenPlayerMedia(@NonNull Uri uri, @Nullable String path) {
		String host = uri.getHost();
		if (host == null || path == null || !path.startsWith("/videoplayback")) return false;
		if (!host.endsWith(".googlevideo.com")) return false;
		return Constant.PAGE_WATCH.equals(UrlUtils.getPageClass(pageUrl));
	}
	private static final java.util.Set<String> BRIDGE_ORIGINS = java.util.Set.of("https://youtube.com", "https://*.youtube.com");
	/**
	 * True when the page talks to the app through an origin-restricted message channel; false on
	 * old WebView versions, which fall back to addJavascriptInterface.
	 */
	private boolean messageBridge;
	@Nullable
	private JavascriptInterface bridge;
	@Nullable
	private String bridgeShim;
	private final com.testtube.app.player.queue.QueueInvalidationListener queueListener = () -> post(this::pushBridgeState);

	public boolean isTrustedPage() {
		return trustedPage;
	}

	public void setContentFilters(@NonNull ContentFilters contentFilters) {
		this.contentFilters = contentFilters;
	}

	public void setQueueRepository(@NonNull QueueRepository queueRepository) {
		this.queueRepository = queueRepository;
		if (isAttachedToWindow()) {
			queueRepository.addListener(queueListener);
		}
	}

	public void setPoTokenContextStore(@NonNull PoTokenContextStore poTokenContextStore) {
		this.poTokenContextStore = poTokenContextStore;
	}

	public boolean isPoTokenReadyCandidate() {
		String url = frame.url;
		return initialized && frame.finished && UrlUtils.isAllowedUrl(url) && !UrlUtils.isGoogleAccountsUrl(url) && !url.startsWith("file:");
	}

	@Override
	protected void onFinishInflate() {
		super.onFinishInflate();
		progressBar = findViewById(R.id.progressBar);
	}

	@Override
	public void loadUrl(@NonNull String url) {
		String loadUrl = sanitizeLoadUrl(url);
		Uri external = UrlUtils.externalUri(loadUrl);
		if (external != null) {
			openExternal(external);
			return;
		}
		if (canLoad(loadUrl)) {
			super.loadUrl(loadUrl);
			return;
		}
		if (canOpenExternal(loadUrl)) {
			openExternal(Uri.parse(loadUrl));
			return;
		}
		Log.w("YoutubeWebview", "Blocked attempt to load unauthorized URL: " + loadUrl);
	}

	/**
	 * Strips everything from a page-supplied intent: URI that could reach this app's private
	 * components or grant access to its files, the same way browsers handle intent: links.
	 */
	@NonNull
	private Intent safeIntent(@NonNull Intent intent) {
		intent.addCategory(Intent.CATEGORY_BROWSABLE);
		intent.setComponent(null);
		intent.setSelector(null);
		intent.setClipData(null);
		intent.removeFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
						| Intent.FLAG_GRANT_WRITE_URI_PERMISSION
						| Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
						| Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
		if (getContext().getPackageName().equals(intent.getPackage())) {
			intent.setPackage(null);
		}
		intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		return intent;
	}

	private void openExternal(@NonNull Uri uri) {
		try {
			getContext().startActivity(new Intent(Intent.ACTION_VIEW, uri));
		} catch (ActivityNotFoundException e) {
			ToastUtils.show(getContext(), R.string.application_not_found);
			Log.e(getContext().getString(R.string.application_not_found), e.toString());
		}
	}

	@NonNull
	String sanitizeLoadUrl(@NonNull String url) {
		return sanitizeLoadUrl(url, queueRepository != null && queueRepository.isEnabled());
	}

	@SuppressLint({"SetJavaScriptEnabled", "ClickableViewAccessibility"})
	public void init() {
		ScriptBundle bundle = ScriptBundle.get(getContext());
		scriptBundle = bundle;
		initialized = true;
		setFocusable(true);
		setFocusableInTouchMode(true);
		setLayerType(LAYER_TYPE_HARDWARE, null);

		CookieManager.getInstance().setAcceptCookie(true);

		WebSettings settings = getSettings();
		settings.setJavaScriptEnabled(true);
		settings.setAllowFileAccess(false);
		settings.setAllowContentAccess(false);
		settings.setGeolocationEnabled(false);
		settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
		settings.setDatabaseEnabled(true);
		settings.setDomStorageEnabled(true);
		settings.setCacheMode(WebSettings.LOAD_DEFAULT);
		settings.setLoadWithOverviewMode(true);
		settings.setUseWideViewPort(true);
		settings.setLoadsImagesAutomatically(true);
		settings.setSupportZoom(false);
		settings.setBuiltInZoomControls(false);
		settings.setMediaPlaybackRequiresUserGesture(false);
		settings.setUserAgentString(Constant.USER_AGENT);

		JavascriptInterface jsInterface = new JavascriptInterface(this, youtubeExtractor, player, extensionManager, tabManager, queueRepository, Objects.requireNonNull(contentFilters), Objects.requireNonNull(watchHistory));
		bridge = jsInterface;
		if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
			// Only YouTube pages get the channel, and only calls from the top frame are accepted,
			// so ads and other embedded frames cannot reach the app.
			WebViewCompat.addWebMessageListener(this, "testtubeBridge", BRIDGE_ORIGINS,
							(view, message, sourceOrigin, isMainFrame, replyProxy) -> {
								String data = message.getData();
								if (!isMainFrame || !trustedPage || data == null || data.length() > 2_000_000) return;
								jsInterface.dispatch(data);
							});
			bridgeShim = bundle.bridgeShim;
			messageBridge = bridgeShim != null;
		}
		if (!messageBridge) {
			addJavascriptInterface(jsInterface, "testtube");
		}
		setTag(jsInterface);

		setWebViewClient(new WebViewClient() {

			@Override
			public boolean shouldOverrideUrlLoading(@NonNull WebView view, @NonNull WebResourceRequest request) {
				Uri uri = request.getUrl();
				if (Objects.equals(uri.getScheme(), "intent")) {
					// open in other app
					try {
						Intent intent = safeIntent(Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME));
						getContext().startActivity(intent);
					} catch (ActivityNotFoundException | URISyntaxException e) {
						ToastUtils.show(getContext(), R.string.application_not_found);
						Log.e(getContext().getString(R.string.application_not_found), e.toString());
					}
				} else {
					Uri external = UrlUtils.externalUri(uri);
					if (external != null) {
						openExternal(external);
						return true;
					}
					// restrict domain
					if (UrlUtils.isAllowedDomain(uri)) return false;
					openExternal(uri);
				}
				return true;
			}

			@Override
			public void doUpdateVisitedHistory(@NonNull WebView view, @NonNull String url, boolean isReload) {
				super.doUpdateVisitedHistory(view, url, isReload);
				trustedPage = UrlUtils.isTrustedPageUrl(url);
				pageUrl = url;
				pushBridgeState(url);
				evaluateJavascript("window.dispatchEvent(new Event('doUpdateVisitedHistory'));", null);
				if (updateVisitedHistory != null) updateVisitedHistory.accept(url);
				post(YoutubeWebview.this::refreshPoTokenContext);
			}

			@Override
			public void onPageStarted(@NonNull WebView view, @NonNull String url, @Nullable Bitmap favicon) {
				super.onPageStarted(view, url, favicon);
				trustedPage = UrlUtils.isTrustedPageUrl(url);
				pageUrl = url;
				frame.epoch.incrementAndGet();
				frame.finished = false;
				frame.url = url;
				onNavStarted();
				if (progressBar != null) progressBar.beginLoading();
				evaluateJavascript("window.dispatchEvent(new Event('onPageStarted'));", null);
				injectJavaScript(url);
			}

			@Override
			public void onPageFinished(@NonNull WebView view, @NonNull String url) {
				super.onPageFinished(view, url);
				frame.finished = true;
				frame.url = url;
				evaluateJavascript("window.dispatchEvent(new Event('onPageFinished'));", null);
				// Injected again when the page has finished: the injection at page start can land
				// in the previous document. Every script and style guards against running twice.
				injectJavaScript(url);
				refreshPoTokenContext();
			}

			@Override
			public void onReceivedError(@NonNull WebView view, @NonNull WebResourceRequest request, @NonNull WebResourceError error) {
				if (request.isForMainFrame()) {
					int errorCode = error.getErrorCode();
					String failingUrl = request.getUrl().toString();
					String description = error.getDescription().toString();

					String encodedDescription = URLEncoder.encode(description, StandardCharsets.UTF_8);
					String encodedUrl = URLEncoder.encode(failingUrl, StandardCharsets.UTF_8);
					String url = "file:///android_asset/page/error.html?description=" + encodedDescription + "&errorCode=" + errorCode + "&url=" + encodedUrl;
					post(() -> view.loadUrl(url));
				}
			}

			@Override
			public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
				if (request.isForMainFrame()) {
					int statusCode = errorResponse.getStatusCode();
					String failingUrl = request.getUrl().toString();
					String reason = errorResponse.getReasonPhrase();

					String encodedDescription = URLEncoder.encode("HTTP Error " + statusCode + ": " + reason, StandardCharsets.UTF_8);
					String encodedUrl = URLEncoder.encode(failingUrl, StandardCharsets.UTF_8);
					String url = "file:///android_asset/page/error.html?description=" + encodedDescription + "&errorCode=" + statusCode + "&url=" + encodedUrl;
					post(() -> view.loadUrl(url));
				}
			}

			@Nullable
			@Override
			public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
				Uri uri = request.getUrl();
				String path = uri.getPath();
				if (isHiddenPlayerMedia(uri, path) || isTrackingRequest(uri, path)) {
					// The page's own player is hidden and muted on video pages; the app's player
					// plays the video. Letting the page download the same video as well would
					// halve the bandwidth left for the real player.
					return new WebResourceResponse("text/plain", "utf-8", 204, "No Content",
									Collections.emptyMap(), new ByteArrayInputStream(new byte[0]));
				}
				if (path != null && path.equals("/live_chat") && okHttpWebViewInterceptor != null && okHttpWebViewInterceptor.canExecute(request)) {
					String url = uri.toString();
					Response okHttpResponse = null;
					try {
						okHttpResponse = okHttpWebViewInterceptor.execute(request);
						if (okHttpResponse == null) {
							return super.shouldInterceptRequest(view, request);
						}

						InputStream sourceStream = okHttpResponse.body().byteStream();
						String injectedScript = "<script>(function(){ " + "document.addEventListener('tap', (e) => { " + "const msg = e.target.closest('yt-live-chat-text-message-renderer'); " + "if (!msg) return; " + "e.preventDefault(); " + "e.stopImmediatePropagation(); " + "}, true); " + "})();</script>";
						InputStream injectedStream = new ByteArrayInputStream(injectedScript.getBytes(StandardCharsets.UTF_8));
						Enumeration<InputStream> streams = Collections.enumeration(Arrays.asList(injectedStream, sourceStream));
						SequenceInputStream sequenceInputStream = new SequenceInputStream(streams);
						return okHttpWebViewInterceptor.toWebResourceResponse(url, okHttpResponse, sequenceInputStream);
					} catch (Exception ignored) {
						if (okHttpResponse != null) okHttpResponse.close();
					}
				}
				if (okHttpWebViewInterceptor != null) {
					WebResourceResponse response = okHttpWebViewInterceptor.intercept(request);
					if (response != null) return response;
				}
				return super.shouldInterceptRequest(view, request);
			}

		});

		setWebChromeClient(new WebChromeClient() {

			@Override
			public boolean onConsoleMessage(@NonNull ConsoleMessage consoleMessage) {
				// Page console output is only useful while developing; release builds drop it.
				if ((getContext().getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0) return true;
				Log.d("js-log", consoleMessage.message() + " -- From line " + consoleMessage.lineNumber() + " of " + consoleMessage.sourceId());
				return super.onConsoleMessage(consoleMessage);
			}

			@Override
			public void onProgressChanged(@NonNull WebView view, int progress) {
				if (progressBar == null) {
					super.onProgressChanged(view, progress);
					return;
				}
				if (progress >= 100) {
					progressBar.finishLoading();
					evaluateJavascript("window.dispatchEvent(new Event('onProgressChangeFinish'));", null);
				} else {
					progressBar.setLoadingProgress(progress);
				}
				super.onProgressChanged(view, progress);
			}

			// replace video default poster
			@Override
			public Bitmap getDefaultVideoPoster() {
				return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
			}

			@Override
			public void onShowCustomView(@NonNull View view, @NonNull CustomViewCallback callback) {
				setVisibility(View.GONE);

				if (getContext() instanceof MainActivity mainActivity) {
					if (fullscreen != null)
						((FrameLayout) mainActivity.getWindow().getDecorView()).removeView(fullscreen);

					fullscreen = new FrameLayout(getContext());
					((FrameLayout) fullscreen).addView(view, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
					ViewUtils.setFullscreen(fullscreen, true);

					((FrameLayout) mainActivity.getWindow().getDecorView()).addView(fullscreen, new FrameLayout.LayoutParams(-1, -1));
					fullscreen.setVisibility(View.VISIBLE);
					// keep screen going on
					fullscreen.setKeepScreenOn(true);
					evaluateJavascript("window.dispatchEvent(new Event('onFullScreen'));", null);
				}
			}

			@Override
			public void onHideCustomView() {
				if (fullscreen == null) return;
				ViewUtils.setFullscreen(fullscreen, false);
				fullscreen.setVisibility(View.GONE);
				fullscreen.setKeepScreenOn(false);
				setVisibility(View.VISIBLE);
				if (getContext() instanceof MainActivity) {
					evaluateJavascript("window.dispatchEvent(new Event('exitFullScreen'));", null);
				}
			}
		});
	}

	public void refreshPoTokenContext() {
		PoTokenContextStore contextStore = poTokenContextStore;
		String pageUrl = frame.url;
		long capturedPageEpoch = frame.epoch.get();
		if (contextStore == null || !isShown() || !isPoTokenReadyCandidate() || pageUrl == null) {
			return;
		}
		String key = capturedPageEpoch + "|" + pageUrl;
		if (key.equals(poTokenInflightKey) || key.equals(poTokenDoneKey)) return;
		poTokenInflightKey = key;
		evaluateJavascript(PO_TOKEN_CONTEXT_SCRIPT, rawValue -> {
			if (contextStore != poTokenContextStore || capturedPageEpoch != frame.epoch.get() || !Objects.equals(pageUrl, frame.url)) {
				poTokenInflightKey = null;
				return;
			}
			PoTokenWebViewContext context = PoTokenWebViewContext.fromJson(pageUrl, capturedPageEpoch, PoTokenJsonUtils.normalizeEvaluateJavascriptResult(rawValue));
			if (context != null) {
				contextStore.update(context);
				poTokenDoneKey = key;
			}
			poTokenInflightKey = null;
		});
	}

	/**
	 * Sends the values the page reads synchronously (settings, filters, labels, queue state and
	 * resume position) into the page.
	 */
	private void pushBridgeState() {
		pushBridgeState(null);
	}

	private void pushBridgeState(@Nullable String url) {
		JavascriptInterface target = bridge;
		if (!messageBridge || target == null || !trustedPage) return;
		String pageUrl = url != null ? url : frame.url != null ? frame.url : getUrl();
		evaluateJavascript("window.__testtubeState=" + target.bridgeState(pageUrl) + ";", null);
	}

	@Override
	protected void onAttachedToWindow() {
		super.onAttachedToWindow();
		if (queueRepository != null) queueRepository.addListener(queueListener);
	}

	@Override
	protected void onDetachedFromWindow() {
		if (queueRepository != null) queueRepository.removeListener(queueListener);
		super.onDetachedFromWindow();
	}

	private void injectJavaScript(@Nullable String url) {
		if (UrlUtils.isGoogleAccountsUrl(url)) return;
		if (messageBridge && trustedPage && bridgeShim != null) {
			pushBridgeState(url);
			evaluateJavascript(bridgeShim, null);
		}
		ScriptBundle bundle = scriptBundle;
		if (bundle != null) evaluateJavascript(bundle.pageScript, null);
	}

	public void setScriptActive(boolean active) {
		evaluateJavascript("(function(){window.__testtubeActive=" + active + ";if(window.__testtubeSetActive){window.__testtubeSetActive(" + active + ");}})();", null);
	}

	public void syncPreferences() {
		if (extensionManager == null) return;
		// The local watch history changes while other pages are shown, so the page gets fresh
		// state every time it comes back into view.
		pushBridgeState();
		evaluateJavascript("window.dispatchEvent(new Event('testtubeStateChanged'));", null);
		long version = extensionManager.version();
		if (version == prefVersion) return;
		prefVersion = version;
		evaluateJavascript("window.dispatchEvent(new Event('testtubePreferencesChanged'));", null);
	}

	private void onNavStarted() {
		poTokenInflightKey = null;
		poTokenDoneKey = null;
	}

/**
 * Snapshot of the current WebView navigation frame.
 */
	private static final class Frame {
		@NonNull
		private final AtomicLong epoch = new AtomicLong();
		private volatile boolean finished;
		@Nullable
		private volatile String url;
	}

}
