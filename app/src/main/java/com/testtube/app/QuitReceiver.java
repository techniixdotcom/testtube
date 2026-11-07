package com.testtube.app;

import android.app.ActivityManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Closing the app from its player notification, which also shows on the lock screen: the Quit
 * button, and swiping the notification away. A controller that only knows the session (a watch,
 * a car) sends Quit to {@link PlaybackService}, which calls {@link #quit} itself.
 * <p>
 * Quitting stops the video, closes every screen and clears the cache, like "Clear cache" in the
 * settings. Swiping the notification away, or the app away from the recent apps, closes it the
 * same way (see {@link #exit}). Downloads in progress keep going; they have their own notification.
 */
@UnstableApi
public final class QuitReceiver extends BroadcastReceiver {
	static final String ACTION_QUIT = "com.testtube.app.action.QUIT";
	private static final String ACTION_DISMISSED = "com.testtube.app.action.NOTIFICATION_DISMISSED";
	private static final int REQUEST_QUIT = 102;
	private static final int REQUEST_DISMISSED = 103;
	/** Well inside the time Android gives a receiver that went async. */
	private static final long MAX_CLEAR_WAIT_MS = 8_000L;

	/** Closes the screens; set while the main screen exists. */
	@Nullable
	private static Runnable closeScreens;

	@Override
	public void onReceive(@NonNull Context context, @NonNull Intent intent) {
		String action = intent.getAction();
		boolean clearCache;
		if (ACTION_QUIT.equals(action)) {
			clearCache = true;
		} else if (ACTION_DISMISSED.equals(action)) {
			// Swiped away, playing or not: everything closes, like swiping the app out of recents
			clearCache = AppSettings.clearCacheOnExit();
		} else {
			return;
		}
		// Keeps the process alive until the cache is empty; with every screen gone Android would
		// otherwise kill it half-way through. Let go in time anyway, a receiver mustn't hang on;
		// whatever is left then gets cleared on the next start.
		PendingResult pending = goAsync();
		AtomicBoolean finished = new AtomicBoolean();
		Handler handler = new Handler(Looper.getMainLooper());
		Runnable finish = () -> {
			if (finished.compareAndSet(false, true)) pending.finish();
		};
		handler.postDelayed(finish, MAX_CLEAR_WAIT_MS);
		exit(context, clearCache, () -> {
			handler.removeCallbacks(finish);
			finish.run();
		});
	}

	/** For the notification's Quit button. Only this app can send it (explicit and immutable). */
	@NonNull
	static PendingIntent pendingIntent(@NonNull Context context) {
		return broadcast(context, ACTION_QUIT, REQUEST_QUIT);
	}

	/** Sent when the notification is swiped away. */
	@NonNull
	static PendingIntent dismissIntent(@NonNull Context context) {
		return broadcast(context, ACTION_DISMISSED, REQUEST_DISMISSED);
	}

	@NonNull
	private static PendingIntent broadcast(@NonNull Context context, @NonNull String action, int requestCode) {
		Intent intent = new Intent(context, QuitReceiver.class).setAction(action);
		return PendingIntent.getBroadcast(context, requestCode, intent,
						PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
	}

	/** The main screen registers how to close itself. Without one, quitting just stops the player service. */
	@MainThread
	public static void setCloseScreens(@NonNull Runnable close) {
		closeScreens = close;
	}

	/**
	 * When that screen goes. Only forgets {@code close} if it's still the registered one, since a
	 * rebuilt screen may have registered its own already.
	 */
	@MainThread
	public static void clearCloseScreens(@NonNull Runnable close) {
		if (closeScreens == close) closeScreens = null;
	}

	@MainThread
	static void quit(@NonNull Context context) {
		exit(context, true, null);
	}

	/**
	 * Stops the video, takes the notification away and closes the screens. The Quit button always
	 * clears the cache; swiping the app away only does when "Clear cache on exit" is on.
	 * {@code done}, if given, runs on the main thread once the cache is empty (right away when it
	 * isn't cleared), so the caller can keep the process alive until then.
	 */
	@MainThread
	static void exit(@NonNull Context context, boolean clearCache, @Nullable Runnable done) {
		Context app = context.getApplicationContext();
		Runnable close = closeScreens;
		if (close != null) {
			// Hides the notification and finishes the screens; the player goes with them
			close.run();
		} else {
			app.stopService(new Intent(app, PlaybackService.class));
		}
		removeFromRecents(app);
		if (!clearCache) {
			if (done != null) done.run();
			return;
		}
		// Safe while the player is still letting go: the caches are emptied through their own APIs
		AppGraph.of(app).clearCachesOnExit(done);
	}

	/**
	 * Takes every task of the app out of the recent apps. Finishing the screen alone left the card
	 * behind on some phones (Nothing OS), so closed didn't look closed.
	 */
	private static void removeFromRecents(@NonNull Context app) {
		ActivityManager activities = app.getSystemService(ActivityManager.class);
		if (activities == null) return;
		for (ActivityManager.AppTask task : activities.getAppTasks()) {
			try {
				task.finishAndRemoveTask();
			} catch (RuntimeException alreadyGone) {
				// Removed in the meantime, which is what we wanted anyway
			}
		}
	}
}
