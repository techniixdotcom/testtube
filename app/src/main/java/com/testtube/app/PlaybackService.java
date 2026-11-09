package com.testtube.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.os.Binder;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.service.notification.StatusBarNotification;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.RemoteViews;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;
import androidx.media.session.MediaButtonReceiver;
import androidx.media3.common.util.UnstableApi;

import com.testtube.app.player.engine.Engine;
import com.testtube.app.player.queue.QueueNav;
import com.testtube.app.ui.MainActivity;
import com.testtube.app.util.ImageUtils;
import com.testtube.app.util.ViewUtils;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

@UnstableApi
public class PlaybackService extends Service {
	private static final String TAG = "PlaybackService";
	/** Session artwork (cars, watches); the notification gets a smaller copy. */
	private static final int NOTIFICATION_ART_PX = 512;
	/** Picture in the expanded notification. A full size one can blow the binder size limit. */
	private static final int NOTIFICATION_INLINE_ART_PX = 192;
	private static final int[] CONTROL_IDS = {
					R.id.notification_previous, R.id.notification_back, R.id.notification_play_pause,
					R.id.notification_forward, R.id.notification_next, R.id.notification_close};
	/** Seek step of the notification buttons. */
	private static final long SEEK_STEP_MS = 10_000L;
	/** Dims buttons that do nothing right now. */
	private static final int DISABLED_ALPHA = 97;
	/**
	 * Default importance so the lock screen shows it (Android hides silent ones there, only the old
	 * media player was exempt). Never makes a sound. New id since importance can't change later.
	 */
	private static final String CHANNEL_ID = "player";
	/** Old silent channel, removed so it doesn't linger in settings. */
	private static final String OLD_CHANNEL_ID = "player_channel";
	static final int NOTIFICATION_ID = 100;
	private final Handler handler = new Handler(Looper.getMainLooper());
	private final ExecutorService executorService = Executors.newSingleThreadExecutor();
	@NonNull
	private QueueNav queueNavigationAvailability = QueueNav.INACTIVE;
	private MediaSessionCompat mediaSession;
	private NotificationManager notificationManager;
	private boolean isSeeking = false;
	private final Runnable resetSeekFlagRunnable = () -> isSeeking = false;
	private boolean lastIsPlayingState = false;
	/** Also read on the artwork thread. */
	private volatile boolean inForeground;
	private boolean lastWantsToPlay = true;
	private volatile boolean destroyed = false;
	/**
	 * Re-posts when the screen goes off. "New notifications only" lock screens (GrapheneOS,
	 * Pixel) would hide it otherwise; an updated one counts as new.
	 */
	private final BroadcastReceiver screenOff = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) repostNotification();
		}
	};
	/** Small copy of the current picture, made once per picture. */
	@Nullable
	private Bitmap inlineArtSource;
	@Nullable
	private Bitmap inlineArt;

	/**
	 * Starts the service for the first video. Later ones find it running, and Android 12+ won't
	 * let a background app start it anyway.
	 */
	public static void start(@NonNull Context context) {
		try {
			ContextCompat.startForegroundService(context, new Intent(context, PlaybackService.class));
		} catch (IllegalStateException e) {
			// ForegroundServiceStartNotAllowedException: we're in the background and the bound service is
			// already in the foreground
			Log.w(TAG, "not started from the background: " + e.getMessage());
		}
	}

	private static long playbackActionsFor(@NonNull QueueNav availability) {
		long actions = PlaybackStateCompat.ACTION_PLAY
						| PlaybackStateCompat.ACTION_PAUSE
						| PlaybackStateCompat.ACTION_PLAY_PAUSE
						| PlaybackStateCompat.ACTION_SEEK_TO
						| PlaybackStateCompat.ACTION_REWIND
						| PlaybackStateCompat.ACTION_FAST_FORWARD;
		if (shouldIncludeNextAction(availability)) {
			actions |= PlaybackStateCompat.ACTION_SKIP_TO_NEXT;
		}
		if (shouldIncludePreviousAction(availability)) {
			actions |= PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS;
		}
		return actions;
	}

	private static boolean shouldIncludePreviousAction(@NonNull QueueNav availability) {
		return availability.isPreviousActionEnabled();
	}

	private static boolean shouldIncludeNextAction(@NonNull QueueNav availability) {
		return availability.isNextActionEnabled();
	}

	/**
	 * What headsets, cars and watches see. Quit is here for ones with custom buttons; the
	 * notification has its own Close.
	 */
	@NonNull
	private PlaybackStateCompat buildPlaybackState(int state,
	                                               final long position,
	                                               final float speed,
	                                               @NonNull QueueNav availability) {
		PlaybackStateCompat.Builder builder = new PlaybackStateCompat.Builder()
						.setActions(playbackActionsFor(availability))
						.setState(state, position, speed);
		builder.addCustomAction(new PlaybackStateCompat.CustomAction.Builder(
						QuitReceiver.ACTION_QUIT, getString(R.string.action_quit), R.drawable.ic_quit).build());
		return builder.build();
	}

	private static boolean isPlayingState(@Nullable PlaybackStateCompat playback) {
		return playback != null && playback.getState() == PlaybackStateCompat.STATE_PLAYING;
	}

	@Nullable
	@Override
	public IBinder onBind(@NonNull Intent intent) {
		return new PlaybackBinder();
	}

	@Override
	public void onCreate() {
		super.onCreate();
		destroyed = false;
		ContextCompat.registerReceiver(this, screenOff, new IntentFilter(Intent.ACTION_SCREEN_OFF),
						ContextCompat.RECEIVER_NOT_EXPORTED);
		notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
		NotificationChannel channel = new NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel_player),
						NotificationManager.IMPORTANCE_DEFAULT);
		channel.setDescription(getString(R.string.notification_channel_player_description));
		channel.setShowBadge(false);
		channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
		channel.setSound(null, null);
		channel.enableVibration(false);
		channel.enableLights(false);
		if (notificationManager != null) {
			try {
				notificationManager.deleteNotificationChannel(OLD_CHANNEL_ID);
			} catch (SecurityException stillInUse) {
				// Android refuses while a foreground service uses it, so next start
			}
			notificationManager.createNotificationChannel(channel);
		}
		mediaSession = new MediaSessionCompat(this, TAG);
		PlaybackStateCompat initialState = buildPlaybackState(
						PlaybackStateCompat.STATE_NONE,
						0L,
						1.0f,
						queueNavigationAvailability);
		mediaSession.setPlaybackState(initialState);
	}

	@Override
	public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
		MediaSessionCompat session = mediaSession;
		if (intent != null && session != null) MediaButtonReceiver.handleIntent(session, intent);
		// nothing to restore after a system kill, the video lives in the app
		return START_NOT_STICKY;
	}

	public void initialize(@NonNull Engine engine) {
		MediaSessionCompat session = mediaSession;
		if (shouldAbort() || session == null) return;
		session.setCallback(new MediaSessionCompat.Callback() {
			@Override
			public void onPlay() {
				engine.play();
			}

			@Override
			public void onPause() {
				engine.pause();
			}

			@Override
			public void onSkipToNext() {
				engine.skipToNext();
			}

			@Override
			public void onSkipToPrevious() {
				engine.skipToPrevious();
			}

			@Override
			public void onRewind() {
				engine.seekBy(-SEEK_STEP_MS);
			}

			@Override
			public void onFastForward() {
				engine.seekBy(SEEK_STEP_MS);
			}

			@Override
			public void onStop() {
				// stop from a headset, car or watch
				engine.pause();
				hideNotification();
			}

			@Override
			public void onCustomAction(@NonNull String action, @Nullable Bundle extras) {
				if (QuitReceiver.ACTION_QUIT.equals(action)) QuitReceiver.quit(PlaybackService.this);
			}

			@Override
			public void onSeekTo(long pos) {
				isSeeking = true;
				handler.removeCallbacks(resetSeekFlagRunnable);
				handler.postDelayed(resetSeekFlagRunnable, 1000);
				engine.seekTo(pos);
			}
		});
		session.setActive(true);
	}

	@Nullable
	private Bitmap fetchThumbnail(@Nullable String url) {
		if (url == null || url.isEmpty() || shouldAbort()) return null;
		// usually a cache hit, the thumbnail is on screen already
		return ImageUtils.squareBitmap(this, url, NOTIFICATION_ART_PX);
	}

	@Nullable
	/**
	 * @param foreground whether the notification belongs to the running foreground service. Only
	 *                   then does Android colour the whole card, so only then can it use our theme.
	 */
	private Notification buildNotification(boolean isPlaying, boolean foreground) {
		MediaSessionCompat session = mediaSession;
		if (shouldAbort() || session == null) return null;
		MediaMetadataCompat metadata = session.getController().getMetadata();
		if (metadata == null) return null;
		String title = metadata.getString(MediaMetadataCompat.METADATA_KEY_TITLE);
		String artist = metadata.getString(MediaMetadataCompat.METADATA_KEY_ARTIST);
		Bitmap largeIcon = metadata.getBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART);
		Intent launchIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
		if (launchIntent == null) launchIntent = new Intent(this, MainActivity.class);
		launchIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
		PendingIntent contentIntent = PendingIntent.getActivity(this, 101, launchIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

		// Prev, back 10 s, play/pause, forward 10 s, next, close. Collapsed (and on the lock screen) just
		// the buttons; expanded adds picture, title and channel.
		CardColors colors = foreground ? CardColors.of(this) : null;
		RemoteViews controls = new RemoteViews(getPackageName(), R.layout.notification_player_controls);
		bindControls(controls, isPlaying, colors);
		boolean niix = AppSettings.theme() == AppSettings.Theme.NIIX;
		RemoteViews expanded = new RemoteViews(getPackageName(),
						niix ? R.layout.notification_player_expanded_niix : R.layout.notification_player_expanded);
		expanded.setTextViewText(R.id.notification_title, title);
		expanded.setTextViewText(R.id.notification_author, artist);
		if (colors != null) {
			expanded.setTextColor(R.id.notification_title, colors.text);
			expanded.setTextColor(R.id.notification_author, colors.secondary);
		}
		Bitmap art = inlineArt(largeIcon);
		if (art != null) {
			expanded.setImageViewBitmap(R.id.notification_art, art);
			expanded.setViewVisibility(R.id.notification_art, View.VISIBLE);
		} else {
			expanded.setViewVisibility(R.id.notification_art, View.GONE);
		}
		bindControls(expanded, isPlaying, colors);

		// No MediaStyle on purpose. With it Android draws its own player, swiping that only hides it
		// and the video plays on. On 15+ it can't be swiped at all. A plain notification can, and we get
		// the delete intent. 14+ allows swiping while playing, older keeps it pinned until paused.
		NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
						.setSmallIcon(R.drawable.ic_notification)
						.setContentTitle(title)
						.setContentText(artist)
						// header line: what's playing
						.setSubText(title)
						.setContentIntent(contentIntent)
						// swiping it away closes the whole app
						.setDeleteIntent(QuitReceiver.dismissIntent(this))
						.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
						.setOnlyAlertOnce(true)
						.setShowWhen(false)
						.setOngoing(false)
						.setStyle(new NotificationCompat.DecoratedCustomViewStyle())
						.setCustomContentView(controls)
						.setCustomBigContentView(expanded);
		if (colors != null) builder.setColorized(true).setColor(colors.background);
		return builder.build();
	}

	/**
	 * Hooks up the six buttons. Notifications only get taps, no double tap.
	 * @param colors theme colours, or null for Android's
	 */
	private void bindControls(@NonNull RemoteViews views, boolean isPlaying, @Nullable CardColors colors) {
		if (colors != null) {
			for (int id : CONTROL_IDS) views.setInt(id, "setColorFilter", colors.icon);
			// play/pause in the accent, like the player's big button
			views.setInt(R.id.notification_play_pause, "setColorFilter", colors.accent);
		}
		bindButton(views, R.id.notification_previous, shouldIncludePreviousAction(queueNavigationAvailability),
						PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS);
		bindButton(views, R.id.notification_back, true, PlaybackStateCompat.ACTION_REWIND);
		bindButton(views, R.id.notification_play_pause, true, PlaybackStateCompat.ACTION_PLAY_PAUSE);
		bindButton(views, R.id.notification_forward, true, PlaybackStateCompat.ACTION_FAST_FORWARD);
		bindButton(views, R.id.notification_next, shouldIncludeNextAction(queueNavigationAvailability),
						PlaybackStateCompat.ACTION_SKIP_TO_NEXT);
		views.setImageViewResource(R.id.notification_play_pause, isPlaying ? R.drawable.ic_pause : R.drawable.ic_play);
		views.setContentDescription(R.id.notification_play_pause,
						getString(isPlaying ? R.string.action_pause : R.string.action_play));
		views.setOnClickPendingIntent(R.id.notification_close, QuitReceiver.pendingIntent(this));
	}

	private void bindButton(@NonNull RemoteViews views, int id, boolean enabled, long action) {
		views.setBoolean(id, "setEnabled", enabled);
		views.setInt(id, "setImageAlpha", enabled ? 255 : DISABLED_ALPHA);
		if (enabled) views.setOnClickPendingIntent(id, MediaButtonReceiver.buildMediaButtonPendingIntent(this, action));
	}

	/** Synchronized: the artwork thread rebuilds it too. */
	@Nullable
	private synchronized Bitmap inlineArt(@Nullable Bitmap source) {
		if (source == null) return null;
		if (source != inlineArtSource) {
			inlineArtSource = source;
			int largest = Math.max(source.getWidth(), source.getHeight());
			if (largest <= NOTIFICATION_INLINE_ART_PX) {
				inlineArt = source;
			} else {
				float scale = NOTIFICATION_INLINE_ART_PX / (float) largest;
				inlineArt = Bitmap.createScaledBitmap(source, Math.max(1, Math.round(source.getWidth() * scale)),
								Math.max(1, Math.round(source.getHeight() * scale)), true);
			}
		}
		return inlineArt;
	}

	public void showNotification(@Nullable String title, @Nullable String author, @Nullable String thumbnail, long duration) {
		if (shouldAbort()) return;
		MediaSessionCompat session = mediaSession;
		if (session == null) return;
		PlaybackStateCompat playback = session.getController().getPlaybackState();
		if (playback != null && playback.getState() != PlaybackStateCompat.STATE_NONE) {
			playback = buildPlaybackState(
							playback.getState(),
							playback.getPosition(),
							playback.getPlaybackSpeed(),
							queueNavigationAvailability);
		} else {
			playback = buildPlaybackState(
							PlaybackStateCompat.STATE_PAUSED,
							0L,
							1.0f,
							queueNavigationAvailability);
		}
		session.setPlaybackState(playback);
		lastIsPlayingState = isPlayingState(playback);
		MediaMetadataCompat initialMetadata = new MediaMetadataCompat.Builder()
						.putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
						.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, author)
						.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
						.build();
		session.setMetadata(initialMetadata);
		Notification initialNotification = buildNotification(lastIsPlayingState, true);
		if (initialNotification != null && !shouldAbort()) startInForeground(initialNotification);
		try {
			executorService.execute(() -> {
				if (shouldAbort()) return;
				Bitmap largeIcon = fetchThumbnail(thumbnail);
				if (shouldAbort() || largeIcon == null) return;
				MediaSessionCompat currentSession = mediaSession;
				NotificationManager manager = notificationManager;
				if (currentSession == null || manager == null) return;
				MediaMetadataCompat metadata = new MediaMetadataCompat.Builder()
								.putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
								.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, author)
								.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, largeIcon)
								.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
								.build();
				if (shouldAbort()) return;
				currentSession.setMetadata(metadata);
				PlaybackStateCompat updatedPlayback = currentSession.getController().getPlaybackState();
				Notification notification = buildNotification(isPlayingState(updatedPlayback), inForeground);
				if (notification != null && !shouldAbort()) {
					manager.notify(NOTIFICATION_ID, notification);
				}
			});
		} catch (RejectedExecutionException ignored) {
		}
	}

	private void startInForeground(@NonNull Notification notification) {
		try {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
				startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
			} else {
				startForeground(NOTIFICATION_ID, notification);
			}
			inForeground = true;
		} catch (Exception e) {
			// newer Android refuses from the background, the notification still shows
			Log.e(TAG, "startForeground failed: " + e.getMessage());
			NotificationManager manager = notificationManager;
			if (manager == null) return;
			// not foreground after all, so Android won't colour it; use its colours
			Notification plain = buildNotification(lastIsPlayingState, false);
			manager.notify(NOTIFICATION_ID, plain != null ? plain : notification);
		}
	}

	/** Re-posts the same notification, only if it's still there (never revives a swiped one). */
	private void repostNotification() {
		NotificationManager manager = notificationManager;
		if (shouldAbort() || manager == null || !isNotificationShowing(manager)) return;
		Notification notification = buildNotification(lastIsPlayingState, inForeground);
		if (notification != null) manager.notify(NOTIFICATION_ID, notification);
	}

	private static boolean isNotificationShowing(@NonNull NotificationManager manager) {
		for (StatusBarNotification shown : manager.getActiveNotifications()) {
			if (shown.getId() == NOTIFICATION_ID) return true;
		}
		return false;
	}

	public void hideNotification() {
		stopForeground(true);
		inForeground = false;
		if (notificationManager != null) notificationManager.cancel(NOTIFICATION_ID);
		stopSelf();
	}

	/**
	 * @param wantsToPlay false only when paused. Buffering and the gap to the next queued video keep
	 *                    the service in the foreground, Android won't give it back to a background app
	 */
	public void updateProgress(long pos, float speed, boolean isPlaying, boolean wantsToPlay) {
		if (isSeeking) return;
		MediaSessionCompat session = mediaSession;
		NotificationManager manager = notificationManager;
		if (shouldAbort() || session == null) return;
		int stateCompat = isPlaying ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED;
		PlaybackStateCompat playbackState = buildPlaybackState(stateCompat, pos, speed, queueNavigationAvailability);
		if (shouldAbort()) return;
		session.setPlaybackState(playbackState);
		// only on change, retrying a refused startForeground every tick is pointless
		if (isPlaying != lastIsPlayingState || wantsToPlay != lastWantsToPlay) {
			boolean foreground = staysForeground(wantsToPlay);
			Notification updatedNotification = buildNotification(isPlaying, foreground);
			if (updatedNotification != null && manager != null && !shouldAbort()) {
				if (foreground && !inForeground) {
					startInForeground(updatedNotification);
				} else if (!foreground && inForeground) {
					// paused before 14: a foreground notification can't be swiped there, so let go of the
					// foreground. The notification stays and play brings it back.
					ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH);
					inForeground = false;
					manager.notify(NOTIFICATION_ID, updatedNotification);
				} else {
					manager.notify(NOTIFICATION_ID, updatedNotification);
				}
			}
		}
		lastIsPlayingState = isPlaying;
		lastWantsToPlay = wantsToPlay;
	}

	/**
	 * Only foreground notifications get themed, so on 14+ (still swipeable) it stays foreground while
	 * paused too. Before 14 a paused one has to leave the foreground or it can't be swiped.
	 */
	private static boolean staysForeground(boolean wantsToPlay) {
		return wantsToPlay || Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE;
	}

	public void updateQueueNavigationAvailability(@NonNull QueueNav availability) {
		queueNavigationAvailability = availability;
		refreshControls();
	}

	/** Rebuilds the session buttons and the notification for the current state. */
	private void refreshControls() {
		MediaSessionCompat session = mediaSession;
		NotificationManager manager = notificationManager;
		if (shouldAbort() || session == null) return;
		PlaybackStateCompat playback = session.getController().getPlaybackState();
		int state = playback != null ? playback.getState() : PlaybackStateCompat.STATE_NONE;
		long position = playback != null ? playback.getPosition() : 0L;
		float speed = playback != null ? playback.getPlaybackSpeed() : 1.0f;
		if (shouldAbort()) return;
		session.setPlaybackState(buildPlaybackState(state, position, speed, queueNavigationAvailability));
		Notification updatedNotification = buildNotification(state == PlaybackStateCompat.STATE_PLAYING, inForeground);
		if (updatedNotification != null && manager != null && !shouldAbort()) {
			manager.notify(NOTIFICATION_ID, updatedNotification);
		}
	}

	@Override
	public void onTaskRemoved(Intent rootIntent) {
		super.onTaskRemoved(rootIntent);
		// only our own task; swiping the downloads screen leaves the video alone
		ComponentName root = rootIntent != null ? rootIntent.getComponent() : null;
		if (root != null && !MainActivity.class.getName().equals(root.getClassName())) return;
		// swiped from recents means stop, like YouTube. Just stopping the service wasn't enough, the
		// bound screen kept the video playing. Counts as an exit for clear-on-exit. The service only
		// stops once clearing is done, otherwise Android kills it half way.
		stopForeground(true);
		inForeground = false;
		QuitReceiver.exit(this, AppSettings.clearCacheOnExit(), this::stopSelf);
	}

	@Override
	public void onDestroy() {
		destroyed = true;
		try {
			unregisterReceiver(screenOff);
		} catch (IllegalArgumentException notRegistered) {
			// onCreate didn't get this far
		}
		// #216: stop notification work before releasing the session
		handler.removeCallbacksAndMessages(null);
		executorService.shutdownNow();
		stopForeground(true);
		inForeground = false;
		MediaSessionCompat session = mediaSession;
		mediaSession = null;
		if (session != null) {
			session.setActive(false);
			session.release();
		}
		NotificationManager manager = notificationManager;
		notificationManager = null;
		if (manager != null) {
			manager.cancel(NOTIFICATION_ID);
		}
		super.onDestroy();
	}

	private boolean shouldAbort() {
		return destroyed || Thread.currentThread().isInterrupted();
	}

	/** Colours of the chosen theme. */
	private static final class CardColors {
		@ColorInt
		final int background;
		@ColorInt
		final int text;
		@ColorInt
		final int secondary;
		@ColorInt
		final int icon;
		@ColorInt
		final int accent;

		private CardColors(@NonNull Context themed) {
			background = ViewUtils.themeColor(themed, com.google.android.material.R.attr.colorSurface);
			text = ViewUtils.themeColor(themed, com.google.android.material.R.attr.colorOnSurface);
			secondary = ViewUtils.themeColor(themed, com.google.android.material.R.attr.colorOnSurfaceVariant);
			icon = text;
			accent = ViewUtils.themeColor(themed, androidx.appcompat.R.attr.colorPrimary);
		}

		/** Services don't follow the app's night mode on their own, so wrap the context first. */
		@NonNull
		static CardColors of(@NonNull Context context) {
			AppSettings.Theme theme = AppSettings.theme();
			Configuration config = new Configuration(context.getResources().getConfiguration());
			int night;
			if (theme.nightMode == AppCompatDelegate.MODE_NIGHT_YES) {
				night = Configuration.UI_MODE_NIGHT_YES;
			} else if (theme.nightMode == AppCompatDelegate.MODE_NIGHT_NO) {
				night = Configuration.UI_MODE_NIGHT_NO;
			} else {
				night = config.uiMode & Configuration.UI_MODE_NIGHT_MASK;
			}
			config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | night;
			return new CardColors(new ContextThemeWrapper(context.createConfigurationContext(config), theme.style));
		}
	}

	public class PlaybackBinder extends Binder {
		public PlaybackService getService() {
			return PlaybackService.this;
		}
	}
}
