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

import com.testtube.app.AppSettings;
import com.testtube.app.R;
import com.testtube.app.util.ViewUtils;
import com.testtube.app.ui.Dialogs;
import com.testtube.app.extractor.FeedCache;
import com.testtube.app.extractor.FeedClient;
import com.testtube.app.extractor.FeedItem;
import com.testtube.app.extractor.LocalSubscriptions;
import com.testtube.app.filter.ContentFilters;
import com.testtube.app.net.Failure;
import com.testtube.app.net.RequestPacer;

import org.schabi.newpipe.extractor.Page;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

/** Home, Subscriptions and search, each with its own list and scroll position. */
public final class NativeBrowser {
	private static final int LOAD_MORE_THRESHOLD = 6;
	private static final int MAX_EMPTY_PAGES = 3;
	private static final long AUTO_RETRY_MIN_MS = 2_500L;
	private static final long AUTO_RETRY_MAX_MS = 20_000L;
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

		/** Account cookies missing or expired. */
		void signIn();

		/** Subscriptions should use the YouTube account. */
		boolean accountEnabled();

		void setAccountEnabled(boolean enabled);

		/** Signs out of YouTube. */
		void logout();

		/** Queues a video (swipe right). */
		void enqueue(@NonNull FeedItem item);

		/** Picks a channel file to follow. */
		void importSubscriptions();

		/** Saves followed channels to a file. */
		void exportSubscriptions();

		/** Follows the video's channel (swipe left), after a reminder. */
		void follow(@NonNull FeedItem item);

		/** Follows the video's channel right away (long press on its avatar). */
		void followChannel(@NonNull FeedItem item);

