package com.testtube.app.util;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;

/**
 * Small stream and file helpers.
 */
public final class StreamIOUtils {
	private static final String TAG = "StreamIOUtils";
	private static final int BUFFER = 64 * 1024;
	private static final int URL_TIMEOUT_MS = 30_000;

	private StreamIOUtils() {
	}

	@Nullable
	public static String readInputStream(@NonNull InputStream inputStream) {
		try (inputStream) {
			return new String(readAll(inputStream), StandardCharsets.UTF_8);
		} catch (IOException e) {
			Log.e(TAG, "Error reading input stream", e);
			return null;
		}
	}

	@NonNull
	public static byte[] readInputStreamToBytes(@NonNull InputStream inputStream) {
		try (inputStream) {
			return readAll(inputStream);
		} catch (IOException e) {
			Log.e(TAG, "Error reading input stream to bytes", e);
			return new byte[0];
		}
	}

	@NonNull
	private static byte[] readAll(@NonNull InputStream in) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(BUFFER, in.available()));
		copy(in, out);
		return out.toByteArray();
	}

	public static void copy(@NonNull InputStream in, @NonNull OutputStream out) throws IOException {
		byte[] buffer = new byte[BUFFER];
		int read;
		while ((read = in.read(buffer)) != -1) {
			out.write(buffer, 0, read);
		}
	}

	public static void copyUrlToFile(@NonNull URL url, @NonNull File target) throws IOException {
		URLConnection connection = url.openConnection();
		connection.setConnectTimeout(URL_TIMEOUT_MS);
		connection.setReadTimeout(URL_TIMEOUT_MS);
		try (InputStream in = connection.getInputStream()) {
			File parent = target.getParentFile();
			if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
				throw new IOException("Unable to create " + parent);
			}
			try (OutputStream out = new FileOutputStream(target)) {
				copy(in, out);
			}
		} catch (IOException e) {
			deleteQuietly(target);
			throw e;
		} finally {
			if (connection instanceof HttpURLConnection http) http.disconnect();
		}
	}

	public static void copyFile(@NonNull File source, @NonNull File target) throws IOException {
		try (InputStream in = new FileInputStream(source);
		     OutputStream out = new FileOutputStream(target)) {
			copy(in, out);
		}
	}

	/**
	 * Moves a file, failing if the target already exists.
	 */
	public static void moveFile(@NonNull File source, @NonNull File target) throws IOException {
		if (target.exists()) throw new IOException("Target already exists: " + target);
		if (source.renameTo(target)) return;
		copyFile(source, target);
		if (!source.delete()) {
			deleteQuietly(target);
			throw new IOException("Unable to delete " + source + " after copying");
		}
	}

	public static void deleteQuietly(@Nullable File file) {
		if (file == null) return;
		try {
			//noinspection ResultOfMethodCallIgnored
			file.delete();
		} catch (SecurityException ignored) {
		}
	}
}
