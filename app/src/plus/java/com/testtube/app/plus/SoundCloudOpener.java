package com.testtube.app.plus;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.media3.common.util.UnstableApi;

import com.google.gson.JsonObject;
import com.testtube.app.AppGraph;
import com.testtube.app.Constant;
import com.testtube.app.R;
import com.testtube.app.links.LinkTarget;
import com.testtube.app.util.ToastUtils;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.OkHttpClient;

/**
 * Opens a SoundCloud link: tracks play, sets play the first song and queue the rest, artists
 * show a YouTube search. Short links get followed first.
 */
@UnstableApi
public final class SoundCloudOpener {
	private static final String TAG = "SoundCloudOpener";
	private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "testtube-soundcloud");
		thread.setDaemon(true);
		return thread;
	});
	/** A newer link replaces one still being read. */
	private static final AtomicInteger GENERATION = new AtomicInteger();

	private SoundCloudOpener() {
	}

	public static boolean isSupported(@Nullable String url) {
		return SoundCloudLink.isSoundCloudUrl(url);
	}

	@MainThread
	public static void open(@NonNull Activity activity, @NonNull String url, @NonNull LinkTarget target) {
		SoundCloudLink.Ref ref = SoundCloudLink.parse(url);
		if (ref == null) return;
		if (ref.kind() == SoundCloudLink.Kind.TRACK) {
			// read in the player itself, like a YouTube video
			target.playUrl(ref.url());
			return;
		}
		int job = GENERATION.incrementAndGet();
		Context app = activity.getApplicationContext();
		OkHttpClient client = WebPage.client(AppGraph.of(app).okHttpClient());
		Handler main = new Handler(Looper.getMainLooper());
		ToastUtils.show(activity, R.string.link_soundcloud_reading);
		WORKER.execute(() -> {
			try {
				run(app, client, ref, target, main, job);
			} catch (SoundCloudReader.SoundCloudException e) {
				Log.w(TAG, "could not open " + url + ": " + e.problem, e);
				int message = e.problem == SoundCloudReader.SoundCloudException.Problem.NOT_FOUND
								? R.string.link_soundcloud_not_found : R.string.link_soundcloud_failed;
				main.post(() -> ToastUtils.show(app, message));
			} catch (IOException | RuntimeException e) {
				Log.w(TAG, "could not open " + url, e);
				main.post(() -> ToastUtils.show(app, R.string.link_external_network));
			}
		});
	}

	@WorkerThread
	private static void run(@NonNull Context context, @NonNull OkHttpClient client, @NonNull SoundCloudLink.Ref link,
	                        @NonNull LinkTarget target, @NonNull Handler main, int job)
					throws IOException, SoundCloudReader.SoundCloudException {
		SoundCloudLink.Ref ref = link;
		if (ref.kind() == SoundCloudLink.Kind.SHORT) {
			// the API doesn't take short links, follow it like a browser
			WebPage page = WebPage.get(client, ref.url(), Constant.DESKTOP_USER_AGENT);
			SoundCloudLink.Ref real = SoundCloudLink.parse(page.url.toString());
			if (real == null || real.kind() == SoundCloudLink.Kind.SHORT) {
				throw new SoundCloudReader.SoundCloudException(SoundCloudReader.SoundCloudException.Problem.NOT_FOUND,
								"short link led to " + page.url, null);
			}
			if (GENERATION.get() != job) return;
			if (real.kind() == SoundCloudLink.Kind.TRACK) {
				post(main, target, job, () -> target.playUrl(real.url()));
				return;
			}
			ref = real;
		}
		SoundCloudReader.Resolved resolved = SoundCloudReader.resolve(client, ref.url());
		if (GENERATION.get() != job) return;
		JsonObject json = resolved.json();
		switch (resolved.kind()) {
			case "track" -> {
				String page = SoundCloudReader.text(json, "permalink_url");
				if (page != null) post(main, target, job, () -> target.playUrl(page));
			}
			case "playlist", "system-playlist" -> {
				List<SoundCloudReader.Entry> entries = SoundCloudReader.setTracks(client, json, secretToken(ref));
				if (GENERATION.get() != job) return;
				if (entries.isEmpty()) {
					post(main, target, job, () -> ToastUtils.show(context, R.string.link_soundcloud_empty));
					return;
				}
				post(main, target, job, () -> {
					SoundCloudReader.Entry first = entries.get(0);
					target.playUrl(first.pageUrl());
					for (int i = 1; i < entries.size(); i++) {
						SoundCloudReader.Entry entry = entries.get(i);
						String id = PlusSources.idOf(entry.pageUrl());
						if (id == null) continue;
						target.queueUrl(id, entry.pageUrl(), entry.title(), entry.author(), entry.artworkUrl());
					}
					if (entries.size() > 1) {
						ToastUtils.show(context, context.getResources().getQuantityString(
										R.plurals.link_soundcloud_queued, entries.size() - 1, entries.size() - 1));
					}
				});
			}
			case "user" -> {
				String name = SoundCloudReader.text(json, "username");
				if (name != null) post(main, target, job, () -> target.search(name));
			}
			default -> post(main, target, job, () -> ToastUtils.show(context, R.string.link_soundcloud_failed));
		}
	}

	/** Private set's secret part (/s-…), needed to read its songs. */
	@Nullable
	private static String secretToken(@NonNull SoundCloudLink.Ref ref) {
		String[] parts = ref.url().split("/");
		String last = parts[parts.length - 1];
		return last.startsWith("s-") ? last : null;
	}

	/** Main thread, while the screen is alive and no newer link came in. */
	private static void post(@NonNull Handler main, @NonNull LinkTarget target, int job, @NonNull Runnable action) {
		main.post(() -> {
			if (GENERATION.get() != job || !target.isAlive()) return;
			action.run();
		});
	}
}
