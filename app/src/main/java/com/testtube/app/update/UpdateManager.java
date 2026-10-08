package com.testtube.app.update;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;
import androidx.core.content.pm.PackageInfoCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tencent.mmkv.MMKV;
import com.testtube.app.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * In-app updates from GitHub releases: check, download the APK with progress, hand it to the
 * installer. Android only installs it if it's signed with the same key, so a tampered APK
 * can't replace the app.
 */
public final class UpdateManager {
	private static final String LATEST_URL =
					"https://api.github.com/repos/techniixdotcom/testtube/releases/latest";
	private static final String DOWNLOAD_PREFIX =
					"https://github.com/techniixdotcom/testtube/releases/download/";
	private static final String KEY_AUTO = "update_auto_check";
	private static final String KEY_SNOOZED_TAG = "update_snoozed_tag";
	private static final String KEY_SNOOZED_UNTIL = "update_snoozed_until";
	private static final long START_DELAY_MS = 5_000L;
	private static final long SNOOZE_MS = 24L * 60 * 60 * 1000;
	private static final int NOTES_LIMIT = 600;
	private static final String UPDATE_DIR = "updates";

	private record Release(String tag, String notes, String apkUrl, long size, @Nullable String sha256) {
	}

	private final OkHttpClient client;
	private final MMKV store = MMKV.defaultMMKV();
	private final ExecutorService executor = Executors.newSingleThreadExecutor();
	private volatile Call activeDownload;

	public UpdateManager(@NonNull OkHttpClient client) {
		// separate client without the shared cache or response rewriting, so the APK comes through untouched
		OkHttpClient.Builder builder = client.newBuilder().cache(null);
		builder.networkInterceptors().clear();
		this.client = builder.build();
	}

	public boolean autoCheckEnabled() {
		return store.decodeBool(KEY_AUTO, true);
	}

	public void setAutoCheck(boolean enabled) {
		store.encode(KEY_AUTO, enabled);
	}

	/** On startup: checks after 5s and stays quiet unless there's an update. */
	public void checkOnStart(@NonNull Activity activity) {
		deleteOldDownloads(activity);
		if (!autoCheckEnabled()) return;
		new Handler(Looper.getMainLooper()).postDelayed(() -> {
			if (!activity.isFinishing() && !activity.isDestroyed() && autoCheckEnabled()) check(activity, false);
		}, START_DELAY_MS);
	}

	/** Manual check, also reports "up to date" and errors. */
	public void check(@NonNull Activity activity, boolean manual) {
		if (manual) Toast.makeText(activity, R.string.update_checking, Toast.LENGTH_SHORT).show();
		executor.execute(() -> {
			Release release;
			try {
				release = fetchLatest();
			} catch (IOException | RuntimeException e) {
				if (manual) runOnUi(activity, () ->
								Toast.makeText(activity, R.string.update_check_failed, Toast.LENGTH_LONG).show());
				return;
			}
			String current = currentVersion(activity);
			boolean newer = release != null && isNewer(release.tag(), current);
			runOnUi(activity, () -> {
				if (!newer) {
					if (manual) Toast.makeText(activity,
									activity.getString(R.string.update_none, current), Toast.LENGTH_LONG).show();
				} else if (manual || !isSnoozed(release.tag())) {
					showOffer(activity, release, current);
				}
			});
		});
	}

	private boolean isSnoozed(String tag) {
		return tag.equals(store.decodeString(KEY_SNOOZED_TAG, null))
						&& System.currentTimeMillis() < store.decodeLong(KEY_SNOOZED_UNTIL, 0L);
	}

	@Nullable
	private Release fetchLatest() throws IOException {
		Request request = new Request.Builder().url(LATEST_URL)
						.header("Accept", "application/vnd.github+json")
						.header("User-Agent", "TestTube")
						.build();
		try (Response response = client.newCall(request).execute()) {
			ResponseBody body = response.body();
			if (!response.isSuccessful() || body == null) throw new IOException("HTTP " + response.code());
			JsonObject json = JsonParser.parseReader(
							new InputStreamReader(body.byteStream(), StandardCharsets.UTF_8)).getAsJsonObject();
			if (json.has("draft") && json.get("draft").getAsBoolean()) return null;
			String tag = string(json, "tag_name");
			JsonArray assets = json.has("assets") && json.get("assets").isJsonArray()
							? json.getAsJsonArray("assets") : new JsonArray();
			for (JsonElement element : assets) {
				JsonObject asset = element.getAsJsonObject();
				String name = string(asset, "name");
				String url = string(asset, "browser_download_url");
				if (tag.isEmpty() || !name.endsWith(".apk") || !url.startsWith(DOWNLOAD_PREFIX)) continue;
				String digest = string(asset, "digest");
				String sha = digest.startsWith("sha256:") ? digest.substring(7).toLowerCase(Locale.ROOT) : null;
				long size = asset.has("size") ? asset.get("size").getAsLong() : -1L;
				return new Release(tag, string(json, "body").trim(), url, size, sha);
			}
			return null;
		}
	}

