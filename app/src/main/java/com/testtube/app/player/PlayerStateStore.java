package com.testtube.app.player;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

/**
 * Current video and mini player state for the UI. The state lives here instead of being read
 * back from the LiveData: {@link MutableLiveData#postValue} is asynchronous, so quick updates
 * would overwrite each other with stale values.
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
