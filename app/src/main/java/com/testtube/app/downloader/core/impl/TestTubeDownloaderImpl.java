package com.testtube.app.downloader.core.impl;

import android.content.Context;

import androidx.annotation.NonNull;

import com.testtube.app.downloader.core.DownloadTaskCallback;
import com.testtube.app.downloader.core.MediaMuxer;
import com.testtube.app.downloader.core.ProgressCallback;
import com.testtube.app.downloader.core.StreamDownloader;
import com.testtube.app.downloader.core.Task;
import com.testtube.app.downloader.core.TestTubeDownloader;
import com.testtube.app.util.StreamIOUtils;

import org.schabi.newpipe.extractor.stream.Stream;

import java.io.File;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntConsumer;

/** Runs download tasks: video and audio in parallel, subtitles and thumbnails on a small pool. */
public class TestTubeDownloaderImpl implements TestTubeDownloader {
	private final Context context;
	private final StreamDownloader streamDL;
	private final MediaMerger mediaMerger;
	// subtitles and thumbnails: small files, a few at a time
	private final ExecutorService executor = Executors.newFixedThreadPool(3, runnable -> {
		Thread thread = new Thread(runnable, "testtube-download-file");
		thread.setDaemon(true);
		return thread;
	});
	private final Map<String, Future<?>> sideTasks = new ConcurrentHashMap<>();
	private final Map<String, Task> tasks = new ConcurrentHashMap<>();
	private final Map<String, DownloadTaskCallback> callbacks = new ConcurrentHashMap<>();

	public TestTubeDownloaderImpl(Context context, StreamDownloader streamDL) {
		this(context, streamDL, MediaMuxer::merge);
	}

	TestTubeDownloaderImpl(Context context, StreamDownloader streamDL, MediaMerger mediaMerger) {
		this.context = context;
		this.streamDL = streamDL;
		this.mediaMerger = mediaMerger;
	}

	@Override
	public void setCallback(@NonNull String videoId, DownloadTaskCallback callback) {
		if (callback != null) callbacks.put(videoId, callback);
		else callbacks.remove(videoId);
	}

	@Override
	public void download(@NonNull Task task) {
		tasks.put(task.videoId(), task);
		if (task.subtitle() != null) {
			runSideTask(task, () -> StreamIOUtils.copyUrlToFile(new URL(task.subtitle().getContent()), outputFile(task)));
		} else if (task.thumbnail() != null) {
			runSideTask(task, () -> StreamIOUtils.copyUrlToFile(new URL(task.thumbnail()), outputFile(task)));
		} else {
			downloadMedia(task);
		}
	}

	private void runSideTask(Task task, ThrowingRunnable work) {
		Future<?> future = executor.submit(() -> {
			try {
				work.run();
				if (tasks.containsKey(task.videoId())) {
					complete(task.videoId(), outputFile(task));
				} else {
					// cancelled in the meantime
					StreamIOUtils.deleteQuietly(outputFile(task));
				}
			} catch (Exception e) {
				handleError(task, e);
			} finally {
				sideTasks.remove(task.videoId());
			}
		});
		sideTasks.put(task.videoId(), future);
	}

	/** Video and audio are fetched side by side into temp files and muxed once both are done. */
	private void downloadMedia(Task task) {
		String videoId = task.videoId();
		int threads = task.threadCount();
		File videoFile = tempFile(task, "_v");
		File audioFile = tempFile(task, "_a");
		File out = outputFile(task);
		long videoSize = contentLength(task.video());
		long audioSize = contentLength(task.audio());

		CombinedProgress combined = new CombinedProgress(videoSize, audioSize,
						(percent, downloaded, total) -> progress(videoId, percent, downloaded, total));

		CompletableFuture<File> video = null;
		if (task.video() != null) {
			video = streamDL.download(task.video().getContent(), videoFile, progressAdapter(percent -> {
				if (audioSize > 0) combined.updateVideo(percent);
				else progress(videoId, percent, (long) (videoSize * (percent / 100.0)), videoSize);
			}), threads);
		}

		CompletableFuture<File> audio = null;
		if (task.audio() != null) {
			audio = streamDL.download(task.audio().getContent(), audioFile, progressAdapter(percent -> {
				if (videoSize > 0) combined.updateAudio(percent);
				else progress(videoId, percent, (long) (audioSize * (percent / 100.0)), audioSize);
			}), threads);
		}

		boolean needsMerge = video != null && audio != null;
		CompletableFuture<?> downloads = needsMerge
						? CompletableFuture.allOf(video, audio)
						: (video != null ? video : audio);
		boolean hasVideo = video != null;

		downloads.thenRun(() -> {
			try {
				if (!tasks.containsKey(videoId)) return;
				if (needsMerge) {
					notify(videoId, DownloadTaskCallback::onMerge);
					File merged = tempFile(task, "_m");
					try {
						mediaMerger.merge(videoFile, audioFile, merged);
						StreamIOUtils.moveFile(merged, out);
					} finally {
						StreamIOUtils.deleteQuietly(videoFile);
						StreamIOUtils.deleteQuietly(audioFile);
						StreamIOUtils.deleteQuietly(merged);
					}
				} else {
					StreamIOUtils.moveFile(hasVideo ? videoFile : audioFile, out);
				}
				complete(videoId, out);
			} catch (Exception e) {
				throw new CompletionException(e);
			}
		}).exceptionally(e -> handleError(task, e));
	}

