package com.testtube.app.downloader.core.impl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.testtube.app.downloader.core.ProgressCallback;
import com.testtube.app.downloader.core.StreamDownloader;
import com.tencent.mmkv.MMKV;
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.BitSet;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;


import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Streams a file by chunk and keeps resume state in MMKV.
 */
public class StreamDownloaderImpl implements StreamDownloader {
	private final OkHttpClient client;
	private final MMKV mmkv;
	private final ThreadPoolExecutor executor;
	private final Map<String, TaskContext> tasks = new ConcurrentHashMap<>();
	private static final long CHUNK_BYTES = 512L * 1024L;
	private static final int MAX_THREADS = 8;
	private static final char[] HEX = "0123456789abcdef".toCharArray();
	/**
	 * Resume state key. "dl2_" because the chunk layout changed; resume data stored under the
	 * old "dl_" keys is no longer valid and is removed.
	 */
	private static final String KEY_PREFIX = "dl2_";
	private static final String LEGACY_KEY_PREFIX = "dl_";

	public StreamDownloaderImpl(OkHttpClient client, MMKV mmkv) {
		this.client = client.newBuilder()
						.cache(null)
						.dispatcher(createDispatcher())
						.callTimeout(0L, TimeUnit.MILLISECONDS)
						.connectTimeout(20L, TimeUnit.SECONDS)
						.writeTimeout(30L, TimeUnit.SECONDS)
						.readTimeout(60L, TimeUnit.SECONDS)
						.build();
		this.mmkv = mmkv;
		this.executor = new ThreadPoolExecutor(8, 8, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), r -> new Thread(r, "dl-node"));
		this.executor.allowCoreThreadTimeOut(true);
		this.executor.execute(this::removeLegacyResumeData);
	}

	private void removeLegacyResumeData() {
		String[] keys = mmkv.allKeys();
		if (keys == null) return;
		for (String key : keys) {
			if (key.startsWith(LEGACY_KEY_PREFIX)) mmkv.removeValueForKey(key);
		}
	}

	private static Dispatcher createDispatcher() {
		Dispatcher dispatcher = new Dispatcher();
		dispatcher.setMaxRequests(24);
		dispatcher.setMaxRequestsPerHost(12);
		return dispatcher;
	}

	private static long chunkLength(int idx, int totalChunks, long partSize, long totalLen) {
		long start = idx * partSize;
		long end = (idx == totalChunks - 1 && totalLen > 0) ? totalLen - 1 : (start + partSize - 1);
		if (totalLen <= 0 || end < start) return 0;
		return end - start + 1;
	}

	private static void maybeReportProgress(@NonNull TaskContext task, long totalLen) {
		if (task.callback == null || totalLen <= 0) return;
		long downloaded = Math.min(totalLen, Math.max(0, task.downloadedBytes.get()));
		int progress = (int) Math.min(99, (downloaded * 100) / totalLen);
		synchronized (task.progressLock) {
			int prev;
			do {
				prev = task.lastProgress.get();
				if (progress <= prev) return;
			} while (!task.lastProgress.compareAndSet(prev, progress));
			task.callback.onProgress(progress);
		}
	}

	@Override
	public CompletableFuture<File> download(@NonNull String url, @NonNull File out, @Nullable ProgressCallback callback, int threads) {
		CompletableFuture<File> future = new CompletableFuture<>();
		TaskContext task = new TaskContext(
						url,
						out,
						KEY_PREFIX + md5(url),
						Math.max(1, Math.min(MAX_THREADS, threads)),
						future,
						callback,
						new AtomicBoolean(),
						new AtomicBoolean(),
						new AtomicInteger(),
						new AtomicLong(),
						new AtomicInteger(-1));
		tasks.put(url, task);
		startTask(task);
		return future;
	}

	@NonNull
	private static Request.Builder streamRequest(@NonNull String url) {
		// Downloads go straight to their file; storing them in the HTTP cache too would double
		// the space they take.
		Request.Builder builder = new Request.Builder().url(url).header("Cache-Control", "no-store");
		// googlevideo rejects stream requests whose user agent does not match the client that
		// produced the URL.
		if (YoutubeParsingHelper.isVisionOsStreamingUrl(url)) {
			builder.header("User-Agent", YoutubeParsingHelper.getVisionOsUserAgent(null));
		}
		return builder;
	}

	private void runTask(TaskContext task) {
		RandomAccessFile raf = null;
		try {
			// 1. fetch metadata
			final long total;
			final boolean range;
			try (Response head = client.newCall(streamRequest(task.url).head().build()).execute()) {
				if (!head.isSuccessful()) throw new IOException("HEAD " + head.code());
				total = Long.parseLong(head.header("Content-Length", "-1"));
				range = head.code() == 206 || "bytes".equalsIgnoreCase(head.header("Accept-Ranges"));
			}

			// 2. calculate chunk count
			int chunks;
			if (total <= 0 || !range) chunks = 1;
			else {
				// One chunk per 512 KB, between 1 and 128 chunks.
				chunks = (int) Math.max(1L, Math.min(128L, total / CHUNK_BYTES));
			}
			long part = total > 0 ? total / chunks : total;

			// 3. resume or initialize
			byte[] saved = mmkv.decodeBytes(task.key);
			BitSet bits = (range && saved != null) ? BitSet.valueOf(saved) : new BitSet();
			task.done.set(bits.cardinality());
			if (total > 0) {
				long initialDownloaded = IntStream.range(0, chunks)
								.filter(bits::get)
								.mapToLong(i -> chunkLength(i, chunks, part, total))
								.sum();
				task.downloadedBytes.set(initialDownloaded);
				maybeReportProgress(task, total);
			}
			raf = new RandomAccessFile(task.out, "rw");
			if (total > 0) raf.setLength(total);
			else raf.setLength(0);

			// 4. submit task
			if (task.done.get() < chunks) {
				RandomAccessFile finalRaf = raf;
				// Each download uses its own number of connections: that many workers take the
				// unfinished chunks one by one.
				ConcurrentLinkedQueue<Integer> pending = new ConcurrentLinkedQueue<>();
				IntStream.range(0, chunks).filter(i -> !bits.get(i)).forEach(pending::add);
				int workers = Math.min(task.threads, pending.size());
				CompletableFuture<?>[] running = new CompletableFuture<?>[workers];
				for (int w = 0; w < workers; w++) {
					running[w] = CompletableFuture.runAsync(() -> {
						Integer next;
						while ((next = pending.poll()) != null) {
							if (task.isInactive()) return;
							downloadChunk(task, next, chunks, part, total, range, finalRaf, bits);
						}
					}, executor);
				}
				CompletableFuture.allOf(running).join();
			}

			// 5. clean up
			if (!task.isInactive()) {
				mmkv.removeValueForKey(task.key);
				tasks.remove(task.url);
				task.future.complete(task.out);
				if (task.callback != null) task.callback.onComplete(task.out);
			}
		} catch (Exception e) {
			if (!task.isInactive()) {
				tasks.remove(task.url);
				task.future.completeExceptionally(e);
				if (task.callback != null)
					task.callback.onError(e instanceof RuntimeException && e.getCause() instanceof Exception ? (Exception) e.getCause() : e);
			}
		} finally {
			try {
				if (raf != null) raf.close();
			} catch (IOException ignored) {
			}
		}
	}

	private void downloadChunk(TaskContext task, int idx, int totalChunks, long partSize, long totalLen, boolean rangeSupported, RandomAccessFile raf, BitSet bits) {
		if (task.isInactive()) return;
		long start = idx * partSize;
		long end = (idx == totalChunks - 1 && totalLen > 0) ? totalLen - 1 : (start + partSize - 1);
		String range = rangeSupported && totalLen > 0 ? "bytes=" + start + "-" + end : null;

		Request.Builder rb = streamRequest(task.url);
		if (range != null) rb.header("Range", range);

		try (Response resp = client.newCall(rb.build()).execute()) {
			if (!resp.isSuccessful()) throw new IOException("GET " + resp.code());
			try (InputStream is = resp.body().byteStream()) {
				byte[] buf = new byte[8192];
				int read;
				long offset = start;
				while ((read = is.read(buf)) != -1) {
					if (task.isInactive()) throw new IOException("Stop");
					synchronized (task.lock) {
						raf.seek(offset);
						raf.write(buf, 0, read);
					}
					if (totalLen > 0) {
						task.downloadedBytes.addAndGet(read);
						maybeReportProgress(task, totalLen);
					}
					offset += read;
				}
				if (range != null) synchronized (task.lock) {
					bits.set(idx);
					mmkv.encode(task.key, bits.toByteArray());
				}
			}
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public void pause(@NonNull String url) {
		Optional.ofNullable(tasks.get(url)).ifPresent(t -> t.paused.set(true));
	}

	@Override
	public void cancel(@NonNull String url) {
		TaskContext t = tasks.remove(url);
		if (t != null) {
			t.cancelled.set(true);
			t.future.cancel(true);
			mmkv.removeValueForKey(t.key);
			if (t.callback != null) t.callback.onCancel();
		}
	}

	@Override
	public void resume(@NonNull String url) {
		TaskContext t = tasks.get(url);
		if (t != null && t.paused.compareAndSet(true, false)) startTask(t);
	}

	private void startTask(@NonNull TaskContext task) {
		Thread thread = new Thread(() -> runTask(task), "testtube-download");
		thread.setDaemon(true);
		thread.start();
	}

	private static String md5(String s) {
		try {
			byte[] b = MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8));
			char[] out = new char[b.length * 2];
			for (int i = 0; i < b.length; i++) {
				out[i * 2] = HEX[(b[i] >> 4) & 0xF];
				out[i * 2 + 1] = HEX[b[i] & 0xF];
			}
			return new String(out);
		} catch (Exception e) {
			return String.valueOf(s.hashCode());
		}
	}

	private static final class TaskContext {
		final String url;
		final File out;
		final String key;
		final int threads;
		final CompletableFuture<File> future;
		final ProgressCallback callback;
		final Object lock = new Object();
		final Object progressLock = new Object();
		final AtomicBoolean paused;
		final AtomicBoolean cancelled;
		final AtomicInteger done;
		final AtomicLong downloadedBytes;
		final AtomicInteger lastProgress;

		TaskContext(String url, File out, String key, int threads, CompletableFuture<File> future,
		            ProgressCallback callback, AtomicBoolean paused, AtomicBoolean cancelled,
		            AtomicInteger done, AtomicLong downloadedBytes, AtomicInteger lastProgress) {
			this.url = url;
			this.out = out;
			this.key = key;
			this.threads = threads;
			this.future = future;
			this.callback = callback;
			this.paused = paused;
			this.cancelled = cancelled;
			this.done = done;
			this.downloadedBytes = downloadedBytes;
			this.lastProgress = lastProgress;
		}

		boolean isInactive() {
			return paused.get() || cancelled.get() || future.isCancelled();
		}
	}


}
