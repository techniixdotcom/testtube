package com.testtube.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;
import androidx.media.app.NotificationCompat.MediaStyle;
import androidx.media.session.MediaButtonReceiver;
import androidx.media3.common.util.UnstableApi;

import com.testtube.app.player.engine.Engine;
import com.testtube.app.player.queue.QueueNav;
import com.testtube.app.ui.MainActivity;
import com.testtube.app.util.ImageUtils;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

@UnstableApi
public class PlaybackService extends Service {
	private static final String TAG = "PlaybackService";
	/** Big enough for the large notification picture on any screen. */
	private static final int NOTIFICATION_ART_PX = 512;
	private static final String CHANNEL_ID = "player_channel";
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
	private volatile boolean destroyed = false;

	public static void start(@NonNull Context context) {
		ContextCompat.startForegroundService(context, new Intent(context, PlaybackService.class));
	}

	private static long playbackActionsFor(@NonNull QueueNav availability) {
		long actions = PlaybackStateCompat.ACTION_PLAY
						| PlaybackStateCompat.ACTION_PAUSE
						| PlaybackStateCompat.ACTION_PLAY_PAUSE
						| PlaybackStateCompat.ACTION_SEEK_TO;
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

	@NonNull
	private static PlaybackStateCompat buildPlaybackState(int state,
	                                                      final long position,
	                                                      final float speed,
	                                                      @NonNull QueueNav availability) {
		return new PlaybackStateCompat.Builder()
						.setActions(playbackActionsFor(availability))
						.setState(state, position, speed)
						.build();
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
		notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
		NotificationChannel channel = new NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel_player),
						NotificationManager.IMPORTANCE_LOW);
		channel.setDescription(getString(R.string.notification_channel_player_description));
		channel.setShowBadge(false);
		channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
		if (notificationManager != null) notificationManager.createNotificationChannel(channel);
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
			public void onStop() {
				// The paused notification was swiped away
				engine.pause();
				hideNotification();
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
	private Notification buildNotification(boolean isPlaying) {
		MediaSessionCompat session = mediaSession;
		if (shouldAbort() || session == null) return null;
		MediaMetadataCompat metadata = session.getController().getMetadata();
		if (metadata == null) return null;
		String title = metadata.getString(MediaMetadataCompat.METADATA_KEY_TITLE);
		String artist = metadata.getString(MediaMetadataCompat.METADATA_KEY_ARTIST);
		Bitmap largeIcon = metadata.getBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART);
		int playPauseIcon = isPlaying ? R.drawable.ic_pause : R.drawable.ic_play;
		String playPauseTitle = isPlaying ? getString(R.string.action_pause) : getString(R.string.action_play);
		PendingIntent playPauseIntent = MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_PLAY_PAUSE);
		PendingIntent prevIntent = MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS);
		PendingIntent nextIntent = MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_SKIP_TO_NEXT);
		Intent launchIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
		if (launchIntent == null) launchIntent = new Intent(this, MainActivity.class);
		launchIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
		PendingIntent contentIntent = PendingIntent.getActivity(this, 101, launchIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
		final NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
						.setSmallIcon(R.drawable.ic_notification)
						.setContentTitle(title)
						.setContentText(artist)
						.setLargeIcon(largeIcon)
						.setContentIntent(contentIntent)
						.setDeleteIntent(MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_STOP))
						.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
						.setOnlyAlertOnce(true)
						.setOngoing(isPlaying);
		MediaStyle style = new MediaStyle().setMediaSession(session.getSessionToken());
		boolean includePrevious = shouldIncludePreviousAction(queueNavigationAvailability);
		boolean includeNext = shouldIncludeNextAction(queueNavigationAvailability);
		if (includePrevious && includeNext) {
			builder.addAction(R.drawable.ic_previous, getString(R.string.action_previous), prevIntent);
			builder.addAction(playPauseIcon, playPauseTitle, playPauseIntent);
			builder.addAction(R.drawable.ic_next, getString(R.string.action_next), nextIntent);
			style.setShowActionsInCompactView(0, 1, 2);
		} else if (includePrevious) {
			builder.addAction(R.drawable.ic_previous, getString(R.string.action_previous), prevIntent);
			builder.addAction(playPauseIcon, playPauseTitle, playPauseIntent);
			style.setShowActionsInCompactView(0, 1);
		} else if (includeNext) {
			builder.addAction(playPauseIcon, playPauseTitle, playPauseIntent);
			builder.addAction(R.drawable.ic_next, getString(R.string.action_next), nextIntent);
			style.setShowActionsInCompactView(0, 1);
		} else {
			builder.addAction(playPauseIcon, playPauseTitle, playPauseIntent);
			style.setShowActionsInCompactView(0);
		}
		return builder.setStyle(style).build();
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
		Notification initialNotification = buildNotification(lastIsPlayingState);
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
				Notification notification = buildNotification(isPlayingState(updatedPlayback));
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
		} catch (Exception e) {
			// Not allowed from the background on newer Android; the notification still shows
			Log.e(TAG, "startForeground failed: " + e.getMessage());
			NotificationManager manager = notificationManager;
			if (manager != null) manager.notify(NOTIFICATION_ID, notification);
		}
	}

	public void hideNotification() {
		stopForeground(true);
		if (notificationManager != null) notificationManager.cancel(NOTIFICATION_ID);
		stopSelf();
	}

	public void updateProgress(long pos, float speed, boolean isPlaying) {
		if (isSeeking) return;
		MediaSessionCompat session = mediaSession;
		NotificationManager manager = notificationManager;
		if (shouldAbort() || session == null) return;
		int stateCompat = isPlaying ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED;
		PlaybackStateCompat playbackState = buildPlaybackState(stateCompat, pos, speed, queueNavigationAvailability);
		if (shouldAbort()) return;
		session.setPlaybackState(playbackState);
		if (isPlaying != lastIsPlayingState) {
			Notification updatedNotification = buildNotification(isPlaying);
			if (updatedNotification != null && manager != null && !shouldAbort()) {
				if (isPlaying) {
					startInForeground(updatedNotification);
				} else {
					// Paused: no reason to keep the app running in the background. The
					// notification stays, can be swiped away now, and play brings it back.
					ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH);
					manager.notify(NOTIFICATION_ID, updatedNotification);
				}
			}
		}
		lastIsPlayingState = isPlaying;
	}

	public void updateQueueNavigationAvailability(@NonNull QueueNav availability) {
		queueNavigationAvailability = availability;
		MediaSessionCompat session = mediaSession;
		NotificationManager manager = notificationManager;
		if (shouldAbort() || session == null) return;
		PlaybackStateCompat playback = session.getController().getPlaybackState();
		int state = playback != null ? playback.getState() : PlaybackStateCompat.STATE_NONE;
		long position = playback != null ? playback.getPosition() : 0L;
		float speed = playback != null ? playback.getPlaybackSpeed() : 1.0f;
		if (shouldAbort()) return;
		session.setPlaybackState(buildPlaybackState(state, position, speed, queueNavigationAvailability));
		Notification updatedNotification = buildNotification(state == PlaybackStateCompat.STATE_PLAYING);
		if (updatedNotification != null && manager != null && !shouldAbort()) {
			manager.notify(NOTIFICATION_ID, updatedNotification);
		}
	}

	@Override
	public void onTaskRemoved(Intent rootIntent) {
		super.onTaskRemoved(rootIntent);
		stopForeground(true);
		stopSelf();
	}

	@Override
	public void onDestroy() {
		destroyed = true;
		// #216: Stop notification work before releasing the media session.
		handler.removeCallbacksAndMessages(null);
		executorService.shutdownNow();
		stopForeground(true);
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

	public class PlaybackBinder extends Binder {
		public PlaybackService getService() {
			return PlaybackService.this;
		}
	}
}
