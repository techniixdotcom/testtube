package com.testtube.app.history;

import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.imageview.ShapeableImageView;
import com.testtube.app.R;
import com.testtube.app.util.ImageUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/** The Library's History tab: watched videos grouped by day. */
public final class LocalHistoryController {
	private static final int TYPE_HEADER = 0;
	private static final int TYPE_VIDEO = 1;

	public interface Listener {
		void onPlay(@NonNull String videoId);
	}

	@NonNull
	private final WatchHistory history;
	@NonNull
	private final Listener listener;
	@NonNull
	private final Runnable onChanged;
	@NonNull
	private final Adapter adapter = new Adapter();
	@NonNull
	private final RecyclerView list;
	@NonNull
	private final TextView empty;

	/**
	 * @param root      a view holding {@code history_list} and {@code history_empty}
	 * @param onChanged runs after every reload, so the host can update its clear button
	 */
	public LocalHistoryController(@NonNull View root,
	                              @NonNull WatchHistory history,
	                              @NonNull Listener listener,
	                              @NonNull Runnable onChanged) {
		this.history = history;
		this.listener = listener;
		this.onChanged = onChanged;
		this.empty = root.findViewById(R.id.history_empty);
		this.list = root.findViewById(R.id.history_list);
		list.setLayoutManager(new LinearLayoutManager(root.getContext()));
		list.setAdapter(adapter);
	}

	public boolean isEmpty() {
		return adapter.rows.isEmpty();
	}

	/** Asks, then removes every video from the history. */
	public void confirmClear() {
		new MaterialAlertDialogBuilder(list.getContext())
						.setTitle(R.string.watch_history_clear)
						.setMessage(R.string.watch_history_clear_confirm)
						.setNegativeButton(R.string.cancel, null)
						.setPositiveButton(R.string.clear, (dialog, which) -> {
							history.clear();
							reload();
						})
						.show();
	}

	/** Reloads from the store; call whenever the tab becomes visible. */
	public void reload() {
		List<WatchHistory.Entry> entries = history.entries();
		List<Object> rows = new ArrayList<>(entries.size() + 8);
		LocalDate lastDay = null;
		ZoneId zone = ZoneId.systemDefault();
		for (WatchHistory.Entry entry : entries) {
			LocalDate day = Instant.ofEpochMilli(entry.watchedAt()).atZone(zone).toLocalDate();
			if (!day.equals(lastDay)) {
				lastDay = day;
				rows.add(day);
			}
			rows.add(entry);
		}
		adapter.rows = rows;
		adapter.notifyDataSetChanged();
		boolean none = rows.isEmpty();
		empty.setVisibility(none ? View.VISIBLE : View.GONE);
		list.setVisibility(none ? View.GONE : View.VISIBLE);
		onChanged.run();
	}

	@NonNull
	private static CharSequence dayLabel(@NonNull LocalDate day) {
		long millis = day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
		return DateUtils.getRelativeTimeSpanString(millis, System.currentTimeMillis(), DateUtils.DAY_IN_MILLIS);
	}

	private final class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
		@NonNull
		private List<Object> rows = new ArrayList<>();

		@Override
		public int getItemViewType(int position) {
			return rows.get(position) instanceof LocalDate ? TYPE_HEADER : TYPE_VIDEO;
		}

		@Override
		public int getItemCount() {
			return rows.size();
		}

		@NonNull
		@Override
		public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
			LayoutInflater inflater = LayoutInflater.from(parent.getContext());
			if (viewType == TYPE_HEADER) {
				return new HeaderHolder(inflater.inflate(R.layout.item_local_history_header, parent, false));
			}
			return new VideoHolder(inflater.inflate(R.layout.item_local_history, parent, false));
		}

		@Override
		public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
			Object row = rows.get(position);
			if (holder instanceof HeaderHolder headerHolder && row instanceof LocalDate day) {
				headerHolder.header.setText(dayLabel(day));
			} else if (holder instanceof VideoHolder videoHolder && row instanceof WatchHistory.Entry entry) {
				videoHolder.bind(entry);
			}
		}
	}

	private static final class HeaderHolder extends RecyclerView.ViewHolder {
		@NonNull
		final TextView header;

		HeaderHolder(@NonNull View itemView) {
			super(itemView);
			header = (TextView) itemView;
		}
	}

	private final class VideoHolder extends RecyclerView.ViewHolder {
		@NonNull
		private final ShapeableImageView thumbnail;
		@NonNull
		private final TextView title;
		@NonNull
		private final TextView author;
		@NonNull
		private final TextView when;
		@NonNull
		private final ImageButton more;

		VideoHolder(@NonNull View itemView) {
			super(itemView);
			thumbnail = itemView.findViewById(R.id.thumbnail);
			title = itemView.findViewById(R.id.title);
			author = itemView.findViewById(R.id.author);
			when = itemView.findViewById(R.id.when);
			more = itemView.findViewById(R.id.more);
		}

		void bind(@NonNull WatchHistory.Entry entry) {
			title.setText(entry.title() != null ? entry.title() : entry.videoId());
			author.setText(entry.author());
			author.setVisibility(entry.author() != null ? View.VISIBLE : View.GONE);
			when.setText(DateUtils.getRelativeTimeSpanString(entry.watchedAt(),
							System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS));
			ImageUtils.loadThumb(thumbnail, entry.thumbnailUrl() != null ? entry.thumbnailUrl()
							: "https://i.ytimg.com/vi/" + entry.videoId() + "/mqdefault.jpg");
			itemView.setOnClickListener(v -> listener.onPlay(entry.videoId()));
			more.setOnClickListener(v -> {
				PopupMenu menu = new PopupMenu(v.getContext(), v);
				menu.getMenu().add(0, 0, 0, R.string.watch_history_remove);
				menu.setOnMenuItemClickListener(item -> {
					history.remove(entry.videoId());
					reload();
					return true;
				});
				menu.show();
			});
		}
	}
}
