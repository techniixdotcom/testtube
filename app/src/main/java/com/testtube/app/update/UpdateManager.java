package com.testtube.app.update;

import android.app.Activity;
import android.content.ActivityNotFoundException;
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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tencent.mmkv.MMKV;
import com.testtube.app.R;
import com.testtube.app.ui.Dialogs;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * In-app updates from GitHub releases: checks, downloads the APK with progress and hands it to
 * the installer. Android only installs it when it is signed with the same key.
 */
public final class UpdateManager {
	private static final String LATEST_URL =
					"https://api.github.com/repos/techniixdotcom/testtube/releases/latest";
	/** Newest releases first, pre-releases included ("latest" leaves them out). */
	private static final String RELEASES_URL =
					"https://api.github.com/repos/techniixdotcom/testtube/releases?per_page=20";
	/** "v2.1.1" and an optional pre-release part such as "-beta2". */
	/** A letter prefix is allowed, with or without a separator: "2.1.1", "v2.1.1", "v.2.1.1", "V 2.1.1". */
	private static final Pattern VERSION = Pattern.compile("(?i)[a-z]*[ ._-]?(\\d+(?:\\.\\d+)*)(.*)");
	private static final Pattern TRAILING_NUMBER = Pattern.compile("(\\d+)\\D*$");
	private static final String DOWNLOAD_PREFIX =
					"https://github.com/techniixdotcom/testtube/releases/download/";
	private static final String KEY_AUTO = "update_auto_check";
	private static final String KEY_PRERELEASES = "update_prereleases";
	private static final String KEY_SNOOZED_TAG = "update_snoozed_tag";
	private static final String KEY_SNOOZED_UNTIL = "update_snoozed_until";
	private static final long START_DELAY_MS = 5_000L;
	private static final long SNOOZE_MS = 24L * 60 * 60 * 1000;
	private static final int NOTES_LIMIT = 600;
	private static final String UPDATE_DIR = "updates";

	private record Release(String tag, String notes, String apkUrl, long size, @Nullable String sha256,
	                       boolean prerelease) {
	}

	private final OkHttpClient client;
	private final MMKV store = MMKV.defaultMMKV();
	private final ExecutorService executor = Executors.newSingleThreadExecutor();
	private volatile Call activeDownload;

	public UpdateManager(@NonNull OkHttpClient client) {
		// Own client: no shared HTTP cache, no response rewriting
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

	/** Pre-releases (test versions) are offered too. Off by default. */
	public boolean prereleasesEnabled() {
		return store.decodeBool(KEY_PRERELEASES, false);
	}

	public void setPrereleases(boolean enabled) {
		store.encode(KEY_PRERELEASES, enabled);
	}

	/** Quiet check 5 s after startup; only shows up when there is an update. */
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
		if (!prereleasesEnabled()) {
			JsonElement json = get(LATEST_URL);
			return json.isJsonObject() ? parseRelease(json.getAsJsonObject()) : null;
		}
		JsonElement json = get(RELEASES_URL);
		if (!json.isJsonArray()) return null;
		Release newest = null;
		for (JsonElement element : json.getAsJsonArray()) {
			if (!element.isJsonObject()) continue;
			Release release = parseRelease(element.getAsJsonObject());
			if (release != null && (newest == null || isNewer(release.tag(), newest.tag()))) newest = release;
		}
		return newest;
	}

	@NonNull
	private JsonElement get(@NonNull String url) throws IOException {
		Request request = new Request.Builder().url(url)
						.header("Accept", "application/vnd.github+json")
						.header("User-Agent", "TestTube")
						.build();
		try (Response response = client.newCall(request).execute()) {
			ResponseBody body = response.body();
			if (!response.isSuccessful() || body == null) throw new IOException("HTTP " + response.code());
			return JsonParser.parseReader(new InputStreamReader(body.byteStream(), StandardCharsets.UTF_8));
		}
	}

	@Nullable
	private static Release parseRelease(@NonNull JsonObject json) {
		if (json.has("draft") && json.get("draft").getAsBoolean()) return null;
		boolean prerelease = json.has("prerelease") && json.get("prerelease").getAsBoolean();
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
			return new Release(tag, string(json, "body").trim(), url, size, sha, prerelease);
		}
		return null;
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

	/**
	 * Compares versions as numbers, so "v1.10.0" is newer than "v1.9.3". A pre-release comes before
	 * its release: "v2.1.1-beta1" < "v2.1.1-beta2" < "v2.1.1".
	 */
	static boolean isNewer(String remote, String local) {
		Matcher a = VERSION.matcher(remote.trim());
		Matcher b = VERSION.matcher(local.trim());
		if (!a.matches() || !b.matches()) return false;
		int[] x = numbers(a.group(1));
		int[] y = numbers(b.group(1));
		for (int i = 0; i < Math.max(x.length, y.length); i++) {
			int p = i < x.length ? x[i] : 0;
			int q = i < y.length ? y[i] : 0;
			if (p != q) return p > q;
		}
		String remoteTag = a.group(2).trim();
		String localTag = b.group(2).trim();
		if (remoteTag.isEmpty() || localTag.isEmpty()) return remoteTag.isEmpty() && !localTag.isEmpty();
		Matcher remoteNumber = TRAILING_NUMBER.matcher(remoteTag);
		Matcher localNumber = TRAILING_NUMBER.matcher(localTag);
		boolean remoteHasNumber = remoteNumber.find();
		boolean localHasNumber = localNumber.find();
		String remoteName = remoteHasNumber ? remoteTag.substring(0, remoteNumber.start()) : remoteTag;
		String localName = localHasNumber ? localTag.substring(0, localNumber.start()) : localTag;
		int byName = remoteName.compareToIgnoreCase(localName);
		if (byName != 0) return byName > 0;
		long remoteCount = remoteHasNumber ? parseLong(remoteNumber.group(1)) : 0;
		long localCount = localHasNumber ? parseLong(localNumber.group(1)) : 0;
		return remoteCount > localCount;
	}