	@Override
	public boolean pause(@NonNull String videoId) {
		Task task = tasks.get(videoId);
		if (task == null) return false;
		if (task.video() != null) streamDL.pause(task.video().getContent());
		if (task.audio() != null) streamDL.pause(task.audio().getContent());
		return task.video() != null || task.audio() != null;
	}

	@Override
	public boolean resume(@NonNull String videoId) {
		Task task = tasks.get(videoId);
		if (task == null) return false;
		if (task.video() != null) streamDL.resume(task.video().getContent());
		if (task.audio() != null) streamDL.resume(task.audio().getContent());
		return task.video() != null || task.audio() != null;
	}

	@Override
	public void cancel(@NonNull String videoId) {
		Task task = tasks.remove(videoId);
		try {
			if (task == null) return;
			if (task.video() != null) streamDL.cancel(task.video().getContent());
			if (task.audio() != null) streamDL.cancel(task.audio().getContent());
			Future<?> side = sideTasks.remove(videoId);
			if (side != null) side.cancel(true);
			notify(videoId, DownloadTaskCallback::onCancel);
			clean(task);
		} finally {
			clearCallback(videoId);
		}
	}

	private ProgressCallback progressAdapter(IntConsumer action) {
		return new ProgressCallback() {
			@Override
			public void onProgress(int progress) {
				action.accept(progress);
			}

			@Override
			public void onComplete(File file) {
			}

			@Override
			public void onError(Exception e) {
			}

			@Override
			public void onCancel() {
			}
		};
	}

	private Void handleError(Task task, Throwable e) {
		Throwable cause = e instanceof CompletionException ? e.getCause() : e;
		Exception error = cause instanceof Exception ? (Exception) cause : new Exception(cause);
		try {
			if (tasks.containsKey(task.videoId())) {
				notify(task.videoId(), callback -> callback.onError(error));
				clean(tasks.remove(task.videoId()));
			}
		} finally {
			clearCallback(task.videoId());
		}
		return null;
	}

	private void complete(String videoId, File file) {
		try {
			if (tasks.remove(videoId) != null) notify(videoId, callback -> callback.onComplete(file));
		} finally {
			clearCallback(videoId);
		}
	}

	private void progress(String videoId, int percent, long downloaded, long total) {
		notify(videoId, callback -> callback.onProgress(percent, downloaded, total));
	}

	private void notify(String videoId, CallbackAction action) {
		DownloadTaskCallback callback = callbacks.get(videoId);
		if (callback != null) action.run(callback);
	}

	private void clearCallback(@NonNull String videoId) {
		callbacks.remove(videoId);
	}

	private void clean(Task task) {
		if (task == null) return;
		if (task.video() != null) StreamIOUtils.deleteQuietly(tempFile(task, "_v"));
		if (task.audio() != null) StreamIOUtils.deleteQuietly(tempFile(task, "_a"));
		if (task.video() != null && task.audio() != null) StreamIOUtils.deleteQuietly(tempFile(task, "_m"));
		StreamIOUtils.deleteQuietly(outputFile(task));
	}

	private File tempFile(Task task, String suffix) {
		return new File(context.getCacheDir(), taskFileKey(task) + suffix + ".tmp");
	}

	private File outputFile(@NonNull Task task) {
		if (task.subtitle() != null) {
			return new File(task.desDir(), task.fileName() + "." + task.subtitle().getExtension());
		}
		if (task.thumbnail() != null) {
			return new File(task.desDir(), task.fileName() + ".jpg");
		}
		return new File(task.desDir(), task.fileName() + (task.video() != null ? ".mp4" : ".m4a"));
	}

	private String taskFileKey(@NonNull Task task) {
		return task.videoId().replaceAll("[\\\\/:*?\"<>|]", "_");
	}

	private long contentLength(Stream stream) {
		try {
			return stream.getItagItem().getContentLength();
		} catch (Exception e) {
			return 0;
		}
	}

	interface ThrowingRunnable {
		void run() throws Exception;
	}

	interface MediaMerger {
		void merge(@NonNull File videoFile, @NonNull File audioFile, @NonNull File outputFile) throws Exception;
	}

	interface CallbackAction {
		void run(DownloadTaskCallback cb);
	}

	interface ProgressUpdateListener {
		void onUpdate(int progress, long downloaded, long total);
	}

	/** Combines the separate video and audio progress into one figure, weighted by size. */
	private static class CombinedProgress {
		final long videoBytes, audioBytes, totalBytes;
		final ProgressUpdateListener listener;
		int videoPercent, audioPercent;

		CombinedProgress(long videoBytes, long audioBytes, ProgressUpdateListener listener) {
			this.videoBytes = Math.max(videoBytes, 1);
			this.audioBytes = Math.max(audioBytes, 1);
			this.totalBytes = this.videoBytes + this.audioBytes;
			this.listener = listener;
		}

		synchronized void updateVideo(int percent) {
			videoPercent = percent;
			publish();
		}

		synchronized void updateAudio(int percent) {
			audioPercent = percent;
			publish();
		}

		void publish() {
			int percent = (int) ((videoPercent * videoBytes + audioPercent * audioBytes) / totalBytes);
			long downloaded = (long) (videoBytes * (videoPercent / 100.0) + audioBytes * (audioPercent / 100.0));
			listener.onUpdate(percent, downloaded, totalBytes);
		}
	}
}
