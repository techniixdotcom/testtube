package com.testtube.app.plus;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;
import androidx.media3.common.util.UnstableApi;

import com.testtube.app.AppGraph;
import com.testtube.app.Constant;
import com.testtube.app.R;
import com.testtube.app.extractor.FeedClient;
import com.testtube.app.extractor.FeedItem;
import com.testtube.app.links.LinkTarget;
import com.testtube.app.util.ToastUtils;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.OkHttpClient;

/**
 * Opens a Spotify link via YouTube: Spotify's audio is DRM and only plays in Spotify, so the same
 * song is found on YouTube and played with everything TestTube does (no ads, background, queue).
 * Albums and playlists play the first song and queue the rest.
 */
@UnstableApi
public final class SpotifyOpener {
	private static final String TAG = "SpotifyOpener";
	/** Gap between an album's searches so YouTube doesn't see a burst. */
	private static final long PAUSE_MS = 400L;
	private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "testtube-spotify");
		thread.setDaemon(true);
		return thread;
	});
	/** A newer link replaces one still being looked up. */
	private static final AtomicInteger GENERATION = new AtomicInteger();

	private SpotifyOpener() {
	}

	public static boolean isSupported(@NonNull String url) {
		return SpotifyLink.isSpotifyUrl(url);
	}

	@MainThread
	public static void open(@NonNull Activity activity, @NonNull String url, @NonNull LinkTarget target) {
		int job = GENERATION.incrementAndGet();
		Context app = activity.getApplicationContext();
		AppGraph graph = AppGraph.of(app);
		OkHttpClient client = WebPage.client(graph.okHttpClient());
		FeedClient feeds = graph.feedClient();
		ToastUtils.show(activity, R.string.link_spotify_reading);
		new Job(app, url, target, client, feeds, job).start();
	}

	private static final class Job {
		private final Context context;
		private final String url;
		private final LinkTarget target;
		private final OkHttpClient client;
		private final FeedClient feeds;
		private final int generation;
		private final Handler main = new Handler(Looper.getMainLooper());
		private final AtomicBoolean gone = new AtomicBoolean();

		Job(@NonNull Context context, @NonNull String url, @NonNull LinkTarget target,
		    @NonNull OkHttpClient client, @NonNull FeedClient feeds, int generation) {
			this.context = context;
			this.url = url;
			this.target = target;
			this.client = client;
			this.feeds = feeds;
			this.generation = generation;
		}

		void start() {
			WORKER.execute(() -> {
				try {
					run();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				} catch (IOException | SpotifyReader.SpotifyException | RuntimeException e) {
					Log.w(TAG, "could not open " + url, e);
					toast(R.string.link_spotify_failed);
				}
			});
		}

		private boolean stopped() {
			return gone.get() || GENERATION.get() != generation;
		}

		@WorkerThread
		private void run() throws IOException, SpotifyReader.SpotifyException, InterruptedException {
			String userAgent = Constant.DESKTOP_USER_AGENT;
			SpotifyLink.Ref ref = SpotifyReader.resolve(client, url, userAgent);
			if (stopped()) return;
			SpotifyItem item = SpotifyReader.read(client, ref, userAgent);
			if (stopped()) return;
			List<SpotifyItem.Track> tracks = item.tracks();
			if (tracks.isEmpty()) {
				// artist, show, or a page we couldn't fully read: show YouTube results
				onMain(() -> target.search(item.name()));
				return;
			}
			if (tracks.size() > 1) {
				String text = context.getResources().getQuantityString(R.plurals.link_spotify_finding,
								tracks.size(), tracks.size());
				onMain(() -> ToastUtils.show(context, text));
			}
			int found = 0;
			for (int i = 0; i < tracks.size(); i++) {
				if (stopped()) return;
				if (i > 0) Thread.sleep(PAUSE_MS);
				SpotifyItem.Track track = tracks.get(i);
				FeedItem match = match(track);
				if (stopped()) return;
				if (match == null) {
					if (tracks.size() == 1) {
						toast(R.string.link_spotify_not_found);
						onMain(() -> target.search(track.query()));
					}
					continue;
				}
				String videoId = match.videoId();
				if (videoId == null) continue;
				if (found == 0) {
					onMain(() -> target.playVideo(videoId));
				} else {
					onMain(() -> target.queueVideo(videoId, match.title(), match.author(), match.thumbnailUrl()));
				}
				found++;
			}
			if (tracks.size() > 1 && !stopped()) {
				if (found == 0) {
					toast(R.string.link_spotify_not_found);
					onMain(() -> target.search(item.name()));
				} else {
					String text = context.getString(R.string.link_spotify_done, found, tracks.size());
					onMain(() -> ToastUtils.show(context, text));
				}
			}
		}

		/** Best YouTube match, or null. */
		@WorkerThread
		private FeedItem match(@NonNull SpotifyItem.Track track) throws InterruptedException {
			return YoutubeSearch.find(feeds, track);
		}

		private void toast(int message) {
			onMain(() -> ToastUtils.show(context, message));
		}

		/** Main thread, while the screen is alive and no newer link replaced this one. */
		private void onMain(@NonNull Runnable action) {
			main.post(() -> {
				if (stopped()) return;
				if (!target.isAlive()) {
					gone.set(true);
					return;
				}
				action.run();
			});
		}
	}
}