	private static String string(JsonObject json, String key) {
		JsonElement element = json.get(key);
		return element == null || element.isJsonNull() ? "" : element.getAsString();
	}

	private static String currentVersion(Activity activity) {
		try {
			String name = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionName;
			return name == null ? "" : name;
		} catch (PackageManager.NameNotFoundException e) {
			return "";
		}
	}

	/** Compares dotted numbers, so "v1.10.0" is newer than "v1.9.3". */
	static boolean isNewer(String remote, String local) {
		int[] a = numbers(remote);
		int[] b = numbers(local);
		for (int i = 0; i < Math.max(a.length, b.length); i++) {
			int x = i < a.length ? a[i] : 0;
			int y = i < b.length ? b[i] : 0;
			if (x != y) return x > y;
		}
		return false;
	}

	private static int[] numbers(String version) {
		String[] parts = version.replaceAll("[^0-9.]", "").split("\\.");
		int[] out = new int[parts.length];
		for (int i = 0; i < parts.length; i++) {
			try {
				out[i] = parts[i].isEmpty() ? 0 : Integer.parseInt(parts[i]);
			} catch (NumberFormatException e) {
				out[i] = 0;
			}
		}
		return out;
	}

	private void showOffer(Activity activity, Release release, String current) {
		if (activity.isFinishing() || activity.isDestroyed()) return;
		String notes = release.notes().length() > NOTES_LIMIT
						? release.notes().substring(0, NOTES_LIMIT) + "…" : release.notes();
		String message = activity.getString(R.string.update_message, release.tag(), current)
						+ (notes.isEmpty() ? "" : "\n\n" + notes);
		new MaterialAlertDialogBuilder(activity)
						.setTitle(R.string.update_title)
						.setMessage(message)
						.setPositiveButton(R.string.update_action, (d, w) -> startUpdate(activity, release))
						.setNegativeButton(R.string.update_later, (d, w) -> {
							store.encode(KEY_SNOOZED_TAG, release.tag());
							store.encode(KEY_SNOOZED_UNTIL, System.currentTimeMillis() + SNOOZE_MS);
						})
						.show();
	}

