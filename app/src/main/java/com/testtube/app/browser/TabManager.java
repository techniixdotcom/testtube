package com.testtube.app.browser;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.webkit.ValueCallback;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.media3.common.util.UnstableApi;

import com.testtube.app.Constant;
import com.testtube.app.R;
import com.testtube.app.extractor.PageSource;
import com.testtube.app.extractor.PlaylistSource;
import com.testtube.app.extractor.YoutubeExtractor;
import com.testtube.app.player.TestTubePlayer;
import com.testtube.app.util.UrlUtils;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedList;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Navigation between the native screens, the native watch screen and the YouTube web pages that
 * are still web based (channels, playlists, ...).
 * <p>
 * The watch screen is native: the player on top and the details below. Leaving it moves the
 * video into the mini player; the mini player brings it back. Web pages form a stack shown over
 * the native screens; when the stack is empty the native screen underneath is visible.
 */
@UnstableApi
public class TabManager {
	private static final int MAX_TABS = 2;
	private static final int MAX_WATCH_BACK = 50;

	/**
	 * Receives navigation changes.
	 */
	public interface Host {
		/**
		 * @param visible true when a web page covers the native screens
		 */
		void onWebVisible(boolean visible);

		/**
		 * @param url the video shown on the watch screen, or null when the watch screen is closed
		 * @param visible true when the watch screen is in front, false when it sits in the mini
		 *                player or is closed
		 */
		void onWatchChanged(@Nullable String url, boolean visible);

		/**
		 * A web page asked for a screen the app shows natively (Home or Subscriptions).
		 */
		void onNativeRequested(@NonNull String pageClass);

		/**
		 * Shows a channel or playlist on the native page screen.
		 */
		void onNativePage(@NonNull PageSource.Kind kind, @NonNull String url);
	}

	/**
	 * Result of a playlist step.
	 */
	public interface PlaylistStep {
		/**
		 * @param url the video to play, or null when there is none
		 * @param end true when the playlist has no further video in that direction
		 */
		void onResult(@Nullable String url, boolean end);
	}

	@NonNull
	private final Activity activity;
	@NonNull
	private final Supplier<TestTubePlayer> player;
	@NonNull
	private final PlaylistSource playlists;
	@NonNull
	private final Handler handler = new Handler(Looper.getMainLooper());
	@NonNull
	private final Deque<YoutubeFragment> tabs = new LinkedList<>();
	@NonNull
	private final Deque<String> watchBack = new ArrayDeque<>();
	@Nullable
	private YoutubeFragment tab;
	@Nullable
	private Host host;
	/**
	 * The video of the watch screen, null when it is closed.
	 */
	@Nullable
	private String watchUrl;
	private boolean watchVisible;

	public TabManager(@NonNull Activity activity,
	                  @NonNull Supplier<TestTubePlayer> player,
	                  @NonNull PlaylistSource playlists) {
		this.activity = activity;
		this.player = player;
		this.playlists = playlists;
	}

	public void setHost(@Nullable Host host) {
		this.host = host;
	}

	static boolean isNativePage(@Nullable String pageClass) {
		return Constant.PAGE_HOME.equals(pageClass) || Constant.PAGE_SUBSCRIPTIONS.equals(pageClass)
						|| Constant.PAGE_LIBRARY.equals(pageClass) || "history".equals(pageClass);
	}

	@NonNull
	private TestTubePlayer testtubePlayer() {
		return Objects.requireNonNull(player.get());
	}

	public boolean isWebVisible() {
		return !watchVisible && tab != null;
	}

	/**
	 * A web page changed its address (including in-page navigation).
	 */
	public void onUrlChanged(@NonNull YoutubeFragment fragment, @NonNull String url) {
		if (fragment != tab) return;
		String pageClass = UrlUtils.getPageClass(url);
		if (isNativePage(pageClass) || "searching".equals(pageClass)) {
			// Posted: the request can come from inside a WebView callback of the page being closed.
			Host current = host;
			String request = "searching".equals(pageClass) ? "searching:" + UrlUtils.getQueryParameter(url, "search_query") : pageClass;
			if (current != null) handler.post(() -> current.onNativeRequested(request));
			return;
		}
		if (Constant.PAGE_WATCH.equals(pageClass)) {
			// A link on a web page opened a video: show it natively and put the page back.
			handler.post(() -> {
				undoWatchNavigation(fragment);
				openWatch(url);
			});
			return;
		}
		PageSource.Kind kind = nativePageKind(url);
		if (kind != null) {
			handler.post(() -> openNativePage(kind, url));
		}
	}

	private void undoWatchNavigation(@NonNull YoutubeFragment fragment) {
		YoutubeWebview webView = fragment.getWebView();
		if (webView != null && webView.canGoBack()) {
			webView.goBack();
			return;
		}
		tabs.remove(fragment);
		if (tab == fragment) tab = tabs.peekLast();
		FragmentTransaction ft = fm().beginTransaction().remove(fragment);
		YoutubeFragment next = tab;
		if (next != null) ft.show(next);
		ft.commit();
	}

