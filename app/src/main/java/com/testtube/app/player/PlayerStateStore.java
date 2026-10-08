package com.testtube.app.player;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

/**
 * Current video / mini player state for the UI. We keep our own copy instead of reading the
 * LiveData back because {@link MutableLiveData#postValue} is async and quick updates would
 * overwrite each other with stale values.
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
