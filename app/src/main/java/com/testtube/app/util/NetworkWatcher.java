package com.testtube.app.util;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Wi-Fi or mobile data, kept current while the app runs. Listeners hear about a change once it
 * settled for a moment, so a blip while switching doesn't count.
 */
public final class NetworkWatcher {
	private static final String TAG = "NetworkWatcher";
	private static final long SETTLE_MS = 2_000L;

	public interface Listener {
		@MainThread
		void onNetworkTypeChanged(boolean mobile);
	}

	@NonNull
	private final Handler main = new Handler(Looper.getMainLooper());
	@NonNull
	private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
	private volatile boolean mobile;
	private volatile boolean pendingMobile;
	@NonNull
	private final Runnable settle = this::settle;

	public NetworkWatcher(@NonNull Context context) {
		ConnectivityManager connectivity = context.getSystemService(ConnectivityManager.class);
		if (connectivity == null) return;
		try {
			mobile = isMobile(connectivity.getNetworkCapabilities(connectivity.getActiveNetwork()));
			pendingMobile = mobile;
			connectivity.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
				@Override
				public void onCapabilitiesChanged(@NonNull Network network, @NonNull NetworkCapabilities capabilities) {
					update(isMobile(capabilities));
				}
			}, main);
		} catch (RuntimeException e) {
			// some devices refuse the callback (too many registered), the type stays as read
			Log.w(TAG, "network changes won't be noticed", e);
		}
	}

	/** True on mobile data, false on Wi-Fi or Ethernet. */
	public boolean isMobile() {
		return mobile;
	}

	public void addListener(@NonNull Listener listener) {
		listeners.addIfAbsent(listener);
	}

	public void removeListener(@NonNull Listener listener) {
		listeners.remove(listener);
	}

	private void update(boolean nowMobile) {
		pendingMobile = nowMobile;
		main.removeCallbacks(settle);
		main.postDelayed(settle, SETTLE_MS);
	}

	private void settle() {
		boolean nowMobile = pendingMobile;
		if (nowMobile == mobile) return;
		mobile = nowMobile;
		for (Listener listener : listeners) {
			listener.onNetworkTypeChanged(nowMobile);
		}
	}

	/**
	 * Wi-Fi and Ethernet count as Wi-Fi, cellular as mobile. Anything else (VPN hiding its network,
	 * Bluetooth tethering) goes by metered.
	 */
	private static boolean isMobile(@Nullable NetworkCapabilities capabilities) {
		if (capabilities == null) return false;
		if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
						|| capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
			return false;
		}
		if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return true;
		return !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
	}
}
