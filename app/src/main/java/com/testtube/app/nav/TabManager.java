package com.testtube.app.nav;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;

import com.testtube.app.Constant;
import com.testtube.app.external.ExternalIds;
import com.testtube.app.extractor.PageSource;
import com.testtube.app.extractor.PlaylistSource;
import com.testtube.app.extractor.YoutubeExtractor;
import com.testtube.app.player.TestTubePlayer;
import com.testtube.app.util.UrlUtils;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Navigation between list screens and the watch screen. Leaving the watch screen sends the video
 * to the mini player.
 */
@UnstableApi
public class TabManager {
	private static final int MAX_WATCH_BACK = 50;

	/** Gets navigation changes. */
	public interface Host {
		/**
		 * @param url video on the watch screen, null when closed
		 * @param visible watch screen in front; false when minimized or closed
		 */
		void onWatchChanged(@Nullable String url, boolean visible);

		/** Link to Home, Subscriptions, search or History. */
		void onNativeRequested(@NonNull String pageClass);

		/** Shows a channel or playlist page. */
		void onNativePage(@NonNull PageSource.Kind kind, @NonNull String url);
	}

	/** Result of a playlist step. */
	public interface PlaylistStep {
		/**
		 * @param url video to play, or null
		 * @param end no more videos that way
		 */
		void onResult(@Nullable String url, boolean end);
	}

	@NonNull
	private final Supplier<TestTubePlayer> player;
	@NonNull
	private final PlaylistSource playlists;
	@NonNull
	private final Handler handler = new Handler(Looper.getMainLooper());
	@NonNull
	private final Deque<String> watchBack = new ArrayDeque<>();
	@Nullable
	private Host host;
	/** Watch screen video, null when closed. */
	@Nullable
	private String watchUrl;
	private boolean watchVisible;

	public TabManager(@NonNull Supplier<TestTubePlayer> player,
	                  @NonNull PlaylistSource playlists) {
		this.player = player;
		this.playlists = playlists;
	}

	public void setHost(@Nullable Host host) {
		this.host = host;
	}

	private static boolean isNativePage(@Nullable String pageClass) {
		return Constant.PAGE_HOME.equals(pageClass) || Constant.PAGE_SUBSCRIPTIONS.equals(pageClass)
						|| Constant.PAGE_LIBRARY.equals(pageClass) || "history".equals(pageClass);
	}

	@NonNull
	private TestTubePlayer testtubePlayer() {
		return Objects.requireNonNull(player.get());
	}

	private void notifyWatch() {
		Host current = host;
		if (current != null) current.onWatchChanged(watchUrl, watchVisible);
	}

	/** Opens a YouTube URL on the right screen. Anything else is ignored. */
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
		if (kind != null) openNativePage(kind, url);
	}

	@Nullable
	private static PageSource.Kind nativePageKind(@NonNull String url) {
		if (UrlUtils.isNativeChannelUrl(url)) return PageSource.Kind.CHANNEL;
		if (UrlUtils.isNativePlaylistUrl(url)) return PageSource.Kind.PLAYLIST;
		return null;
	}

	/** Shows a channel or playlist; a playing video gets minimized. */
	public void openNativePage(@NonNull PageSource.Kind kind, @NonNull String url) {
		leaveWatch();
		Host current = host;
		if (current != null) current.onNativePage(kind, url);
	}

	/** Brings the watch screen to the front and plays. */
	public void openWatch(@NonNull String url) {
		TestTubePlayer testtubePlayer = testtubePlayer();
		if (testtubePlayer.isInMiniPlayer()) {
			testtubePlayer.exitInAppMiniPlayer();
			testtubePlayer.setMiniPlayerCallbacks(null, null);
		}
		watchVisible = true;
		navigateWatch(url, true);
	}

	/** Plays on the watch screen wherever it is (front or mini player). */
	public void playInWatch(@NonNull String url) {
		navigateWatch(url, true);
	}

	/**
	 * Replaces the watch screen's link without a Back step (a SoundCloud preview playing from
	 * YouTube instead).
	 */
	public void replaceWatch(@NonNull String url) {
		navigateWatch(url, false);
	}

	private void navigateWatch(@NonNull String url, boolean remember) {
		String previous = watchUrl;
		String previousId = ExternalIds.idOf(previous);
		if (remember && previous != null && !Objects.equals(previousId, ExternalIds.idOf(url))) {
			watchBack.offerLast(previous);
			while (watchBack.size() > MAX_WATCH_BACK) watchBack.pollFirst();
		}
		watchUrl = url;
		testtubePlayer().play(url);
		notifyWatch();
	}

	/** Leaves the watch screen, a playing video gets minimized. */
	public void showNative() {
		leaveWatch();
	}

	private void leaveWatch() {
		if (watchVisible && !minimizeWatch()) closeWatch();
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

	/** Loads the current video's playlist. */
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

	/** Next (+1), previous (-1) or random (0) playlist video. */
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

	/**
	 * Back like a browser: replays the previous video; with none left the video stops and the
	 * screen it came from shows. Swiping the player down is what minimizes.
	 * @return false if the watch screen isn't open
	 */
	public boolean goBack() {
		if (!watchVisible) return false;
		if (canGoBackInWatch()) {
			goBackInWatch();
			return true;
		}
		closeWatch();
		return true;
	}

	/** Watch screen in front with a video that can be minimized. */
	public boolean canMinimizeWatch() {
		return watchVisible && testtubePlayer().canSuspendWatch();
	}

	/**
	 * Minimizes the playing video.
	 * @return false if the watch screen isn't in front or nothing can play
	 */
	public boolean minimizeWatch() {
		if (!canMinimizeWatch()) return false;
		TestTubePlayer testtubePlayer = testtubePlayer();
		watchVisible = false;
		testtubePlayer.setMiniPlayerCallbacks(this::restoreWatch, this::onMiniPlayerClosed);
		testtubePlayer.enterInAppMiniPlayer();
		notifyWatch();
		return true;
	}

	private void restoreWatch() {
		if (watchUrl == null) return;
		TestTubePlayer testtubePlayer = testtubePlayer();
		testtubePlayer.exitInAppMiniPlayer();
		testtubePlayer.setMiniPlayerCallbacks(null, null);
		watchVisible = true;
		notifyWatch();
	}

	private void onMiniPlayerClosed() {
		// player already stopped itself
		watchUrl = null;
		watchVisible = false;
		watchBack.clear();
		notifyWatch();
	}

	/** Stops the video and closes the watch screen. */
	public void closeWatch() {
		boolean wasOpen = watchUrl != null;
		watchUrl = null;
		watchVisible = false;
		watchBack.clear();
		testtubePlayer().hide();
		if (wasOpen) notifyWatch();
	}
}
