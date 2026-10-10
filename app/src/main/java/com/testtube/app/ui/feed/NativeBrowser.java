package com.testtube.app.ui.feed;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.testtube.app.R;
import com.testtube.app.extractor.FeedClient;
import com.testtube.app.extractor.FeedCache;
import com.testtube.app.extractor.FeedItem;
import com.testtube.app.extractor.LocalSubscriptions;
import com.testtube.app.filter.ContentFilters;

import org.schabi.newpipe.extractor.Page;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import java.util.concurrent.CompletionException;

/**
 * Native Home, Subscriptions and Search screens. Each screen keeps its own list and scroll
 * position, so switching between them is instant.
 */
public final class NativeBrowser {
	private static final int LOAD_MORE_THRESHOLD = 6;
	private static final int MAX_EMPTY_PAGES = 3;
	private static final long RETRY_AFTER_MS = 3_000L;

	public enum Mode {
		HOME,
		SUBSCRIPTIONS,
		SEARCH
	}

	public interface Host {
		void openVideo(@NonNull FeedItem item);

		void openPage(@NonNull String url);

		void showMenu(@NonNull FeedItem item);

		/**
		 * The account cookies are missing or expired.
		 */
		void signIn();

		/**
		 * True when the Subscriptions tab should use the YouTube account.
		 */
		boolean accountEnabled();

		void setAccountEnabled(boolean enabled);

		/**
		 * Signs out of the YouTube account.
		 */
		void logout();

		/**
		 * Adds a video to the end of the queue (swipe to the right).
		 */
		void enqueue(@NonNull FeedItem item);

		/**
		 * Lets the person pick a file with channels to follow.
		 */
		void importSubscriptions();

		/**
		 * Saves the followed channels to a file.
		 */
		void exportSubscriptions();

		/**
		 * Follows the channel behind a video (swipe to the left), after a reminder popup.
		 */
		void follow(@NonNull FeedItem item);

		/**
		 * A channel was followed or unfollowed from the watch screen.
		 */
		void onFollowChanged();
	}

	@NonNull
	private final Handler handler = new Handler(Looper.getMainLooper());
	@NonNull
	private final Host host;
	@NonNull
	private final View subsBar;
	@NonNull
	private final TextView subsAccount;
	@NonNull
	private final TextView subsManage;
	@NonNull
	private final FeedClient client;
	@NonNull
	private final ContentFilters filters;
	@NonNull
	private final Map<Mode, State> states = new EnumMap<>(Mode.class);
	@NonNull
	private final TextView title;
	@NonNull
	private final ImageView logo;
	@NonNull
	private final ImageButton searchButton;
	@NonNull
	private final ImageButton searchBack;
	@NonNull
	private final EditText searchField;
	@NonNull
	private final SwipeRefreshLayout refresh;
	@NonNull
	private final RecyclerView list;
	@NonNull
	private final LinearLayoutManager layoutManager;
	@NonNull
	private final TextView message;
	@NonNull
	private final FeedAdapter adapter;
	@NonNull
	private Mode mode = Mode.HOME;
	@NonNull
	private Mode browseMode = Mode.HOME;

