package com.testtube.app.nav;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;

import com.testtube.app.Constant;
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
 * Navigation between the native screens and the watch screen.
 * <p>
 * The watch screen is native: the player on top and the details below. Leaving it moves the
 * video into the mini player; the mini player brings it back.
 */
@UnstableApi
public class TabManager {
	private static final int MAX_WATCH_BACK = 50;

	/**
	 * Receives navigation changes.
	 */
	public interface Host {
		/**
		 * @param url the video shown on the watch screen, or null when the watch screen is closed
		 * @param visible true when the watch screen is in front, false when it sits in the mini
		 *                player or is closed
		 */
		void onWatchChanged(@Nullable String url, boolean visible);

		/**
		 * A link asked for a screen the app shows natively (Home, Subscriptions, search or History).
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
	private final Supplier<TestTubePlayer> player;
	@NonNull
	private final PlaylistSource playlists;
	@NonNull
	private final Handler handler = new Handler(Looper.getMainLooper());
	@NonNull
	private final Deque<String> watchBack = new ArrayDeque<>();
	@Nullable
	private Host host;
	/**
	 * The video of the watch screen, null when it is closed.
	 */
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

	static boolean isNativePage(@Nullable String pageClass) {
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

	/**
	 * Opens a page. Videos open on the watch screen, Home, Subscriptions, search and History on
	 * their native screens, channels and playlists on the native page screen. Anything else is
	 * ignored.
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
		if (kind != null) openNativePage(kind, url);
	}

	@Nullable
	static PageSource.Kind nativePageKind(@NonNull String url) {
		if (UrlUtils.isNativeChannelUrl(url)) return PageSource.Kind.CHANNEL;
		if (UrlUtils.isNativePlaylistUrl(url)) return PageSource.Kind.PLAYLIST;
		return null;
	}

	/**
	 * Shows a channel or playlist natively. A playing video moves to the mini player.
	 */
	public void openNativePage(@NonNull PageSource.Kind kind, @NonNull String url) {
		leaveWatch();
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
	 * Leaves the watch screen and reveals the native screen. A playing video moves to the mini
	 * player.
	 */
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

	/**
	 * @return false when the watch screen is not open, so the native screens handle back themselves
	 */
	public boolean goBack() {
		if (!watchVisible) return false;
		if (!minimizeWatch()) closeWatch();
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
		// The player already stopped itself.
		watchUrl = null;
		watchVisible = false;
		watchBack.clear();
		notifyWatch();
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
	}
}
