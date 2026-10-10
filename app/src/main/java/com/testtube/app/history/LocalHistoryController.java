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
import com.squareup.picasso.Picasso;
import com.testtube.app.R;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Embedded local-history view: a day-grouped list of played videos shown inside MainActivity
 * instead of a separate screen.
 */
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
	private final Adapter adapter;
	@NonNull
	private final TextView empty;

	public LocalHistoryController(@NonNull View root,
	                              @NonNull WatchHistory history,
	                              @NonNull Listener listener) {
		this.history = history;
		this.listener = listener;
		this.empty = root.findViewById(R.id.history_empty);
		this.adapter = new Adapter();

		RecyclerView list = root.findViewById(R.id.history_list);
		list.setLayoutManager(new LinearLayoutManager(root.getContext()));
		list.setAdapter(adapter);

		ImageButton clear = root.findViewById(R.id.history_clear);
		clear.setOnClickListener(v -> new MaterialAlertDialogBuilder(root.getContext())
				.setMessage(R.string.watch_history_clear_confirm)
				.setNegativeButton(R.string.cancel, null)
				.setPositiveButton(R.string.watch_history_clear, (dialog, which) -> {
					history.clear();
					reload();
				})
				.show());
	}

	/**
	 * Reloads the entries from the store. Call every time the view becomes visible.
	 */
	public void reload() {
		List<WatchHistory.Entry> entries = history.entries();
		List<Object> rows = new ArrayList<>();
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
		empty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
	}

	@NonNull
	private static CharSequence dayLabel(@NonNull View view, @NonNull LocalDate day) {
		long millis = day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
		return DateUtils.getRelativeTimeSpanString(millis, System.currentTimeMillis(),
				DateUtils.DAY_IN_MILLIS);
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
				headerHolder.header.setText(dayLabel(headerHolder.header, day));
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
			header = itemView.findViewById(R.id.header);
		}
	}

	private final class VideoHolder extends RecyclerView.ViewHolder {
		@NonNull
		private final com.google.android.material.imageview.ShapeableImageView thumbnail;
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
			String thumbnailUrl = entry.thumbnailUrl() != null ? entry.thumbnailUrl()
					: "https://i.ytimg.com/vi/" + entry.videoId() + "/mqdefault.jpg";
			Picasso.get().load(thumbnailUrl)
					.placeholder(R.drawable.bg_thumbnail_placeholder)
					.into(thumbnail);
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
