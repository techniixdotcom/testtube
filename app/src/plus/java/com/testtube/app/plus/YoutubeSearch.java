package com.testtube.app.plus;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.testtube.app.extractor.FeedClient;
import com.testtube.app.extractor.FeedItem;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Finds a song on YouTube: search, then {@link YoutubeMatcher} picks the right video. */
final class YoutubeSearch {
	private static final String TAG = "YoutubeSearch";
	private static final long TIMEOUT_S = 30L;

	private YoutubeSearch() {
	}

	/**
	 * YouTube video of the song, or null if nothing's close or the search failed. Blocks; not on the
	 * main thread or the feed client's threads.
	 */
	@WorkerThread
	@Nullable
	static FeedItem find(@NonNull FeedClient feeds, @NonNull SpotifyItem.Track song) throws InterruptedException {
		FeedClient.Call call = feeds.search(song.query(), null);
		try {
			return YoutubeMatcher.best(call.result.get(TIMEOUT_S, TimeUnit.SECONDS).items(), song);
		} catch (ExecutionException | TimeoutException | RuntimeException e) {
			call.cancel();
			Log.w(TAG, "YouTube search failed", e);
			return null;
		} catch (InterruptedException e) {
			call.cancel();
			throw e;
		}
	}
}