	private void notifyWebVisible() {
		Host current = host;
		if (current != null) current.onWebVisible(isWebVisible());
	}

	private void notifyWatch() {
		Host current = host;
		if (current != null) current.onWatchChanged(watchUrl, watchVisible);
	}

	@NonNull
	private FragmentManager fm() {
		return ((FragmentActivity) activity).getSupportFragmentManager();
	}

	/**
	 * Opens a page. Videos open on the native watch screen, Home and Subscriptions on their
	 * native screens, everything else as a web page.
	 */
	public void openTab(@NonNull String url, @Nullable String tag) {
		String targetTag = tag != null ? tag : UrlUtils.getPageClass(url);
		if (isNativePage(targetTag) || "searching".equals(targetTag)) {
			Host current = host;
			String request = "searching".equals(targetTag) ? "searching:" + UrlUtils.getQueryParameter(url, "search_query") : targetTag;
			if (current != null) current.onNativeRequested(request);
			return;
		}
		if (Constant.PAGE_WATCH.equals(targetTag)) {
			openWatch(url);
			return;
		}
		PageSource.Kind kind = nativePageKind(url);
		if (kind != null) {
			openNativePage(kind, url);
			return;
		}
		if (watchVisible && !minimizeWatch()) closeWatch();
		YoutubeFragment top = this.tab;
		if (top != null && Constant.PAGE_SHORTS.equals(targetTag) && Constant.PAGE_SHORTS.equals(pageClass(top))) {
			if (!url.equals(top.getUrl())) top.loadUrl(url);
			notifyWebVisible();
			return;
		}
		FragmentTransaction ft = fm().beginTransaction();
		if (top != null) ft.hide(top);
		YoutubeFragment next = YoutubeFragment.newInstance(url, targetTag);
		tab = next;
		tabs.offerLast(next);
		ft.add(R.id.fragment_container, next, targetTag);
		trimTabs(ft, next);
		ft.show(next);
		commitAndRun(ft, this::notifyWebVisible);
	}

	@Nullable
	static PageSource.Kind nativePageKind(@NonNull String url) {
		if (UrlUtils.isNativeChannelUrl(url)) return PageSource.Kind.CHANNEL;
		if (UrlUtils.isNativePlaylistUrl(url)) return PageSource.Kind.PLAYLIST;
		return null;
	}

	/**
	 * Closes the web pages and shows a channel or playlist natively. A playing video moves to the
	 * mini player.
	 */
	public void openNativePage(@NonNull PageSource.Kind kind, @NonNull String url) {
		closeWebPages();
		Host current = host;
		if (current != null) current.onNativePage(kind, url);
	}

	/**
	 * Shows the watch screen in front and plays the video.
	 */
	public void openWatch(@NonNull String url) {
		TestTubePlayer testtubePlayer = testtubePlayer();
		if (testtubePlayer.isInMiniPlayer()) {
			testtubePlayer.exitInAppMiniPlayer();
			testtubePlayer.setMiniPlayerCallbacks(null, null);
		}
		watchVisible = true;
		navigateWatch(url, true);
		notifyWebVisible();
	}

	/**
	 * Plays a video on the watch screen, wherever it is (in front or in the mini player).
	 */
	public void playInWatch(@NonNull String url) {
		navigateWatch(url, true);
	}

	private void navigateWatch(@NonNull String url, boolean remember) {
		String previous = watchUrl;
		String previousId = YoutubeExtractor.getVideoId(previous);
		if (remember && previous != null && !Objects.equals(previousId, YoutubeExtractor.getVideoId(url))) {
			watchBack.offerLast(previous);
			while (watchBack.size() > MAX_WATCH_BACK) watchBack.pollFirst();
		}
		watchUrl = url;
		testtubePlayer().play(url);
		notifyWatch();
	}

	/**
	 * Closes the web pages and the watch screen and reveals the native screen. A playing video
	 * moves to the mini player.
	 */
	public void showNative() {
		closeWebPages();
	}

	private void closeWebPages() {
		if (watchVisible && !minimizeWatch()) closeWatch();
		if (!tabs.isEmpty()) {
			FragmentTransaction ft = fm().beginTransaction();
			for (YoutubeFragment fragment : tabs) ft.remove(fragment);
			tabs.clear();
			tab = null;
			ft.commit();
		}
		notifyWebVisible();
	}

	@Nullable
	public YoutubeWebview getWebView() {
		return isWebVisible() && tab != null ? tab.getWebView() : null;
	}

	public void evaluateJavascript(@NonNull String script, @Nullable ValueCallback<String> callback) {
		YoutubeWebview webView = getWebView();
		if (webView != null) webView.evaluateJavascript(script, callback);
	}

	public boolean canGoBackInWatch() {
		return !watchBack.isEmpty();
	}

	public void goBackInWatch() {
		String previous = watchBack.pollLast();
		if (previous != null) navigateWatch(previous, false);
	}

	public boolean watchHasPlaylist() {
		return playlistId(watchUrl) != null;
	}

	@Nullable
	public String getWatchUrl() {
		return watchUrl;
	}