	public NativeBrowser(@NonNull View root,
	                     @NonNull FeedClient client,
	                     @NonNull ContentFilters filters,
	                     @NonNull Host host) {
		this.client = client;
		this.filters = filters;
		this.host = host;
		subsBar = root.findViewById(R.id.browser_subs_bar);
		subsAccount = root.findViewById(R.id.browser_subs_account);
		subsManage = root.findViewById(R.id.browser_subs_manage);
		subsManage.setOnClickListener(v -> {
			if (host.accountEnabled()) {
				new MaterialAlertDialogBuilder(root.getContext())
								.setMessage(R.string.subs_logout_confirm)
								.setPositiveButton(R.string.subs_logout, (dialog, which) -> host.logout())
								.setNegativeButton(R.string.cancel, null)
								.show();
				return;
			}
			SubscriptionsDialog.show(root.getContext(), client, this::reloadLocalSubscriptions,
							host::importSubscriptions, host::exportSubscriptions);
		});
		// One button: the channels followed here, or the YouTube account's own subscriptions.
		subsAccount.setOnClickListener(v -> {
			boolean useAccount = !host.accountEnabled();
			host.setAccountEnabled(useAccount);
			State subs = state(Mode.SUBSCRIPTIONS);
			reset(subs);
			render(subs);
			if (useAccount && !client.isSignedIn()) host.signIn();
			else load(Mode.SUBSCRIPTIONS, true);
		});
		for (Mode value : Mode.values()) states.put(value, new State());
		title = root.findViewById(R.id.browser_title);
		logo = root.findViewById(R.id.browser_logo);
		searchButton = root.findViewById(R.id.browser_search);
		searchBack = root.findViewById(R.id.browser_search_back);
		searchField = root.findViewById(R.id.browser_search_field);
		refresh = root.findViewById(R.id.browser_refresh);
		list = root.findViewById(R.id.browser_list);
		message = root.findViewById(R.id.browser_message);
		adapter = new FeedAdapter(filters, new FeedAdapter.Listener() {
			@Override
			public void onOpen(@NonNull FeedItem item) {
				if (item.kind() == FeedItem.Kind.VIDEO) host.openVideo(item);
				else host.openPage(item.url());
			}

			@Override
			public void onOpenAuthor(@NonNull FeedItem item) {
				if (item.authorUrl() != null) host.openPage(item.authorUrl());
			}

			@Override
			public void onMenu(@NonNull FeedItem item) {
				if (item.kind() == FeedItem.Kind.VIDEO) host.showMenu(item);
			}
		});
		layoutManager = new LinearLayoutManager(root.getContext());
		list.setLayoutManager(layoutManager);
		list.setAdapter(adapter);
		list.setItemViewCacheSize(6);
		list.addOnScrollListener(new RecyclerView.OnScrollListener() {
			@Override
			public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
				if (dy <= 0) return;
				int last = layoutManager.findLastVisibleItemPosition();
				if (last >= adapter.getItemCount() - LOAD_MORE_THRESHOLD) loadMore();
			}
		});
		new ItemTouchHelper(new QueueSwipe(root.getContext(), new QueueSwipe.Target() {
			@Nullable
			@Override
			public FeedItem itemFor(@NonNull RecyclerView.ViewHolder holder) {
				return adapter.itemAt(holder);
			}

			@Override
			public void enqueue(@NonNull FeedItem item) {
				host.enqueue(item);
			}

			@Override
			public void follow(@NonNull FeedItem item) {
				host.follow(item);
			}
		})).attachToRecyclerView(list);
		message.setOnClickListener(v -> {
			State state = state(mode);
			if (mode == Mode.SUBSCRIPTIONS && !host.accountEnabled()) {
				if (LocalSubscriptions.get().all().isEmpty()) subsManage.performClick();
				else load(mode, true);
			} else if (mode == Mode.SUBSCRIPTIONS && (!client.isSignedIn() || (state.loaded && !state.signedIn))) {
				host.signIn();
			} else if (state.error) {
				load(mode, !state.loaded);
			}
		});
		refresh.setColorSchemeResources(R.color.yt_red);
		refresh.setOnRefreshListener(() -> load(mode, true));
		searchButton.setOnClickListener(v -> {
			if (mode == Mode.SEARCH) submitSearch();
			else show(Mode.SEARCH);
		});
		searchBack.setOnClickListener(v -> show(browseMode));
		searchField.setOnEditorActionListener((v, actionId, event) -> {
			boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
							&& event.getAction() == KeyEvent.ACTION_DOWN;
			if (actionId == EditorInfo.IME_ACTION_SEARCH || enter) {
				submitSearch();
				return true;
			}
			return false;
		});
	}

	@NonNull
	public Mode mode() {
		return mode;
	}

	/**
	 * Switches screen. Lists that were loaded before are shown again without a new request.
	 */
	public void show(@NonNull Mode next) {
		State current = states.get(mode);
		if (current != null && next != mode) current.scroll = layoutManager.onSaveInstanceState();
		if (next != Mode.SEARCH) browseMode = next;
		boolean changed = next != mode;
		mode = next;
		applySearchUi(next == Mode.SEARCH);
		State state = state(next);
		render(state);
		if (changed && state.scroll != null) layoutManager.onRestoreInstanceState(state.scroll);
		if (next == Mode.SEARCH) {
			if (state.query == null) focusSearch();
		} else if (!state.loaded && state.call == null && !subsOff(next)) {
			load(next, false);
		}
	}

	private boolean subsOff(@NonNull Mode value) {
		return value == Mode.SUBSCRIPTIONS && host.accountEnabled() && !client.isSignedIn();
	}

	@NonNull
	private String cacheKey(@NonNull Mode value) {
		if (value == Mode.HOME) return client.isSignedIn() ? "home_account" : "home_guest";
		return host.accountEnabled() ? "subs_account" : "subs_local";
	}

	private static void reset(@NonNull State state) {
		if (state.call != null) state.call.cancel();
		state.call = null;
		state.items.clear();
		state.seen.clear();
		state.next = null;
		state.scroll = null;
		state.loaded = false;
		state.error = false;
	}

	/**
	 * The followed channels changed (followed, unfollowed or imported): load the feed again.
	 */
	public void reloadLocalSubscriptions() {
		State subs = state(Mode.SUBSCRIPTIONS);
		reset(subs);
		if (mode == Mode.SUBSCRIPTIONS && !host.accountEnabled()) {
			render(subs);
			load(Mode.SUBSCRIPTIONS, true);
		}
	}

	/**
	 * The account switch or the sign-in state changed: refresh the Subscriptions screen.
	 */
	public void refreshAccountUi() {
		if (mode == Mode.SUBSCRIPTIONS) {
			State state = state(Mode.SUBSCRIPTIONS);
			render(state);
			if (!state.loaded && state.call == null && !subsOff(Mode.SUBSCRIPTIONS)) load(Mode.SUBSCRIPTIONS, false);
		}
		applySearchUi(mode == Mode.SEARCH);
	}

	/**
	 * Scrolls to the top, or reloads when the list is already at the top.
	 */
	public void reselect() {
		if (layoutManager.findFirstCompletelyVisibleItemPosition() <= 0) {
			load(mode, true);
		} else {
			list.scrollToPosition(0);
		}
	}

	/**
	 * @return true when back was handled (leaving search)
	 */
	public boolean handleBack() {
		if (mode != Mode.SEARCH) return false;
		show(browseMode);
		return true;
	}

	/**
	 * Blocked channels or watched marks changed.
	 */
	public void onFiltersChanged() {
		render(state(mode));
		adapter.refreshStates();
	}

	/**
	 * Drops every loaded list, e.g. after signing in, and reloads the visible one.
	 */
	public void reloadAll() {
		for (State state : states.values()) {
			if (state.call != null) state.call.cancel();
			state.call = null;
			state.items.clear();
			state.seen.clear();
			state.next = null;
			state.scroll = null;
			state.loaded = false;
			state.error = false;
		}
		show(mode == Mode.SEARCH ? browseMode : mode);
	}

	public void release() {
		for (State state : states.values()) {
			if (state.call != null) state.call.cancel();
			state.call = null;
		}
		handler.removeCallbacksAndMessages(null);
	}

	@NonNull
	private State state(@NonNull Mode value) {
		State state = states.get(value);
		if (state == null) {
			state = new State();
			states.put(value, state);
		}
		return state;
	}

	/**
	 * Runs a search that came from a link (a YouTube results page) on the native search screen.
	 */
	public void searchFor(@NonNull String query) {
		show(Mode.SEARCH);
		searchField.setText(query);
		submitSearch();
	}

	private void submitSearch() {
		String query = searchField.getText().toString().trim();
		if (query.isEmpty()) return;
		hideKeyboard();
		searchField.clearFocus();
		State state = state(Mode.SEARCH);
		state.query = query;
		state.scroll = null;
		load(Mode.SEARCH, true);
		list.scrollToPosition(0);
	}

	private void loadMore() {
		State state = state(mode);
		if (state.call != null || state.next == null) return;
		if (state.error && System.currentTimeMillis() - state.errorAt < RETRY_AFTER_MS) return;
		load(mode, false);
	}

	/**
	 * @param fresh true to start over (refresh or new search), false for the first or next page
	 */
	private void load(@NonNull Mode target, boolean fresh) {
		State state = state(target);
		if (subsOff(target)) {
			if (target == mode) render(state);
			return;
		}
		if (fresh) {
			if (state.call != null) state.call.cancel();
			state.call = null;
			state.next = null;
		} else if (state.call != null) {
			return;
		}
		boolean firstPage = fresh || !state.loaded;
		Object next = firstPage ? null : state.next;
		if (!firstPage && next == null) return;
		FeedClient.Call call;
		final Object loadId = new Object();
		final boolean[] streamed = {false};
		state.loadToken = loadId;
		if (target == Mode.SEARCH) {
			if (state.query == null) {
				refresh.setRefreshing(false);
				return;
			}
			call = client.search(state.query, next instanceof Page page ? page : null);
		} else if (target == Mode.SUBSCRIPTIONS && !host.accountEnabled()) {
			// Followed channels have no shared order, so videos show up in the order the channels answer.
			Consumer<List<FeedItem>> partial = !firstPage ? null : items -> handler.post(() -> {
				if (state.loadToken != loadId) return;
				if (!streamed[0]) {
					streamed[0] = true;
					state.items.clear();
					state.seen.clear();
					state.scroll = null;
				}
				for (FeedItem item : items) {
					if (state.seen.add(item.url())) state.items.add(item);
				}
				if (target == mode) {
					render(state);
					if (state.items.size() == items.size()) list.scrollToPosition(0);
				}
			});
			call = client.localSubscriptions(LocalSubscriptions.get().all(), next, partial);
		} else {
			FeedClient.Feed feed = target == Mode.HOME ? FeedClient.Feed.HOME : FeedClient.Feed.SUBSCRIPTIONS;
			call = client.browse(feed, next instanceof String token ? token : null);
		}
		state.call = call;
		state.refreshing = firstPage;
		state.error = false;
		if (target == mode && firstPage) {
			refresh.setRefreshing(true);
			if (state.items.isEmpty()) message.setVisibility(View.GONE);
		}
		call.result.whenComplete((page, error) -> handler.post(() -> {
			if (state.call != call) return;
			state.call = null;
			if (error != null) {
				if (!isCancellation(error)) {
					state.error = true;
					state.errorAt = System.currentTimeMillis();
					// Nothing new could be loaded (offline, say): the last saved list is better than an error.
					if (firstPage && target != Mode.SEARCH && state.items.isEmpty()) {
						for (FeedItem cached : FeedCache.load(cacheKey(target))) {
							if (state.seen.add(cached.url())) state.items.add(cached);
						}
						if (!state.items.isEmpty()) state.error = false;
					}
				}
			} else if (page != null) {
				if (firstPage && !streamed[0]) {
					state.items.clear();
					state.seen.clear();
					state.scroll = null;
				}
				int added = 0;
				for (FeedItem item : page.items()) {
					if (state.seen.add(item.url())) {
						state.items.add(item);
						added++;
					}
				}
				// A page with nothing new causes no scroll event, so ask for the next one right away.
				state.emptyPages = added == 0 ? state.emptyPages + 1 : 0;
				if (added == 0 && !firstPage && page.next() != null && state.emptyPages <= MAX_EMPTY_PAGES) {
					handler.post(() -> {
						if (target == mode) loadMore();
					});
				}
				state.next = page.next();
				state.signedIn = page.signedIn();
				state.loaded = true;
				if (target != Mode.SEARCH && firstPage && !state.items.isEmpty()) {
					FeedCache.save(cacheKey(target), state.items);
				}
			}
			if (target == mode) {
				render(state);
				if (firstPage && error == null && !streamed[0]) list.scrollToPosition(0);
			}
		}));
	}

	private static boolean isCancellation(@NonNull Throwable error) {
		Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
		return cause instanceof CancellationException;
	}

	private void render(@NonNull State state) {
		updateSubsBar();
		List<FeedItem> visible = new ArrayList<>(state.items.size());
		for (FeedItem item : state.items) {
			if (item.author() != null && filters.isChannelBlocked(item.author(), item.authorUrl())) continue;
			visible.add(item);
		}
		adapter.submit(visible);
		refresh.setRefreshing(state.call != null && state.refreshing);
		if (!visible.isEmpty()) {
			message.setVisibility(View.GONE);
			return;
		}
		@StringRes int text;
		if (state.call != null) {
			message.setVisibility(View.GONE);
			return;
		} else if (mode == Mode.SUBSCRIPTIONS && !host.accountEnabled()) {
			// Followed channels never ask for a login.
			text = LocalSubscriptions.get().all().isEmpty() ? R.string.subs_local_empty
							: state.error ? R.string.feed_error : R.string.feed_empty;
		} else if (state.error) {
			text = R.string.feed_error;
		} else if (mode == Mode.SUBSCRIPTIONS && (!client.isSignedIn() || (state.loaded && !state.signedIn))) {
			text = R.string.feed_sign_in;
		} else if (mode == Mode.SEARCH) {
			if (state.query == null) {
				message.setVisibility(View.GONE);
				return;
			}
			text = R.string.search_no_results;
		} else if (state.loaded) {
			text = R.string.feed_empty;
		} else {
			message.setVisibility(View.GONE);
			return;
		}
		message.setText(text);
		message.setVisibility(View.VISIBLE);
	}

	private void updateSubsBar() {
		boolean show = mode == Mode.SUBSCRIPTIONS;
		subsBar.setVisibility(show ? View.VISIBLE : View.GONE);
		subsAccount.setText(host.accountEnabled() ? R.string.subs_use_local : R.string.subs_use_account);
		subsManage.setText(host.accountEnabled() ? R.string.subs_logout : R.string.subs_manage);
	}

	private void applySearchUi(boolean searching) {
		logo.setVisibility(searching ? View.GONE : View.VISIBLE);
		title.setVisibility(searching ? View.GONE : View.VISIBLE);
		searchBack.setVisibility(searching ? View.VISIBLE : View.GONE);
		searchField.setVisibility(searching ? View.VISIBLE : View.GONE);
		if (!searching) {
			hideKeyboard();
			searchField.clearFocus();
		}
	}

	private void focusSearch() {
		searchField.requestFocus();
		searchField.post(() -> {
			InputMethodManager input = inputManager();
			if (input != null) input.showSoftInput(searchField, InputMethodManager.SHOW_IMPLICIT);
		});
	}

	private void hideKeyboard() {
		InputMethodManager input = inputManager();
		if (input != null) input.hideSoftInputFromWindow(searchField.getWindowToken(), 0);
	}

	@Nullable
	private InputMethodManager inputManager() {
		return (InputMethodManager) searchField.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
	}

	private static final class State {
		@NonNull
		final List<FeedItem> items = new ArrayList<>();
		@NonNull
		final Set<String> seen = new HashSet<>();
		@Nullable
		Object next;
		@Nullable
		FeedClient.Call call;
		@Nullable
		Parcelable scroll;
		@Nullable
		Object loadToken;
		@Nullable
		String query;
		boolean loaded;
		boolean signedIn = true;
		boolean refreshing;
		boolean error;
		int emptyPages;
		long errorAt;
	}
}
