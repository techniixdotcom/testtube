package com.testtube.app.player;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

/**
 * Publishes the current video and mini-player state to the UI.
 * The source of truth is kept here rather than read back from the LiveData, because
 * {@link MutableLiveData#postValue} is asynchronous and quick successive updates would
 * otherwise overwrite each other with stale values.
 */
public final class PlayerStateStore {
	@NonNull
	private final MutableLiveData<PlayerState> state = new MutableLiveData<>(new PlayerState(null, false));
	@Nullable
	private String videoId;
	private boolean miniPlayer;

	@NonNull
	public LiveData<PlayerState> getState() {
		return state;
	}

	public synchronized void setVideoId(@Nullable String videoId) {
		this.videoId = videoId;
		publish();
	}

	public synchronized void setInMiniPlayer(boolean inMiniPlayer) {
		this.miniPlayer = inMiniPlayer;
		publish();
	}

	public synchronized void clear() {
		videoId = null;
		miniPlayer = false;
		publish();
	}

	private void publish() {
		state.postValue(new PlayerState(videoId, miniPlayer));
	}
}