	@Nullable
	private static String playlistId(@Nullable String url) {
		if (url == null) return null;
		String list = UrlUtils.getQueryParameter(url, "list");
		return list == null || list.isBlank() ? null : list;
	}

	/**
	 * Loads the playlist of the current video.
	 */
	public void loadPlaylist(@NonNull Consumer<PlaylistSource.Playlist> callback) {
		String url = watchUrl;
		String listId = playlistId(url);
		if (listId == null) {
			callback.accept(null);
			return;
		}
		playlists.load(listId, YoutubeExtractor.getVideoId(url)).whenComplete((playlist, error) -> handler.post(() -> {
			if (!Objects.equals(url, watchUrl)) return;
			callback.accept(error == null ? playlist : null);
		}));
	}

	/**
	 * Works out the next (+1), previous (-1) or a random (0) video of the current playlist.
	 */
	public void playlistStep(int offset, @NonNull PlaylistStep callback) {
		String currentId = YoutubeExtractor.getVideoId(watchUrl);
		loadPlaylist(playlist -> {
			if (playlist == null || playlist.items().isEmpty()) {
				callback.onResult(null, false);
				return;
			}
			int size = playlist.items().size();
			int index = playlist.indexOf(currentId);
			if (offset == 0) {
				if (size == 1) {
					callback.onResult(playlist.urlAt(0), false);
					return;
				}
				int pick = ThreadLocalRandom.current().nextInt(index >= 0 ? size - 1 : size);
				if (index >= 0 && pick >= index) pick++;
				callback.onResult(playlist.urlAt(pick), false);
				return;
			}
			if (index < 0) {
				callback.onResult(offset > 0 ? playlist.urlAt(0) : null, offset < 0);
				return;
			}
			int target = index + offset;
			if (target < 0 || target >= size) {
				callback.onResult(null, true);
				return;
			}
			callback.onResult(playlist.urlAt(target), false);
		});
	}

	@Nullable
	private String pageClass(@Nullable YoutubeFragment fragment) {
		if (fragment == null) return null;
		String url = fragment.getUrl();
		return url != null ? UrlUtils.getPageClass(url) : fragment.getTabTag();
	}

	/**
	 * @return false when nothing is open over the native screens, so they handle back themselves
	 */
	public boolean goBack() {
		if (watchVisible) {
			if (!minimizeWatch()) closeWatch();
			return true;
		}
		YoutubeFragment top = this.tab;
		if (top == null) return false;
		YoutubeWebview webView = top.getWebView();
		if (webView != null && webView.canGoBack()) {
			webView.goBack();
			return true;
		}
		FragmentTransaction ft = fm().beginTransaction();
		tabs.remove(top);
		ft.remove(top);
		YoutubeFragment next = tabs.peekLast();
		tab = next;
		if (next != null) ft.show(next);
		commitAndRun(ft, this::notifyWebVisible);
		return true;
	}

	/**
	 * Moves the playing video into the mini player and shows what is underneath.
	 *
	 * @return false when the watch screen is not in front or nothing can play in the mini player
	 */
	public boolean minimizeWatch() {
		if (!watchVisible) return false;
		TestTubePlayer testtubePlayer = testtubePlayer();
		if (!testtubePlayer.canSuspendWatch()) return false;
		watchVisible = false;
		testtubePlayer.setMiniPlayerCallbacks(this::restoreWatch, this::onMiniPlayerClosed);
		testtubePlayer.enterInAppMiniPlayer();
		notifyWatch();
		notifyWebVisible();
		return true;
	}

	private void restoreWatch() {
		if (watchUrl == null) return;
		TestTubePlayer testtubePlayer = testtubePlayer();
		testtubePlayer.exitInAppMiniPlayer();
		testtubePlayer.setMiniPlayerCallbacks(null, null);
		watchVisible = true;
		notifyWatch();
		notifyWebVisible();
	}

	private void onMiniPlayerClosed() {
		// The player already stopped itself.
		watchUrl = null;
		watchVisible = false;
		watchBack.clear();
		notifyWatch();
		notifyWebVisible();
	}

	/**
	 * Stops the video and closes the watch screen.
	 */
	public void closeWatch() {
		boolean wasOpen = watchUrl != null;
		watchUrl = null;
		watchVisible = false;
		watchBack.clear();
		testtubePlayer().hide();
		if (wasOpen) notifyWatch();
		notifyWebVisible();
	}

	private void trimTabs(@NonNull FragmentTransaction ft, @NonNull YoutubeFragment keep) {
		while (tabs.size() > MAX_TABS) {
			YoutubeFragment oldest = null;
			for (YoutubeFragment fragment : tabs) {
				if (fragment == keep) continue;
				oldest = fragment;
				break;
			}
			if (oldest == null) return;
			tabs.remove(oldest);
			ft.remove(oldest);
		}
	}

	private void commitAndRun(@NonNull FragmentTransaction ft, @NonNull Runnable afterCommit) {
		ft.runOnCommit(afterCommit);
		ft.commit();
	}
}
