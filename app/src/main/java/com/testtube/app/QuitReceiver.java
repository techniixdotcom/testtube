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
 * Closing from the player notification (also on the lock screen): Quit, or swiping it away.
 * Controllers that only know the session (watch, car) send Quit to {@link PlaybackService}.
 * <p>
 * Quitting stops the video, closes the screens and clears the cache. Swiping the notification or
 * the app away does the same (see {@link #exit}). Downloads keep going.
 */
@UnstableApi
public final class QuitReceiver extends BroadcastReceiver {
	static final String ACTION_QUIT = "com.testtube.app.action.QUIT";
	private static final String ACTION_DISMISSED = "com.testtube.app.action.NOTIFICATION_DISMISSED";
	private static final int REQUEST_QUIT = 102;
	private static final int REQUEST_DISMISSED = 103;
	/** Well inside what Android gives an async receiver. */
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
			// swiped away: close everything, like swiping from recents
			clearCache = AppSettings.clearCacheOnExit();
		} else {
			return;
		}
		// keep the process alive until the cache is empty, or Android kills it half way. Don't hang
		// forever though; the next start clears what's left.
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

	/** Quit button. Explicit and immutable, only we can send it. */
	@NonNull
	static PendingIntent pendingIntent(@NonNull Context context) {
		return broadcast(context, ACTION_QUIT, REQUEST_QUIT);
	}

	/** Notification swiped away. */
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

	/** Main screen registers how to close itself. Without it, quit just stops the service. */
	@MainThread
	public static void setCloseScreens(@NonNull Runnable close) {
		closeScreens = close;
	}

	/**
	 * Only forgets {@code close} if it's still the registered one; a rebuilt screen may have
	 * registered already.
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
	 * Stops the video, removes the notification, closes the screens. Quit always clears the cache,
	 * swiping only with clear-on-exit on. {@code done} runs on the main thread once clearing is done
	 * (right away if there's nothing to clear) so the caller can stay alive until then.
	 */
	@MainThread
	static void exit(@NonNull Context context, boolean clearCache, @Nullable Runnable done) {
		Context app = context.getApplicationContext();
		Runnable close = closeScreens;
		if (close != null) {
			// hides the notification and finishes the screens, the player goes with them
			close.run();
		} else {
			app.stopService(new Intent(app, PlaybackService.class));
		}
		removeFromRecents(app);
		if (!clearCache) {
			if (done != null) done.run();
			return;
		}
		// fine while the player lets go, the caches are emptied through their APIs
		AppGraph.of(app).clearCachesOnExit(done);
	}

	/** Removes all our tasks from recents. finish alone left the card behind on Nothing OS. */
	private static void removeFromRecents(@NonNull Context app) {
		ActivityManager activities = app.getSystemService(ActivityManager.class);
		if (activities == null) return;
		for (ActivityManager.AppTask task : activities.getAppTasks()) {
			try {
				task.finishAndRemoveTask();
			} catch (RuntimeException alreadyGone) {
				// already gone, fine
			}
		}
	}
}