		/** A channel was followed or unfollowed from the watch screen. */
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
	private final View topBar;
	@NonNull
	private final View startPage;
	@NonNull
	private final EditText startField;
	@NonNull
	private final View watermark;
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
				Dialogs.builder(root.getContext())
								.setMessage(R.string.subs_logout_confirm)
								.setPositiveButton(R.string.subs_logout, (dialog, which) -> host.logout())
								.setNegativeButton(R.string.cancel, null)
								.show();
				return;
			}
			SubscriptionsDialog.show(root.getContext(), client, this::reloadLocalSubscriptions,
							host::importSubscriptions, host::exportSubscriptions);
		});
		// toggles local follows and the account's subscriptions
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
		topBar = root.findViewById(R.id.browser_top_bar);
		startPage = root.findViewById(R.id.browser_start);
		startField = root.findViewById(R.id.browser_start_field);
		watermark = root.findViewById(R.id.browser_watermark);
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
			public void onFollowAuthor(@NonNull FeedItem item) {
				host.followChannel(item);
			}

			@Override
			public void onMenu(@NonNull FeedItem item) {
				if (item.kind() == FeedItem.Kind.VIDEO) host.showMenu(item);
			}
		});
		// avatars in every list: Home, Subscriptions, search
		adapter.setShowAvatars(true);
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
		refresh.setColorSchemeColors(ViewUtils.themeColor(refresh.getContext(), androidx.appcompat.R.attr.colorPrimary));
		refresh.setProgressBackgroundColorSchemeColor(ViewUtils.themeColor(refresh.getContext(),
						com.google.android.material.R.attr.colorSurfaceContainerHigh));
		refresh.setOnRefreshListener(() -> load(mode, true));
		searchButton.setOnClickListener(v -> {
			if (mode == Mode.SEARCH) submitSearch();
			else show(Mode.SEARCH);
		});
		searchBack.setOnClickListener(v -> show(browseMode));
		root.findViewById(R.id.browser_start_search).setOnClickListener(v -> searchFromStart());
		startField.setOnEditorActionListener((v, actionId, event) -> {
			if (isSearchKey(actionId, event)) {
				searchFromStart();
				return true;
			}
			return false;
		});
		searchField.setOnEditorActionListener((v, actionId, event) -> {
			if (isSearchKey(actionId, event)) {
				submitSearch();
				return true;
			}
			return false;
		});
	}

	private static boolean isSearchKey(int actionId, @Nullable KeyEvent event) {
		return actionId == EditorInfo.IME_ACTION_SEARCH
						|| event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
						&& event.getAction() == KeyEvent.ACTION_DOWN;
	}

	/** Home as just the logo and search (setting). */
	private boolean onStartPage() {
		return mode == Mode.HOME && AppSettings.searchOnlyHome();
	}

	private void searchFromStart() {
		String query = startField.getText().toString().trim();
		if (query.isEmpty()) return;
		startField.setText("");
		startField.clearFocus();
		searchFor(query);
	}

	/** Swaps list and top bar for the start page, or back. */
	private void applyStartPage() {
		boolean start = onStartPage();
		topBar.setVisibility(start ? View.GONE : View.VISIBLE);
		startPage.setVisibility(start ? View.VISIBLE : View.GONE);
		list.setVisibility(start ? View.GONE : View.VISIBLE);
		watermark.setVisibility(start ? View.GONE : View.VISIBLE);
		refresh.setEnabled(!start);
		if (start) {
			refresh.setRefreshing(false);
			message.setVisibility(View.GONE);
		}
	}

	@NonNull
	public Mode mode() {
		return mode;
	}

	/** Switches screen. Already loaded lists show again without a request. */
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
		} else if (!state.loaded && state.call == null && !subsOff(next) && !onStartPage()) {
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

	/** Reloads Subscriptions after follows changed. */
	public void reloadLocalSubscriptions() {
		State subs = state(Mode.SUBSCRIPTIONS);
		reset(subs);
		if (mode == Mode.SUBSCRIPTIONS && !host.accountEnabled()) {
			render(subs);
			load(Mode.SUBSCRIPTIONS, true);
		}
	}

	/** Refreshes Subscriptions after the account switch or sign-in changed. */
	public void refreshAccountUi() {
		if (mode == Mode.SUBSCRIPTIONS) {
			State state = state(Mode.SUBSCRIPTIONS);
			render(state);
			if (!state.loaded && state.call == null && !subsOff(Mode.SUBSCRIPTIONS)) load(Mode.SUBSCRIPTIONS, false);
		}
		applySearchUi(mode == Mode.SEARCH);
	}

	/** Scrolls to the top, or reloads if already there. */
	public void reselect() {
		if (onStartPage()) {
			startField.requestFocus();
			return;
		}
		if (layoutManager.findFirstCompletelyVisibleItemPosition() <= 0) {
			load(mode, true);
		} else {
			list.scrollToPosition(0);
		}
	}

	/** @return true if back was handled (leaving search) */
	public boolean handleBack() {
		if (mode != Mode.SEARCH) return false;
		show(browseMode);
		return true;
	}

	/** Blocked channels or watched marks changed. */
	public void onFiltersChanged() {
		State state = state(mode);
		render(state);
		adapter.refreshStates();
		// search-only Home just turned off, fill it now
		if (mode == Mode.HOME && !state.loaded && state.call == null && !onStartPage()) load(mode, false);
	}

	/** Drops every list (after sign-in, say) and reloads the visible one. */
	public void reloadAll() {
		for (State state : states.values()) reset(state);
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

	/** Runs the search of a YouTube results link. */
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

	/** @param fresh start over (refresh or new search); false for the first or next page */
	private void load(@NonNull Mode target, boolean fresh) {
		State state = state(target);
		if (target == Mode.HOME && AppSettings.searchOnlyHome()) return;
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
			// local subscriptions arrive per channel, each time as the whole list sorted by date, so newer
			// videos slot in above
			Consumer<List<FeedItem>> partial = !firstPage ? null : items -> handler.post(() -> {
				if (state.loadToken != loadId) return;
				boolean first = !streamed[0];
				streamed[0] = true;
				if (first) state.scroll = null;
				replaceItems(state, items);
				if (target == mode) {
					render(state);
					if (first) list.scrollToPosition(0);
				}
			});
			call = client.localSubscriptions(LocalSubscriptions.get().all(), next, fresh, partial);
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
				if (!isCancellation(error) && !Failure.isCancellation(error)) {
					state.error = true;
					state.errorAt = System.currentTimeMillis();
					state.failure = Failure.report(error);
					retryLater(target, state, firstPage);
					// load failed (offline etc), show the last saved list instead of an error
					if (firstPage && target != Mode.SEARCH && state.items.isEmpty()) {
						for (FeedItem cached : FeedCache.load(cacheKey(target))) {
							if (state.seen.add(cached.url())) state.items.add(cached);
						}
						if (!state.items.isEmpty()) state.error = false;
					}
				}
			} else if (page != null) {
				state.autoRetried = false;
				if (firstPage && !streamed[0]) state.scroll = null;
				int before = firstPage ? 0 : state.items.size();
				if (page.whole() || firstPage) {
					replaceItems(state, page.items());
				} else {
					for (FeedItem item : page.items()) {
						if (state.seen.add(item.url())) state.items.add(item);
					}
				}
				int added = state.items.size() - before;
				// an empty page won't trigger a scroll, ask for the next one now
				state.emptyPages = added == 0 ? state.emptyPages + 1 : 0;
				if (added == 0 && !firstPage && page.next() != null && state.emptyPages <= MAX_EMPTY_PAGES) {
					handler.post(() -> {
						if (target == mode) loadMore();
					});
				}
				state.next = page.next();
				state.signedIn = page.signedIn();
				state.loaded = true;
				// a short first page (Home after filtering) doesn't fill the screen enough to scroll
				if (firstPage && state.next != null && state.items.size() < LOAD_MORE_THRESHOLD) {
					handler.post(() -> {
						if (target == mode) loadMore();
					});
				}
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

	private static void replaceItems(@NonNull State state, @NonNull List<FeedItem> items) {
		state.items.clear();
		state.seen.clear();
		for (FeedItem item : items) {
			if (state.seen.add(item.url())) state.items.add(item);
		}
	}

	/**
	 * A first page that failed for a passing reason (pause, timeout) reloads once by itself when the
	 * pause ends; pulling down still retries right away.
	 */
	private void retryLater(@NonNull Mode target, @NonNull State state, boolean firstPage) {
		if (!firstPage || state.autoRetried
						|| (state.failure != Failure.SLOW_DOWN && state.failure != Failure.TIMEOUT)) {
			return;
		}
		state.autoRetried = true;
		long delay = Math.max(AUTO_RETRY_MIN_MS, Math.min(AUTO_RETRY_MAX_MS, RequestPacer.pauseRemainingMs()));
		handler.postDelayed(() -> {
			if (state.call == null && state.error && target == mode) load(target, true);
		}, delay);
	}

	private static boolean isCancellation(@NonNull Throwable error) {
		Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
		return cause instanceof CancellationException;
	}

	private void render(@NonNull State state) {
		updateSubsBar();
		applyStartPage();
		if (onStartPage()) return;
		List<FeedItem> visible = new ArrayList<>(state.items.size());
		boolean shorts = AppSettings.showShorts();
		for (FeedItem item : state.items) {
			if (item.shortForm() && !shorts) continue;
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
			// local subscriptions don't need a login
			text = LocalSubscriptions.get().all().isEmpty() ? R.string.subs_local_empty
							: state.error ? state.failure.feedMessage : R.string.feed_empty;
		} else if (state.error) {
			text = state.failure.feedMessage;
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
		/** Why the last load failed, for the message. */
		@NonNull
		Failure failure = Failure.OTHER;
		/** First page already auto-retried once since its last load. */
		boolean autoRetried;
		int emptyPages;
		long errorAt;
	}
}