	private static long parseLong(@NonNull String digits) {
		try {
			return Long.parseLong(digits);
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	private static int[] numbers(String core) {
		String[] parts = core.split("\\.");
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
		Dialogs.builder(activity)
						.setTitle(release.prerelease() ? R.string.update_title_prerelease : R.string.update_title)
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
			try {
				activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
								Uri.parse("package:" + activity.getPackageName())));
			} catch (ActivityNotFoundException e) {
				// Some phones hide this screen; the toast above says what to allow
			}
			return;
		}
		View view = LayoutInflater.from(activity).inflate(R.layout.dialog_update_progress, null);
		ProgressBar bar = view.findViewById(R.id.update_progress_bar);
		TextView text = view.findViewById(R.id.update_progress_text);
		AlertDialog dialog = Dialogs.builder(activity)
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
		// GitHub works out a SHA-256 for every file uploaded to a release, by itself. Without one
		// there's nothing to check the download against, so no update at all.
		String expected = release.sha256();
		if (expected == null || !expected.matches("[0-9a-f]{64}")) throw new IOException("release has no SHA-256");
		Call call = client.newCall(new Request.Builder().url(release.apkUrl())
						.header("User-Agent", "TestTube").build());
		activeDownload = call;
		try (Response response = call.execute()) {
			ResponseBody body = response.body();
			if (!response.isSuccessful() || body == null) throw new IOException("HTTP " + response.code());
			if (!response.request().url().isHttps()) throw new IOException("download left https");
			long total = body.contentLength() > 0 ? body.contentLength() : release.size();
			if (release.size() > 0 && body.contentLength() > 0 && body.contentLength() != release.size()) {
				throw new IOException("size mismatch");
			}
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
					// Never write more than the release said it holds
					if (release.size() > 0 && done > release.size()) throw new IOException("download too large");
					long now = System.currentTimeMillis();
					if (now - lastReport > 100) {
						lastReport = now;
						progress.onProgress(done, total);
					}
				}
			}
			progress.onProgress(done, total);
			if (!expected.equals(hex(digest.digest()))) throw new IOException("checksum mismatch");
		} catch (IOException | RuntimeException e) {
			// A half or wrong download must never be the thing that gets installed
			//noinspection ResultOfMethodCallIgnored
			part.delete();
			throw e;
		}
		if (apk.exists() && !apk.delete()) throw new IOException("cannot replace old download");
		if (!part.renameTo(apk)) throw new IOException("cannot finish download");
		return apk;
	}

	private void install(Activity activity, File apk) {
		PackageManager pm = activity.getPackageManager();
		int flags = signatureFlags();
		PackageInfo archive = pm.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
		try {
			PackageInfo installed = pm.getPackageInfo(activity.getPackageName(), flags);
			if (archive == null || !activity.getPackageName().equals(archive.packageName)
							|| PackageInfoCompat.getLongVersionCode(archive)
							<= PackageInfoCompat.getLongVersionCode(installed)
							|| !sameSigner(archive, installed)) {
				//noinspection ResultOfMethodCallIgnored
				apk.delete();
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
		try {
			activity.startActivity(intent);
		} catch (ActivityNotFoundException e) {
			Toast.makeText(activity, R.string.update_failed, Toast.LENGTH_LONG).show();
		}
	}

	@SuppressWarnings("deprecation")
	private static int signatureFlags() {
		return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
						? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
	}

	/**
	 * Whether the APK is signed with exactly the key of the app that's installed, which is your
	 * release key: an APK built or re-signed by anyone else is thrown away here, before Android's
	 * installer even comes up. Compared by the SHA-256 of each signing certificate. The installer
	 * checks the key again on its own.
	 */
	private static boolean sameSigner(PackageInfo archive, PackageInfo installed) {
		Set<String> downloaded = certificateHashes(archive);
		return !downloaded.isEmpty() && downloaded.equals(certificateHashes(installed));
	}

	@SuppressWarnings("deprecation")
	@NonNull
	private static Set<String> certificateHashes(@NonNull PackageInfo info) {
		Signature[] signers;
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
			// Several signers only for APKs signed by more than one key at once; a rotated key's
			// history isn't accepted, the current one has to match
			signers = info.signingInfo != null ? info.signingInfo.getApkContentsSigners() : null;
		} else {
			signers = info.signatures;
		}
		Set<String> hashes = new HashSet<>();
		if (signers == null) return hashes;
		try {
			MessageDigest digest = sha256();
			for (Signature signer : signers) {
				if (signer != null) hashes.add(hex(digest.digest(signer.toByteArray())));
			}
		} catch (IOException e) {
			hashes.clear();
		}
		return hashes;
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
		} catch (NoSuchAlgorithmException e) {
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
