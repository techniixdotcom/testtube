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
	/** The video's picture for the session (cars, watches); the notification gets a smaller copy. */
	private static final int NOTIFICATION_ART_PX = 512;
	/**
	 * The picture inside the expanded notification. Notifications are sent to the system in one
	 * go, and a full-size picture could push them over the limit.
	 */
	private static final int NOTIFICATION_INLINE_ART_PX = 192;
	private static final int[] CONTROL_IDS = {
					R.id.notification_previous, R.id.notification_back, R.id.notification_play_pause,
					R.id.notification_forward, R.id.notification_next, R.id.notification_close};
	/** How far the notification's back and forward buttons jump. */
	private static final long SEEK_STEP_MS = 10_000L;
	/** Dims a button that can't be used right now (previous at the start of a queue and so on). */
	private static final int DISABLED_ALPHA = 97;
	/**
	 * Normal importance, so the lock screen shows it: Android hides silent notifications there by
	 * default, and only the media player used to be exempt. It never makes a sound or vibrates.
	 * A new id, because a channel's importance can't be changed once it exists.
	 */
	private static final String CHANNEL_ID = "player";
	/** The old silent channel, removed so it doesn't linger in the app's notification settings. */
	private static final String OLD_CHANNEL_ID = "player_channel";
	private static final int NOTIFICATION_ID = 100;
	private final Handler handler = new Handler(Looper.getMainLooper());
	private final ExecutorService executorService = Executors.newSingleThreadExecutor();
	@NonNull
	private QueueNav queueNavigationAvailability = QueueNav.INACTIVE;
	private MediaSessionCompat mediaSession;
	private NotificationManager notificationManager;
	private boolean isSeeking = false;
	private final Runnable resetSeekFlagRunnable = () -> isSeeking = false;
	private boolean lastIsPlayingState = false;
	/** Read on the thread that loads the picture too. */
	private volatile boolean inForeground;
	private boolean lastWantsToPlay = true;
	private volatile boolean destroyed = false;
	/**
	 * Re-posts the notification when the screen goes off. Lock screens that only show new
	 * notifications (an option on GrapheneOS and Pixels) would hide it otherwise: the media player
	 * used to be exempt from that, a normal notification isn't, but an updated one counts as new.
	 */
	private final BroadcastReceiver screenOff = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) repostNotification();
		}
	};
	/** {@link #NOTIFICATION_INLINE_ART_PX} version of the current picture, made once per picture. */
	@Nullable
	private Bitmap inlineArtSource;
	@Nullable
	private Bitmap inlineArt;

	/**
	 * Starts the service for the first video. Later videos (autoplay in the background) find it
	 * running already, and Android 12+ doesn't let an app in the background start it anyway.
	 */
	public static void start(@NonNull Context context) {
		try {
			ContextCompat.startForegroundService(context, new Intent(context, PlaybackService.class));
		} catch (IllegalStateException e) {
			// ForegroundServiceStartNotAllowedException: in the background, and the bound service
			// keeps playing in the foreground already
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
	 * What headsets, cars and watches see. Quit is in here as well for the ones that show custom
	 * buttons; the notification has its own Close.
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
				// Android refuses while a foreground service still uses it; next start, then
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
		// Nothing to bring back after the system stopped it: the video lives in the app
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
				// Stop from a headset, a car or a watch
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
		// Usually straight from the picture cache: the same thumbnail is on screen already
		return ImageUtils.squareBitmap(this, url, NOTIFICATION_ART_PX);
	}

	@Nullable
	/**
	 * @param foreground whether the notification belongs to the running foreground service when
	 *                   it's shown. Only then does Android colour the whole card, so only then can
	 *                   it be in the app's theme; otherwise it's in Android's own colours.
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

		// Previous video, back 10 s, play/pause, forward 10 s, next video, close. Collapsed (and on
		// the lock screen) it's just the buttons; expanded adds the picture, title and channel.
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

		// No MediaStyle at all, on purpose. With it Android draws its own media player, and swiping
		// that one only hides it: the video plays on and the app never hears about it. Since
		// Android 15 a MediaStyle notification can't be swiped away at all, session or not. A plain
		// notification can, and the swipe reaches us through the delete intent. Android 14+ lets it
		// be swiped while playing; older versions keep a playing one pinned until it's paused.
		NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
						.setSmallIcon(R.drawable.ic_notification)
						.setContentTitle(title)
						.setContentText(artist)
						// The header line of the collapsed notification: what's playing
						.setSubText(title)
						.setContentIntent(contentIntent)
						// Swiping it away closes the whole app, playing or not, on screen or in the background
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
	 * Wires up the six buttons. Notifications only know taps, so no double-tap here.
	 *
	 * @param colors the app theme's colours, or null for Android's
	 */
	private void bindControls(@NonNull RemoteViews views, boolean isPlaying, @Nullable CardColors colors) {
		if (colors != null) {
			for (int id : CONTROL_IDS) views.setInt(id, "setColorFilter", colors.icon);
			// Play/pause in the accent, like the big button in the player (pink in NiiX)
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

	/** Synchronized: the notification is also rebuilt on the thread that loads the picture. */
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
			// Not allowed from the background on newer Android; the notification still shows
			Log.e(TAG, "startForeground failed: " + e.getMessage());
			NotificationManager manager = notificationManager;
			if (manager == null) return;
			// Not a foreground notification after all, so Android won't colour it: its own colours then
			Notification plain = buildNotification(lastIsPlayingState, false);
			manager.notify(NOTIFICATION_ID, plain != null ? plain : notification);
		}
	}

	/** The same notification again, only if it's still there (never brings back a swiped one). */
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
	 * @param wantsToPlay false only when paused; buffering and the step to the next video in the
	 *                    queue keep the service in the foreground, which Android won't give back
	 *                    to an app in the background
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
		// Only on changes: retrying a refused startForeground on every tick would be pointless
		if (isPlaying != lastIsPlayingState || wantsToPlay != lastWantsToPlay) {
			boolean foreground = staysForeground(wantsToPlay);
			Notification updatedNotification = buildNotification(isPlaying, foreground);
			if (updatedNotification != null && manager != null && !shouldAbort()) {
				if (foreground && !inForeground) {
					startInForeground(updatedNotification);
				} else if (!foreground && inForeground) {
					// Paused before Android 14: a foreground notification can't be swiped away
					// there, so it lets go. The notification stays, and play brings it back.
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
	 * Whether the service stays in the foreground. Only a foreground notification can be in the
	 * app's theme (Android colours just those), so from Android 14 on, where it can still be
	 * swiped away, it stays in the foreground while paused too and keeps its look. Before 14 a
	 * paused one leaves the foreground, or swiping it away wouldn't work.
	 */
	private static boolean staysForeground(boolean wantsToPlay) {
		return wantsToPlay || Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE;
	}

	public void updateQueueNavigationAvailability(@NonNull QueueNav availability) {
		queueNavigationAvailability = availability;
		refreshControls();
	}

	/** Builds the session's buttons and the notification again, for the state they're in now. */
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
		// Only the task the player lives in; swiping away the downloads screen leaves the video be
		ComponentName root = rootIntent != null ? rootIntent.getComponent() : null;
		if (root != null && !MainActivity.class.getName().equals(root.getClassName())) return;
		// Swiped away from the recent apps: that means stop, like YouTube does. Only stopping the
		// service wasn't enough, the screen kept it bound and the video just played on with the
		// notification gone. Clear cache on exit counts here too, it's a normal exit. The service
		// stops only once the cache is empty: while it's still started Android leaves the process
		// alive, without it the clearing would be killed half-way.
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
			// onCreate didn't get that far
		}
		// #216: Stop notification work before releasing the media session.
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

	/** The colours of the app's chosen theme (Dark, Light, NiiX or following the phone). */
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

		/**
		 * A service doesn't follow the app's light or dark choice by itself (only screens do), so
		 * the theme is applied to a context with the matching night mode first.
		 */
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
