package com.testtube.app.ui;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.util.UnstableApi;

import com.google.android.material.snackbar.Snackbar;
import com.testtube.app.AppGraph;
import com.testtube.app.R;
import com.testtube.app.nav.TabManager;
import com.testtube.app.player.TestTubePlayer;
import com.testtube.app.player.TestTubePlayerView;
import com.testtube.app.player.controller.Controller;
import com.testtube.app.player.controller.gesture.ZoomTouchListener;
import com.testtube.app.player.engine.Engine;

/**
 * Objects that live as long as one {@link MainActivity}.
 */
@UnstableApi
public final class ActivityGraph {
	@NonNull
	public final AppGraph app;
	@NonNull
	public final TabManager tabManager;
	@NonNull
	public final TestTubePlayer player;

	ActivityGraph(@NonNull AppCompatActivity activity, @NonNull AppGraph app) {
		this.app = app;
		TestTubePlayer[] holder = new TestTubePlayer[1];
		tabManager = new TabManager(() -> holder[0], app.playlistSource());
		TestTubePlayerView playerView = activity.findViewById(R.id.playerView);
		playerView.bind(app.sponsorBlockManager());
		Engine engine = new Engine(app.context(), playerView, app.simpleCache(), app.playerPreferences(),
						tabManager, app.sponsorBlockManager(), app.queueRepository(), app.youtubeExtractor(),
						app.contentFilters(), app.watchHistory());
		engine.setFeedClient(app.feedClient());
		engine.setUpNextListener((title, delayMs, onCancel) -> activity.runOnUiThread(() -> {
			if (activity.isFinishing() || activity.isDestroyed()) return;
			Snackbar
							.make(activity.findViewById(android.R.id.content),
											activity.getString(R.string.up_next_title, title), (int) delayMs)
							.setAction(R.string.up_next_cancel, v -> onCancel.run())
							.show();
		}));
		Controller controller = new Controller(activity, playerView, engine, app.playerPreferences(),
						new ZoomTouchListener(activity, playerView), tabManager);
		player = new TestTubePlayer(activity, app.youtubeExtractor(), playerView, controller, engine,
						app.sponsorBlockManager(), app.queueRepository(), app.playerPreferences(),
						app.playerStateStore(), app.executor());
		holder[0] = player;
	}
}
