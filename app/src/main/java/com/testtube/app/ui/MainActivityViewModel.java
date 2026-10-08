package com.testtube.app.ui;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;
import androidx.lifecycle.ViewModel;

import com.testtube.app.player.PlayerState;
import com.testtube.app.player.PlayerStateStore;
import com.testtube.app.player.common.PlayerLoopMode;
import com.testtube.app.player.common.PlayerPreferences;
import com.testtube.app.player.queue.QueueItem;
import com.testtube.app.player.queue.QueueRepository;
import com.testtube.app.player.queue.QueueState;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class MainActivityViewModel extends ViewModel {
	@NonNull
	private final QueueRepository queueRepository;
	@NonNull
	private final MediatorLiveData<UiState> state = new MediatorLiveData<>();
	@NonNull
	private QueueState queue = new QueueState(false, List.of());
	@NonNull
	private PlayerState player = new PlayerState(null, false);
	@NonNull
	private PlayerLoopMode loopMode;

	public MainActivityViewModel(@NonNull QueueRepository queueRepository,
	                             @NonNull PlayerStateStore playerStateStore,
	                             @NonNull PlayerPreferences prefs) {
		this.queueRepository = queueRepository;
		this.loopMode = prefs.getLoopMode();
		state.addSource(queueRepository.getState(), value -> {
			queue = value != null ? value : new QueueState(false, List.of());
			publish();
		});
		state.addSource(playerStateStore.getState(), value -> {
			player = value != null ? value : new PlayerState(null, false);
			publish();
		});
		state.addSource(prefs.getLoopModeState(), value -> {
			loopMode = value != null ? value : PlayerLoopMode.PLAYLIST_NEXT;
			publish();
		});
		publish();
	}

	@NonNull
	public LiveData<UiState> getState() {
		return state;
	}

	public void setQueueEnabled(boolean enabled) {
		queueRepository.setEnabled(enabled);
	}

	public void removeQueueItem(@NonNull String videoId) {
		queueRepository.remove(videoId);
	}

	public void clearQueue() {
		queueRepository.clear();
	}

	public void moveQueue(@NonNull List<QueueItem> order) {
		List<QueueItem> items = new ArrayList<>(queueRepository.getItems());
		for (int to = 0; to < order.size(); to++) {
			String videoId = order.get(to).getVideoId();
			if (videoId == null) continue;
			int from = -1;
			for (int i = 0; i < items.size(); i++) {
				if (Objects.equals(videoId, items.get(i).getVideoId())) {
					from = i;
					break;
				}
			}
			if (from < 0 || from == to) continue;
			if (queueRepository.move(from, to)) {
				QueueItem item = items.remove(from);
				items.add(to, item);
			}
		}
	}

	private void publish() {
		state.setValue(new UiState(
						queue.enabled(),
						queue.items(),
						player.videoId(),
						loopMode,
						player.miniPlayer()));
	}

	public record UiState(boolean queueEnabled,
	                      @NonNull List<QueueItem> items,
	                      @Nullable String videoId,
	                      @NonNull PlayerLoopMode loopMode,
	                      boolean miniPlayer) {
	}
}