	private void startUpdate(Activity activity, Release release) {
		if (!activity.getPackageManager().canRequestPackageInstalls()) {
			Toast.makeText(activity, R.string.update_allow_install, Toast.LENGTH_LONG).show();
			activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
							Uri.parse("package:" + activity.getPackageName())));
			return;
		}
		View view = LayoutInflater.from(activity).inflate(R.layout.dialog_update_progress, null);
		ProgressBar bar = view.findViewById(R.id.update_progress_bar);
		TextView text = view.findViewById(R.id.update_progress_text);
		AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
						.setTitle(R.string.update_downloading)
						.setView(view)
						.setCancelable(false)
						.setNegativeButton(android.R.string.cancel, (d, w) -> {
							Call call = activeDownload;
							if (call != null) call.cancel();
						})
						.show();
		executor.execute(() -> {
			try {
				File apk = download(activity, release, (done, total) -> runOnUi(activity, () -> {
					bar.setIndeterminate(total <= 0);
					if (total > 0) bar.setProgress((int) (done * 1000 / total));
					text.setText(total > 0
									? activity.getString(R.string.update_progress, megabytes(done), megabytes(total))
									: megabytes(done));
				}));
				runOnUi(activity, () -> {
					dialog.dismiss();
					install(activity, apk);
				});
			} catch (IOException | RuntimeException e) {
				boolean cancelled = activeDownload != null && activeDownload.isCanceled();
				runOnUi(activity, () -> {
					dialog.dismiss();
					if (!cancelled) Toast.makeText(activity, R.string.update_failed, Toast.LENGTH_LONG).show();
				});
			} finally {
				activeDownload = null;
			}
		});
	}

	private interface Progress {
		void onProgress(long done, long total);
	}

	private File download(Activity activity, Release release, Progress progress) throws IOException {
		File dir = new File(activity.getCacheDir(), UPDATE_DIR);
		if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
		File part = new File(dir, "testtube-update.apk.part");
		File apk = new File(dir, "testtube-update.apk");
		Call call = client.newCall(new Request.Builder().url(release.apkUrl())
						.header("User-Agent", "TestTube").build());
		activeDownload = call;
		try (Response response = call.execute()) {
			ResponseBody body = response.body();
			if (!response.isSuccessful() || body == null) throw new IOException("HTTP " + response.code());
			if (!response.request().url().isHttps()) throw new IOException("download left https");
			long total = body.contentLength() > 0 ? body.contentLength() : release.size();
			MessageDigest digest = sha256();
			byte[] buffer = new byte[16 * 1024];
			long done = 0;
			long lastReport = 0;
			try (InputStream in = body.byteStream(); OutputStream out = new FileOutputStream(part)) {
				int read;
				while ((read = in.read(buffer)) != -1) {
					out.write(buffer, 0, read);
					digest.update(buffer, 0, read);
					done += read;
					long now = System.currentTimeMillis();
					if (now - lastReport > 100) {
						lastReport = now;
						progress.onProgress(done, total);
					}
				}
			}
			progress.onProgress(done, total);
			if (release.sha256() != null && !release.sha256().equals(hex(digest.digest()))) {
				throw new IOException("checksum mismatch");
			}
		}
		if (apk.exists() && !apk.delete()) throw new IOException("cannot replace old download");
		if (!part.renameTo(apk)) throw new IOException("cannot finish download");
		return apk;
	}

	private void install(Activity activity, File apk) {
		PackageManager pm = activity.getPackageManager();
		PackageInfo archive = pm.getPackageArchiveInfo(apk.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);
		try {
			PackageInfo installed = pm.getPackageInfo(activity.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
			if (archive == null || !activity.getPackageName().equals(archive.packageName)
							|| PackageInfoCompat.getLongVersionCode(archive)
							<= PackageInfoCompat.getLongVersionCode(installed)
							|| !sameSigner(archive, installed)) {
				Toast.makeText(activity, R.string.update_failed, Toast.LENGTH_LONG).show();
				return;
			}
		} catch (PackageManager.NameNotFoundException e) {
			return;
		}
		Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".provider", apk);
		Intent intent = new Intent(Intent.ACTION_VIEW)
						.setDataAndType(uri, "application/vnd.android.package-archive")
						.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
		activity.startActivity(intent);
	}

	/**
	 * Is the APK signed with the same key as the installed app? The installer checks too, this
	 * just rejects a foreign file before it even opens.
	 */
	private static boolean sameSigner(PackageInfo archive, PackageInfo installed) {
		// old Android versions can't read signing info here; the installer still checks
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return true;
		if (archive.signingInfo == null || installed.signingInfo == null) return false;
		Signature[] downloaded = archive.signingInfo.getApkContentsSigners();
		Signature[] current = installed.signingInfo.getApkContentsSigners();
		return downloaded != null && current != null && downloaded.length > 0
						&& new HashSet<>(Arrays.asList(downloaded)).equals(new HashSet<>(Arrays.asList(current)));
	}

	private void deleteOldDownloads(Activity activity) {
		File[] files = new File(activity.getCacheDir(), UPDATE_DIR).listFiles();
		if (files == null) return;
		executor.execute(() -> {
			for (File file : files) file.delete();
		});
	}

	private static MessageDigest sha256() throws IOException {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new IOException(e);
		}
	}

	private static String hex(byte[] bytes) {
		StringBuilder sb = new StringBuilder(bytes.length * 2);
		for (byte b : bytes) sb.append(String.format(Locale.ROOT, "%02x", b));
		return sb.toString();
	}

	private static String megabytes(long bytes) {
		return String.format(Locale.getDefault(), "%.1f MB", bytes / 1_048_576.0);
	}

	private static void runOnUi(Activity activity, Runnable task) {
		activity.runOnUiThread(task);
	}
}
