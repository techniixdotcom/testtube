package com.testtube.app.links;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.verify.domain.DomainVerificationManager;
import android.content.pm.verify.domain.DomainVerificationUserState;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.annotation.ChecksSdkIntAtLeast;
import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.tencent.mmkv.MMKV;
import com.testtube.app.R;
import com.testtube.app.ui.Dialogs;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Whether other apps' links open here. Since Android 12 only a website can approve an app for
 * its links, and youtube.com only approves YouTube. Everyone else needs the user to allow it in
 * "Open by default", so we ask once, say what to tap, and open that screen.
 */
public final class LinkDefaults {
	/** Links already asked about; ask again only when an update adds new ones. */
	private static final String KEY_ASKED_HOSTS = "link_defaults_asked_hosts";

	/** How many of our links Android opens here. */
	public record Status(int approved, int total) {
		public boolean allApproved() {
			return approved >= total;
		}
	}

	private LinkDefaults() {
	}

	/** Android has the "Open by default" link picker (12+). */
	@ChecksSdkIntAtLeast(api = Build.VERSION_CODES.S)
	public static boolean supported() {
		return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
	}

	/** Approved link count; null before 12 where links just work. */
	@Nullable
	public static Status status(@NonNull Context context) {
		if (!supported()) return null;
		Map<String, Integer> hosts = hosts(context);
		if (hosts == null || hosts.isEmpty()) return null;
		int approved = 0;
		boolean allowed = linkHandlingAllowed(context);
		for (int state : hosts.values()) {
			if (allowed && state != DomainVerificationUserState.DOMAIN_STATE_NONE) approved++;
		}
		return new Status(approved, hosts.size());
	}

	/**
	 * Once after start: if some links don't open here and weren't asked about, explain and offer
	 * to open the screen.
	 */
	@MainThread
	public static void maybeAsk(@NonNull Activity activity) {
		if (!supported() || activity.isFinishing() || activity.isDestroyed()) return;
		Set<String> missing = unapprovedHosts(activity);
		if (missing == null || missing.isEmpty()) return;
		MMKV store = MMKV.defaultMMKV();
		Set<String> asked = store.decodeStringSet(KEY_ASKED_HOSTS, new HashSet<>());
		if (asked != null && asked.containsAll(missing)) return;
		Set<String> nowAsked = new HashSet<>(missing);
		if (asked != null) nowAsked.addAll(asked);
		store.encode(KEY_ASKED_HOSTS, nowAsked);
		Dialogs.builder(activity)
						.setTitle(activity.getString(R.string.links_prompt_title, activity.getString(R.string.app_name)))
						.setMessage(R.string.links_prompt_message)
						.setPositiveButton(R.string.links_prompt_open, (dialog, which) -> openSettings(activity))
						.setNegativeButton(R.string.links_prompt_later, null)
						.show();
	}

	/** "Open by default" for this app, or app info if that's missing. */
	@MainThread
	public static void openSettings(@NonNull Activity activity) {
		Uri app = Uri.parse("package:" + activity.getPackageName());
		if (supported()) {
			try {
				activity.startActivity(new Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, app));
				return;
			} catch (ActivityNotFoundException | SecurityException e) {
				// some phones skip that screen, app info has the same setting
			}
		}
		try {
			activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, app));
		} catch (ActivityNotFoundException | SecurityException ignored) {
			// no settings app
		}
	}

	@RequiresApi(Build.VERSION_CODES.S)
	@Nullable
	private static Set<String> unapprovedHosts(@NonNull Context context) {
		Map<String, Integer> hosts = hosts(context);
		if (hosts == null) return null;
		boolean allowed = linkHandlingAllowed(context);
		Set<String> missing = new HashSet<>();
		for (Map.Entry<String, Integer> host : hosts.entrySet()) {
			if (!allowed || host.getValue() == DomainVerificationUserState.DOMAIN_STATE_NONE) missing.add(host.getKey());
		}
		return missing;
	}

	@RequiresApi(Build.VERSION_CODES.S)
	@Nullable
	private static Map<String, Integer> hosts(@NonNull Context context) {
		DomainVerificationUserState state = userState(context);
		return state != null ? state.getHostToStateMap() : null;
	}

	/** The "Open supported links" switch: off means nothing opens here, whatever is ticked. */
	@RequiresApi(Build.VERSION_CODES.S)
	private static boolean linkHandlingAllowed(@NonNull Context context) {
		DomainVerificationUserState state = userState(context);
		return state != null && state.isLinkHandlingAllowed();
	}

	@RequiresApi(Build.VERSION_CODES.S)
	@Nullable
	private static DomainVerificationUserState userState(@NonNull Context context) {
		DomainVerificationManager manager = context.getSystemService(DomainVerificationManager.class);
		if (manager == null) return null;
		try {
			return manager.getDomainVerificationUserState(context.getPackageName());
		} catch (PackageManager.NameNotFoundException | RuntimeException e) {
			return null;
		}
	}
}
