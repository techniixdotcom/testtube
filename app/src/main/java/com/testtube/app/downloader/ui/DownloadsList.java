package com.testtube.app.downloader.ui;

import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.AttrRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.media3.common.util.UnstableApi;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;

import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.imageview.ShapeableImageView;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.testtube.app.AppGraph;
import com.testtube.app.R;
import com.testtube.app.downloader.core.DownloadTaskIdHelper;
import com.testtube.app.downloader.core.history.DownloadHistoryRepository;
import com.testtube.app.downloader.core.history.DownloadRecord;
import com.testtube.app.downloader.core.history.DownloadStatus;
import com.testtube.app.downloader.core.history.DownloadType;
import com.testtube.app.downloader.service.DownloadService;
import com.testtube.app.extractor.YoutubeExtractor;
import com.testtube.app.util.DownloadStorageUtils;
import com.testtube.app.util.ImageUtils;
import com.testtube.app.util.ToastUtils;
import com.testtube.app.util.ViewUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The list of download records with open, pause, retry and delete. Shown in the Library's
 * Downloads tab and, for the items of one playlist, in {@link DownloadActivity}.
 * <p>
 * The host calls {@link #start()} while the list is on screen and {@link #stop()} when it leaves,
 * so progress updates and the service binding only cost anything while someone is looking.
 */
@UnstableApi
public final class DownloadsList {
	private static final long REFRESH_COALESCE_MS = 80L;
	/** Size of the icon above the "no downloads" message; shared with the Library's other tabs. */
	public static final float EMPTY_ICON_DP = 40f;
	private static final int MENU_OPEN = 0;
	private static final int MENU_COPY_ID = 1;
	private static final int MENU_RETRY = 2;
	private static final int MENU_DELETE = 3;
	private static final int MENU_REDOWNLOAD = 4;
	private static final int MENU_CANCEL = 5;
	private static final int MENU_PAUSE_RESUME = 6;

	@NonNull
	private final AppCompatActivity activity;
	@Nullable
	private final String parentId;
	@NonNull
	private final Runnable onChanged;
	@NonNull
	private final DownloadHistoryRepository repository;
	@NonNull
	private final YoutubeExtractor youtubeExtractor;
	@NonNull
	private final RecyclerView recyclerView;
	@NonNull
	private final TextView emptyView;
	@NonNull
	private final Adapter adapter = new Adapter();
	@NonNull
	private final Handler handler = new Handler(Looper.getMainLooper());
	@NonNull
	private final ExecutorService recordExecutor = Executors.newSingleThreadExecutor();
	@NonNull
	private final Object refreshLock = new Object();
	@NonNull
	private final Set<String> pendingTaskIds = new LinkedHashSet<>();
	private boolean refreshScheduled;
	private boolean started;
	@Nullable
	private DownloadService downloadService;
	private boolean bound;

	private final ServiceConnection connection = new ServiceConnection() {
		@Override
		public void onServiceConnected(ComponentName name, IBinder service) {
			downloadService = ((DownloadService.DownloadBinder) service).getService();
		}

		@Override
		public void onServiceDisconnected(ComponentName name) {
			downloadService = null;
		}
	};

	private final BroadcastReceiver receiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			if (DownloadService.ACTION_DOWNLOAD_RECORD_UPDATED.equals(intent.getAction())) {
				scheduleRecordRefresh(intent.getStringExtra(DownloadService.EXTRA_TASK_ID));
			}
		}
	};

	/**
	 * @param activity  the host; it should implement {@link DownloadPermissionHost} for re-downloads
	 * @param root      a view holding {@code download_list} and {@code download_empty}
	 * @param parentId  a playlist's task id to list its items, or null for all downloads
	 * @param onChanged runs whenever the list gains or loses rows
	 */
	public DownloadsList(@NonNull AppCompatActivity activity, @NonNull View root,
	                     @Nullable String parentId, @NonNull Runnable onChanged) {
		this.activity = activity;
		this.parentId = parentId != null && !parentId.isBlank() ? parentId : null;
		this.onChanged = onChanged;
		AppGraph app = AppGraph.of(activity);
		repository = app.downloadHistory();
		youtubeExtractor = app.youtubeExtractor();
		recyclerView = root.findViewById(R.id.download_list);
		emptyView = root.findViewById(R.id.download_empty);
		ViewUtils.sizeCompoundDrawables(emptyView, EMPTY_ICON_DP);
		recyclerView.setLayoutManager(new LinearLayoutManager(activity));
		recyclerView.setAdapter(adapter);
		if (recyclerView.getItemAnimator() instanceof SimpleItemAnimator animator) {
			animator.setSupportsChangeAnimations(false);
		}
	}

	/** Starts listening for progress and loads the records. */
	public void start() {
		if (started) return;
		started = true;
		ContextCompat.registerReceiver(activity, receiver,
						new IntentFilter(DownloadService.ACTION_DOWNLOAD_RECORD_UPDATED), ContextCompat.RECEIVER_NOT_EXPORTED);
		bound = activity.bindService(new Intent(activity, DownloadService.class), connection, Context.BIND_AUTO_CREATE);
		loadRecords();
	}

	public void stop() {
		if (!started) return;
		started = false;
		activity.unregisterReceiver(receiver);
		if (bound) {
			activity.unbindService(connection);
			bound = false;
		}
		downloadService = null;
		handler.removeCallbacksAndMessages(null);
		synchronized (refreshLock) {
			pendingTaskIds.clear();
			refreshScheduled = false;
		}
	}

	public void release() {
		stop();
		recordExecutor.shutdownNow();
	}

	public boolean isEmpty() {
		return adapter.getItemCount() == 0;
	}

	/** Asks, then removes every record. Files that were downloaded stay where they are. */
	public void confirmClear() {
		new MaterialAlertDialogBuilder(activity)
						.setTitle(R.string.downloads_clear)
						.setMessage(R.string.downloads_clear_confirm)
						.setNegativeButton(R.string.cancel, null)
						.setPositiveButton(R.string.clear, (d, w) -> {
							repository.clear();
							loadRecords();
						}).show();
	}

	private void loadRecords() {
		recordExecutor.execute(() -> {
			List<DownloadRecord> verified = loadVisibleRecords();
			activity.runOnUiThread(() -> {
				adapter.setItems(verified);
				updateEmptyState();
			});
		});
	}

	/** Progress broadcasts arrive many times a second; they are merged into one update. */
	private void scheduleRecordRefresh(@Nullable String taskId) {
		if (taskId == null || taskId.isBlank()) {
			loadRecords();
			return;
		}
		synchronized (refreshLock) {
			pendingTaskIds.add(taskId);
			if (refreshScheduled) return;
			refreshScheduled = true;
		}
		handler.postDelayed(() -> {
			final List<String> taskIds;
			synchronized (refreshLock) {
				refreshScheduled = false;
				if (pendingTaskIds.isEmpty()) return;
				taskIds = new ArrayList<>(pendingTaskIds);
				pendingTaskIds.clear();
			}
			recordExecutor.execute(() -> {
				List<RecordMutation> mutations = buildRecordMutations(taskIds);
				activity.runOnUiThread(() -> applyRecordMutations(mutations));
			});
		}, REFRESH_COALESCE_MS);
	}

	@NonNull
	private List<DownloadRecord> loadVisibleRecords() {
		List<DownloadRecord> source = parentId != null
						? repository.getChildrenSorted(parentId)
						: repository.getRootsSorted();
		List<DownloadRecord> verified = new ArrayList<>(source.size());
		for (DownloadRecord record : source) {
			DownloadRecord checked = verifyRecord(record);
			if (checked != null) verified.add(checked);
		}
		return verified;
	}

	/** A finished download whose file was deleted elsewhere is dropped from the list. */
	@Nullable
	private DownloadRecord verifyRecord(@NonNull DownloadRecord record) {
		if (record.getType() != DownloadType.PLAYLIST
						&& record.getStatus() == DownloadStatus.COMPLETED
						&& DownloadStorageUtils.doesNotExist(activity, record.getOutputPath())) {
			repository.remove(record.getTaskId());
			return null;
		}
		return record;
	}

	@NonNull
	private List<RecordMutation> buildRecordMutations(@NonNull List<String> taskIds) {
		List<DownloadRecord> allRecords = repository.getAllSorted();
		Map<String, DownloadRecord> recordsById = new LinkedHashMap<>(allRecords.size());
		for (DownloadRecord record : allRecords) {
			recordsById.put(record.getTaskId(), record);
		}

		Set<String> targetIds = new LinkedHashSet<>();
		for (String taskId : taskIds) {
			DownloadRecord updated = recordsById.get(taskId);
			if (updated == null) {
				targetIds.add(taskId);
			} else if (parentId != null) {
				// A playlist's page only shows its own items
				if (parentId.equals(updated.getParentId())) targetIds.add(taskId);
			} else {
				// The main list shows a playlist's row instead of its items
				String updatedParent = updated.getParentId();
				targetIds.add(updatedParent != null && !updatedParent.isBlank() ? updatedParent : taskId);
			}
		}

		List<RecordMutation> mutations = new ArrayList<>(targetIds.size());
		for (String taskId : targetIds) {
			DownloadRecord updated = recordsById.get(taskId);
			DownloadRecord checked = updated == null ? null : verifyRecord(updated);
			mutations.add(checked == null ? RecordMutation.remove(taskId) : RecordMutation.upsert(checked));
		}
		return mutations;
	}

	private void applyRecordMutations(@NonNull List<RecordMutation> mutations) {
		if (mutations.isEmpty()) return;
		for (RecordMutation mutation : mutations) {
			if (mutation.record == null) {
				adapter.removeItemByTaskId(mutation.taskId);
			} else {
				adapter.upsertItem(mutation.record);
			}
		}
		updateEmptyState();
	}

	private void updateEmptyState() {
		boolean empty = adapter.getItemCount() == 0;
		emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
		recyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
		onChanged.run();
	}

	// Actions

	private void open(@NonNull DownloadRecord record) {
		if (record.getType() == DownloadType.PLAYLIST) {
			Intent intent = new Intent(activity, DownloadActivity.class);
			intent.putExtra(DownloadActivity.EXTRA_PARENT_ID, record.getTaskId());
			intent.putExtra(DownloadActivity.EXTRA_PARENT_TITLE, titleOf(record));
			activity.startActivity(intent);
			return;
		}
		Uri uri = DownloadStorageUtils.doesNotExist(activity, record.getOutputPath())
						? null
						: DownloadStorageUtils.getOpenUri(activity, record.getOutputPath());
		if (uri == null) {
			ToastUtils.show(activity, R.string.file_not_found);
			loadRecords();
			return;
		}
		String type = DownloadStorageUtils.getMimeType(activity, record.getOutputPath(), record.getFileName());
		Intent intent = new Intent(Intent.ACTION_VIEW)
						.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
						.setDataAndType(uri, type != null ? type : "*/*");
		try {
			activity.startActivity(intent);
		} catch (RuntimeException e) {
			ToastUtils.show(activity, R.string.application_not_found);
		}
	}

	private void forEachTask(@NonNull DownloadRecord record, @NonNull TaskAction action) {
		if (record.getType() == DownloadType.PLAYLIST) {
			for (DownloadRecord child : repository.getChildrenSorted(record.getTaskId())) {
				action.run(child.getTaskId());
				scheduleRecordRefresh(child.getTaskId());
			}
		} else {
			action.run(record.getTaskId());
		}
		scheduleRecordRefresh(record.getTaskId());
	}

	private void cancel(@NonNull DownloadRecord record) {
		DownloadService service = downloadService;
		if (service != null) forEachTask(record, service::cancel);
	}

	private void togglePause(@NonNull DownloadRecord record) {
		DownloadStatus status = record.getStatus();
		if (status == DownloadStatus.FAILED || status == DownloadStatus.CANCELED) {
			redownload(record);
			return;
		}
		DownloadService service = downloadService;
		if (service == null) return;
		if (status == DownloadStatus.PAUSED) {
			forEachTask(record, service::resume);
		} else {
			forEachTask(record, service::pause);
		}
	}

	private void redownload(@NonNull DownloadRecord record) {
		if (record.getType() == DownloadType.PLAYLIST) return;
		String url = "https://m.youtube.com/watch?v=" + videoIdOf(record);
		new DownloadDialog(url, activity, youtubeExtractor).show();
	}

	private void copyVideoId(@NonNull DownloadRecord record) {
		ClipboardManager clipboard = ContextCompat.getSystemService(activity, ClipboardManager.class);
		if (clipboard == null || record.getType() == DownloadType.PLAYLIST) return;
		clipboard.setPrimaryClip(ClipData.newPlainText("videoId", videoIdOf(record)));
		ToastUtils.show(activity, R.string.vid_copied);
	}

	private void confirmDelete(@NonNull DownloadRecord record) {
		View view = LayoutInflater.from(activity).inflate(R.layout.dialog_delete_record, null);
		MaterialCheckBox deleteFile = view.findViewById(R.id.checkbox_delete_file);
		new MaterialAlertDialogBuilder(activity)
						.setTitle(R.string.delete_record)
						.setView(view)
						.setNegativeButton(R.string.cancel, null)
						.setPositiveButton(R.string.delete_record, (d, w) -> delete(record, deleteFile.isChecked()))
						.show();
	}

	private void delete(@NonNull DownloadRecord record, boolean deleteFiles) {
		DownloadService service = downloadService;
		List<DownloadRecord> targets = record.getType() == DownloadType.PLAYLIST
						? repository.getChildrenSorted(record.getTaskId())
						: List.of(record);
		for (DownloadRecord target : targets) {
			if (service != null) service.cancel(target.getTaskId());
			if (deleteFiles && !target.getOutputPath().isBlank()) {
				DownloadStorageUtils.delete(activity, target.getOutputPath());
			}
		}
		if (record.getType() == DownloadType.PLAYLIST) {
			repository.removeWithChildren(record.getTaskId());
		} else {
			repository.remove(record.getTaskId());
		}
		adapter.removeItemByTaskId(record.getTaskId());
		updateEmptyState();
	}

	// Helpers

	@NonNull
	private static String videoIdOf(@NonNull DownloadRecord record) {
		String videoId = record.getVideoId();
		return videoId.length() == 11 ? videoId : DownloadTaskIdHelper.extractVidId(record.getTaskId());
	}

	@NonNull
	private static String titleOf(@NonNull DownloadRecord record) {
		String title = record.getTitle();
		return title != null && !title.isBlank() ? title : record.getFileName();
	}

	private static boolean isActive(@NonNull DownloadStatus status) {
		return status == DownloadStatus.RUNNING
						|| status == DownloadStatus.QUEUED
						|| status == DownloadStatus.MERGING
						|| status == DownloadStatus.PAUSED;
	}

	@NonNull
	private static String formatMB(long bytes) {
		return String.format(Locale.getDefault(), "%.1f", bytes / 1024.0 / 1024.0);
	}

	private int color(@AttrRes int attr) {
		return ViewUtils.themeColor(activity, attr);
	}

	private interface TaskAction {
		void run(@NonNull String taskId);
	}

	private record RecordMutation(@NonNull String taskId, @Nullable DownloadRecord record) {
		@NonNull
		static RecordMutation upsert(@NonNull DownloadRecord record) {
			return new RecordMutation(record.getTaskId(), record);
		}

		@NonNull
		static RecordMutation remove(@NonNull String taskId) {
			return new RecordMutation(taskId, null);
		}
	}

	private final class Adapter extends RecyclerView.Adapter<Adapter.Holder> {
		private final Object payloadProgress = new Object();
		@NonNull
		private final List<DownloadRecord> items = new ArrayList<>();

		void setItems(@NonNull List<DownloadRecord> newItems) {
			List<DownloadRecord> oldItems = new ArrayList<>(items);
			DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
				@Override
				public int getOldListSize() {
					return oldItems.size();
				}

				@Override
				public int getNewListSize() {
					return newItems.size();
				}

				@Override
				public boolean areItemsTheSame(int oldPosition, int newPosition) {
					return Objects.equals(oldItems.get(oldPosition).getTaskId(), newItems.get(newPosition).getTaskId());
				}

				@Override
				public boolean areContentsTheSame(int oldPosition, int newPosition) {
					return Objects.equals(oldItems.get(oldPosition), newItems.get(newPosition));
				}
			});
			items.clear();
			items.addAll(newItems);
			diff.dispatchUpdatesTo(this);
		}

		void upsertItem(@NonNull DownloadRecord record) {
			int index = indexOf(record.getTaskId());
			if (index >= 0) {
				items.set(index, record);
				notifyItemChanged(index, payloadProgress);
				return;
			}
			int insertAt = items.size();
			for (int i = 0; i < items.size(); i++) {
				if (record.getCreatedAt() > items.get(i).getCreatedAt()) {
					insertAt = i;
					break;
				}
			}
			items.add(insertAt, record);
			notifyItemInserted(insertAt);
		}

		void removeItemByTaskId(@NonNull String taskId) {
			int index = indexOf(taskId);
			if (index < 0) return;
			items.remove(index);
			notifyItemRemoved(index);
		}

		private int indexOf(@NonNull String taskId) {
			for (int i = 0; i < items.size(); i++) {
				if (Objects.equals(items.get(i).getTaskId(), taskId)) return i;
			}
			return -1;
		}

		@NonNull
		@Override
		public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
			return new Holder(LayoutInflater.from(parent.getContext())
							.inflate(R.layout.item_download_record, parent, false));
		}

		@Override
		public void onBindViewHolder(@NonNull Holder holder, int position) {
			holder.bind(items.get(position));
		}

		@Override
		public void onBindViewHolder(@NonNull Holder holder, int position, @NonNull List<Object> payloads) {
			if (payloads.isEmpty()) {
				holder.bind(items.get(position));
			} else {
				holder.bindProgress(items.get(position));
			}
		}

		@Override
		public int getItemCount() {
			return items.size();
		}

		final class Holder extends RecyclerView.ViewHolder {
			private final ShapeableImageView thumbnail;
			private final TextView title;
			private final TextView sizeDownloaded;
			private final TextView statusChip;
			private final TextView typeChip;
			private final LinearProgressIndicator progress;
			private final ImageButton more;

			Holder(@NonNull View itemView) {
				super(itemView);
				thumbnail = itemView.findViewById(R.id.thumbnail);
				title = itemView.findViewById(R.id.title);
				sizeDownloaded = itemView.findViewById(R.id.size_downloaded);
				statusChip = itemView.findViewById(R.id.status_chip);
				typeChip = itemView.findViewById(R.id.type_chip);
				progress = itemView.findViewById(R.id.progress);
				more = itemView.findViewById(R.id.more);
			}

			void bind(@NonNull DownloadRecord record) {
				title.setText(titleOf(record));
				typeChip.setText(switch (record.getType()) {
					case PLAYLIST -> R.string.type_playlist;
					case VIDEO -> R.string.type_video;
					case AUDIO -> R.string.type_audio;
					case SUBTITLE -> R.string.type_subtitle;
					case THUMBNAIL -> R.string.type_thumbnail;
				});
				String thumbUrl = record.getThumbnailUrl();
				if (thumbUrl != null && !thumbUrl.isBlank()) {
					ImageUtils.loadThumb(thumbnail, thumbUrl);
				} else {
					String videoId = videoIdOf(record);
					if (videoId.isBlank()) ImageUtils.showThumb(thumbnail);
					else ImageUtils.loadThumb(thumbnail, "https://i.ytimg.com/vi/" + videoId + "/mqdefault.jpg");
				}
				bindProgress(record);
			}

			/** The parts that change while a download runs. */
			void bindProgress(@NonNull DownloadRecord record) {
				DownloadStatus status = record.getStatus();
				boolean active = isActive(status);
				bindStatus(status);
				if (record.getType() == DownloadType.PLAYLIST) {
					sizeDownloaded.setText(activity.getString(R.string.download_playlist_summary,
									record.getDoneCount(), record.getItemCount(), record.getProgress()));
					progress.setVisibility(record.getItemCount() > 0 ? View.VISIBLE : View.GONE);
					progress.setIndeterminate(false);
					progress.setProgressCompat(record.getProgress(), false);
				} else {
					sizeDownloaded.setText(record.getTotalSize() > 0
									? activity.getString(R.string.download_progress_with_total,
									formatMB(record.getDownloadedSize()), formatMB(record.getTotalSize()), record.getProgress())
									: activity.getString(R.string.download_progress_simple, formatMB(record.getDownloadedSize())));
					progress.setVisibility(active ? View.VISIBLE : View.GONE);
					if (active) {
						progress.setIndeterminate(status == DownloadStatus.MERGING || status == DownloadStatus.QUEUED);
						if (!progress.isIndeterminate()) progress.setProgressCompat(record.getProgress(), false);
					}
				}
				more.setOnClickListener(v -> showMenu(v, record));
				itemView.setOnClickListener(v -> {
					if (status == DownloadStatus.COMPLETED) open(record);
					else togglePause(record);
				});
			}

			private void bindStatus(@NonNull DownloadStatus status) {
				statusChip.setText(switch (status) {
					case RUNNING -> R.string.status_downloading_simple;
					case QUEUED -> R.string.status_queued;
					case MERGING -> R.string.status_merging;
					case COMPLETED -> R.string.status_completed;
					case FAILED -> R.string.status_failed;
					case CANCELED -> R.string.status_cancelled;
					case PAUSED -> R.string.status_paused;
				});
				final int background;
				final int text;
				switch (status) {
					case COMPLETED -> {
						background = com.google.android.material.R.attr.colorSecondaryContainer;
						text = com.google.android.material.R.attr.colorOnSecondaryContainer;
					}
					case FAILED, CANCELED -> {
						background = com.google.android.material.R.attr.colorErrorContainer;
						text = com.google.android.material.R.attr.colorOnErrorContainer;
					}
					default -> {
						background = com.google.android.material.R.attr.colorPrimaryContainer;
						text = com.google.android.material.R.attr.colorOnPrimaryContainer;
					}
				}
				ViewCompat.setBackgroundTintList(statusChip, ColorStateList.valueOf(color(background)));
				statusChip.setTextColor(color(text));
			}

			private void showMenu(@NonNull View anchor, @NonNull DownloadRecord record) {
				PopupMenu popup = new PopupMenu(anchor.getContext(), anchor);
				Menu menu = popup.getMenu();
				DownloadStatus status = record.getStatus();
				boolean active = isActive(status);
				int pauseLabel = status == DownloadStatus.PAUSED
								? R.string.download_action_resume : R.string.download_action_pause;
				if (record.getType() == DownloadType.PLAYLIST) {
					if (active) {
						menu.add(0, MENU_PAUSE_RESUME, 0, pauseLabel);
						menu.add(0, MENU_CANCEL, 1, R.string.download_action_cancel);
					}
					menu.add(0, MENU_DELETE, 2, R.string.delete_record);
				} else {
					if (status == DownloadStatus.COMPLETED) {
						menu.add(0, MENU_OPEN, 0, R.string.download_action_open_file);
						menu.add(0, MENU_REDOWNLOAD, 1, R.string.download_action_redownload);
					} else if (status == DownloadStatus.FAILED || status == DownloadStatus.CANCELED) {
						menu.add(0, MENU_RETRY, 0, R.string.download_action_retry);
					} else {
						menu.add(0, MENU_PAUSE_RESUME, 0, pauseLabel);
						menu.add(0, MENU_CANCEL, 1, R.string.download_action_cancel);
					}
					menu.add(0, MENU_COPY_ID, 2, R.string.download_action_copy_video_id);
					menu.add(0, MENU_DELETE, 3, R.string.delete_record);
				}
				popup.setOnMenuItemClickListener(item -> {
					switch (item.getItemId()) {
						case MENU_OPEN -> open(record);
						case MENU_COPY_ID -> copyVideoId(record);
						case MENU_RETRY, MENU_REDOWNLOAD -> redownload(record);
						case MENU_DELETE -> confirmDelete(record);
						case MENU_CANCEL -> cancel(record);
						case MENU_PAUSE_RESUME -> togglePause(record);
						default -> {
							return false;
						}
					}
					return true;
				});
				popup.show();
			}
		}
	}
}
