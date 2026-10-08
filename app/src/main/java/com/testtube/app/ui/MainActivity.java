package com.testtube.app.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.util.UnstableApi;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.tencent.mmkv.MMKV;
import com.testtube.app.AppGraph;
import com.testtube.app.Constant;
import com.testtube.app.PlaybackService;
import com.testtube.app.R;
import com.testtube.app.downloader.ui.DownloadActivity;
import com.testtube.app.downloader.ui.DownloadDialog;
import com.testtube.app.downloader.ui.DownloadPermissionHost;
import com.testtube.app.downloader.ui.PlaylistDownloadDialog;
import com.testtube.app.downloader.ui.PlaylistDownloadItem;
import com.testtube.app.extractor.FeedItem;
import com.testtube.app.extractor.LocalSubscriptions;
import com.testtube.app.extractor.PageSource;
import com.testtube.app.extractor.VideoDetails;
import com.testtube.app.extractor.YoutubeExtractor;
import com.testtube.app.filter.ContentFilters;
import com.testtube.app.history.LocalHistoryController;
import com.testtube.app.history.WatchHistory;
import com.testtube.app.nav.MediaItemMenuPayload;
import com.testtube.app.nav.TabManager;
import com.testtube.app.player.TestTubePlayer;
import com.testtube.app.player.common.PlayerLoopMode;
import com.testtube.app.player.queue.QueueItem;
import com.testtube.app.player.queue.QueueRepository;
import com.testtube.app.ui.feed.NativeBrowser;
import com.testtube.app.ui.feed.PageScreen;
import com.testtube.app.ui.feed.WatchPanel;
import com.testtube.app.ui.queue.QueueAdapter;
import com.testtube.app.ui.queue.QueueTouch;
import com.testtube.app.update.UpdateManager;
import com.testtube.app.util.DeviceUtils;
import com.testtube.app.util.PermissionUtils;
import com.testtube.app.util.ToastUtils;
import com.testtube.app.util.UrlUtils;
import com.testtube.app.util.ViewUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@UnstableApi
public final class MainActivity extends AppCompatActivity implements DownloadPermissionHost {
	private static final String STATE_LAST_URL = "main.last_url";
	private final Handler handler = new Handler(Looper.getMainLooper());
	private ActivityGraph graph;
	private TabManager tabManager;
	private TestTubePlayer player;
	private YoutubeExtractor youtubeExtractor;
	private QueueRepository queueRepository;
	private WatchHistory watchHistory;
	@Nullable
	private PlaybackService playbackService;
	@Nullable
	private ServiceConnection serviceConnection;
	@Nullable
	private TextView hintText;
	@NonNull
	private final Runnable hideHintRunnable = this::hideHint;
	private MainActivityViewModel viewModel;
	@Nullable
	private QueueSheet queueSheet;
	@Nullable
	private QueueSheet queuePanel;
	@Nullable
	private OnBackPressedCallback appBackCallback;
	private long lastBackTime;
	private boolean bootstrapped;
	private boolean historyVisible;
	private boolean watchVisible;
	@Nullable
	private View watchContainer;
	@Nullable
	private WatchPanel watchPanel;
	@Nullable
	private View pageContainer;
	@Nullable
	private PageScreen pageScreen;
	private boolean navGuard;
	@Nullable
	private NativeBrowser nativeBrowser;
	@Nullable
	private View nativeContainer;
	private ContentFilters contentFilters;
	@Nullable
	private LoginController loginController;
	@Nullable
	private LocalHistoryController historyController;
	@Nullable
	private BottomNavigationView bottomNav;
	@Nullable
	private android.view.ViewGroup historyContainer;
	@Nullable
	private Runnable pendingPermissionAction;
	@Nullable
	private String restoredUrl;

	@NonNull
	public ActivityGraph graph() {
		return graph;
	}

	private void showAbout(@NonNull UpdateManager updates) {
		String version;
		try {
			version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
		} catch (PackageManager.NameNotFoundException e) {
			version = "";
		}
		new MaterialAlertDialogBuilder(this)
						.setTitle(getString(R.string.app_name) + " " + version)
						.setMultiChoiceItems(new CharSequence[]{getString(R.string.update_auto)},
										new boolean[]{updates.autoCheckEnabled()},
										(d, which, checked) -> updates.setAutoCheck(checked))
						.setPositiveButton(R.string.update_check_now, (d, w) -> updates.check(this, true))
						.setNegativeButton(R.string.update_close, null)
						.show();
	}

	@Override
	protected void onCreate(@Nullable Bundle savedInstanceState) {
		EdgeToEdge.enable(this);
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);
		AppGraph app = AppGraph.of(this);
		graph = new ActivityGraph(this, app);
		tabManager = graph.tabManager;
		player = graph.player;
		youtubeExtractor = app.youtubeExtractor();
		queueRepository = app.queueRepository();
		watchHistory = app.watchHistory();
		contentFilters = app.contentFilters();
		restoredUrl = savedInstanceState != null ? savedInstanceState.getString(STATE_LAST_URL) : null;
		viewModel = new ViewModelProvider(this, new ViewModelProvider.Factory() {
			@NonNull
			@Override
			@SuppressWarnings("unchecked")
			public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
				return (T) new MainActivityViewModel(app.queueRepository(), app.playerStateStore(), app.playerPreferences());
			}
		}).get(MainActivityViewModel.class);
		viewModel.getState().observe(this, state -> {
			renderQueueSheet(state);
			renderQueuePanel(state);
		});

		setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);

		View mainView = findViewById(R.id.main);
		ViewCompat.setOnApplyWindowInsetsListener(mainView, (v, insets) -> {
			Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
			Insets tappable = insets.getInsets(WindowInsetsCompat.Type.tappableElement());
			v.setPadding(systemBars.left, systemBars.top, systemBars.right, tappable.bottom);
			return insets;
		});

		hintText = findViewById(R.id.activity_hint_text);
		if (hintText != null) {
			int pad = ViewUtils.dpToPx(this, 16);
			hintText.setPadding(pad, pad / 2, pad, pad / 2);
		}

		bottomNav = findViewById(R.id.bottom_nav);
		// The activity already pads for the system bar. Without this the bar adds its own nav inset
		// and the space under the icons doubles.
		if (bottomNav != null) bottomNav.setOnApplyWindowInsetsListener(null);
		historyContainer = findViewById(R.id.history_container);
		nativeContainer = findViewById(R.id.native_container);
		View browserView = getLayoutInflater().inflate(R.layout.view_native_browser,
						(ViewGroup) nativeContainer, true);
		UpdateManager updates = app.updateManager();
		View.OnClickListener aboutClick = v -> showAbout(updates);
		browserView.findViewById(R.id.browser_logo).setOnClickListener(aboutClick);
		browserView.findViewById(R.id.browser_title).setOnClickListener(aboutClick);
		updates.checkOnStart(this);
		NativeBrowser.Host feedHost = new NativeBrowser.Host() {
			@Override
			public void openVideo(@NonNull FeedItem item) {
				MainActivity.this.openVideo(item.url());
			}

			@Override
			public void openPage(@NonNull String url) {
				tabManager.openTab(url, UrlUtils.getPageClass(url));
			}

			@Override
			public void showMenu(@NonNull FeedItem item) {
				if (item.videoId() == null) return;
				new MediaItemMenuDialog(MainActivity.this,
								new MediaItemMenuPayload(item.videoId(), item.url(), item.title(), item.author(),
												item.thumbnailUrl(), item.authorUrl()),
								youtubeExtractor, queueRepository, player, contentFilters,
								() -> {
									if (nativeBrowser != null) nativeBrowser.onFiltersChanged();
									if (watchPanel != null) watchPanel.onFiltersChanged();
									if (pageScreen != null) pageScreen.onFiltersChanged();
								}).show();
			}

			@Override
			public void signIn() {
				LoginController login = loginController;
				if (login == null || login.isShowing()) return;
				login.show(() -> {
					if (nativeBrowser != null) nativeBrowser.reloadAll();
				}, () -> {
					setSubscriptionsAccount(false);
					if (nativeBrowser != null) nativeBrowser.refreshAccountUi();
				});
			}

			@Override
			public boolean accountEnabled() {
				// followed channels by default, the account only after asking
				return MMKV.defaultMMKV().decodeBool(KEY_SUBS_ACCOUNT, false);
			}

			@Override
			public void logout() {
				if (loginController != null) loginController.signOut();
				setSubscriptionsAccount(false);
				ToastUtils.show(MainActivity.this, R.string.subs_logged_out);
				if (nativeBrowser != null) nativeBrowser.reloadAll();
			}

			@Override
			public void setAccountEnabled(boolean enabled) {
				setSubscriptionsAccount(enabled);
			}

			@Override
			public void enqueue(@NonNull FeedItem item) {
				if (item.videoId() == null) return;
				QueueItem queued = new MediaItemMenuPayload(item.videoId(), item.url(), item.title(), item.author(),
								item.thumbnailUrl(), item.authorUrl()).toQueueItem();
				if (queued.getVideoUrl() == null || queued.getTitle() == null || queued.getTitle().isBlank()) {
					ToastUtils.show(MainActivity.this, R.string.queue_item_unavailable);
					return;
				}
				if (!queueRepository.isEnabled()) queueRepository.setEnabled(true);
				queueRepository.add(queued);
				player.refreshQueueNav();
				ToastUtils.show(MainActivity.this, R.string.queue_item_added);
			}

			@Override
			public void follow(@NonNull FeedItem item) {
				confirmFollow(item);
			}

			@Override
			public void importSubscriptions() {
				importPicker.launch(new String[]{"*/*"});
			}

			@Override
			public void exportSubscriptions() {
				if (LocalSubscriptions.get().all().isEmpty()) {
					ToastUtils.show(MainActivity.this, R.string.subs_export_empty);
					return;
				}
				exportPicker.launch("testtube-subscriptions.csv");
			}

			@Override
			public void onFollowChanged() {
				if (nativeBrowser != null) nativeBrowser.reloadLocalSubscriptions();
			}
		};
		nativeBrowser = new NativeBrowser(browserView, app.feedClient(), contentFilters, feedHost);
		watchContainer = findViewById(R.id.watch_container);
		View watchView = getLayoutInflater().inflate(R.layout.view_watch, (ViewGroup) watchContainer, true);
		watchPanel = new WatchPanel(watchView, youtubeExtractor, app.feedClient(), tabManager, contentFilters, feedHost);
		pageContainer = findViewById(R.id.page_container);
		View pageView = getLayoutInflater().inflate(R.layout.view_page, (ViewGroup) pageContainer, true);
		pageScreen = new PageScreen(pageView, app.pageSource(), contentFilters, feedHost, visible -> updateContainers());
		player.setDetailsListener(details -> {
			if (watchPanel != null) watchPanel.onDetails(details);
		});
		// long press on the playing video = same menu as in the lists
		player.setLongPressAction(() -> {
			VideoDetails video = player.currentDetails();
			if (video == null) return;
			feedHost.showMenu(new FeedItem(FeedItem.Kind.VIDEO, Constant.HOME_URL + "/watch?v=" + video.getId(),
							video.getId(), video.getTitle() == null ? "" : video.getTitle(), video.getAuthor(), video.getUploaderUrl(),
							video.getThumbnailUrl(), -1, -1, null, false));
		});
		tabManager.setHost(new TabManager.Host() {
			@Override
			public void onWatchChanged(@Nullable String url, boolean visible) {
				watchVisible = visible;
				if (watchPanel != null) watchPanel.show(url);
				updateContainers();
				if (!visible && nativeBrowser != null) nativeBrowser.onFiltersChanged();
			}

			@Override
			public void onNativePage(@NonNull PageSource.Kind kind, @NonNull String url) {
				if (pageScreen != null) pageScreen.open(kind, url);
				updateContainers();
			}

			@Override
			public void onNativeRequested(@NonNull String pageClass) {
				if (pageClass.startsWith("searching:")) {
					String query = pageClass.substring("searching:".length());
					goTo(R.id.nav_home);
					if (nativeBrowser != null && !query.isBlank() && !"null".equals(query)) nativeBrowser.searchFor(query);
					return;
				}
				if (Constant.PAGE_LIBRARY.equals(pageClass) || "history".equals(pageClass)) {
					goTo(R.id.nav_history);
					return;
				}
				goTo(Constant.PAGE_SUBSCRIPTIONS.equals(pageClass) ? R.id.nav_subscriptions : R.id.nav_home);
			}
		});
		setupBottomNav();
		updateContainers();

		View playerRoot = findViewById(R.id.playerView);
		playerRoot.post(() -> findViewById(R.id.btn_queue).setOnClickListener(v -> showQueueBottomSheet()));
		if (PermissionUtils.needsPostNotificationsPermission()
						&& !PermissionUtils.hasPostNotificationsPermission(this)) {
			ActivityCompat.requestPermissions(
							this,
							PermissionUtils.postNotificationsPermission(),
							PermissionUtils.REQUEST_POST_NOTIFICATIONS);
		}
		serviceConnection = new ServiceConnection() {
			@Override
			public void onServiceConnected(ComponentName name, IBinder binder) {
				playbackService = ((PlaybackService.PlaybackBinder) binder).getService();
				if (player != null && playbackService != null) {
					player.attachPlaybackService(playbackService);
				}
			}

			@Override
			public void onServiceDisconnected(ComponentName name) {
				playbackService = null;
			}
		};
		bindService(new Intent(this, PlaybackService.class), serviceConnection, Context.BIND_AUTO_CREATE);
		appBackCallback = new OnBackPressedCallback(true) {
			@Override
			public void handleOnBackPressed() {
				handleAppBack();
			}
		};
		getOnBackPressedDispatcher().addCallback(this, appBackCallback);

		// Signing in is optional; the Subscriptions tab offers it. PoTokenHost isn't started here
		// either, only once a token is actually requested.
		loginController = new LoginController(this);
		mainView.post(this::bootstrap);
	}

	private static final String KEY_SUBS_ACCOUNT = "subs_use_account";
	private static final String KEY_FOLLOW_REMINDERS = "follow_swipe_reminders";
	private static final int FOLLOW_REMINDER_COUNT = 3;

	/** First three swipes ask for confirmation and say how many reminders are left. */
	private void confirmFollow(@NonNull FeedItem item) {
		MMKV store = MMKV.defaultMMKV();
		int shown = store.decodeInt(KEY_FOLLOW_REMINDERS, 0);
		String name = item.author() != null && !item.author().isBlank() ? item.author() : getString(R.string.swipe_this_channel);
		if (shown >= FOLLOW_REMINDER_COUNT) {
			followChannel(item);
			return;
		}
		store.encode(KEY_FOLLOW_REMINDERS, shown + 1);
		int left = FOLLOW_REMINDER_COUNT - 1 - shown;
		String indicator = left == 0 ? getString(R.string.swipe_last_reminder)
						: getResources().getQuantityString(R.plurals.swipe_more_reminders, left, left);
		new MaterialAlertDialogBuilder(this)
						.setTitle(getString(R.string.swipe_follow_title, name))
						.setMessage(indicator)
						.setPositiveButton(R.string.watch_follow, (d, which) -> followChannel(item))
						.setNegativeButton(R.string.cancel, null)
						.show();
	}

	private void followChannel(@NonNull FeedItem item) {
		String url = item.authorUrl();
		String id = LocalSubscriptions.channelIdOf(url);
		String name = item.author() != null ? item.author() : "";
		if (id != null) {
			if (LocalSubscriptions.get().add(new LocalSubscriptions.Channel(id, name))) {
				ToastUtils.show(this, R.string.subs_followed);
				if (nativeBrowser != null) nativeBrowser.reloadLocalSubscriptions();
			} else {
				ToastUtils.show(this, R.string.swipe_already_following);
			}
			return;
		}
		if (url == null || url.isBlank()) {
			ToastUtils.show(this, R.string.subs_add_failed);
			return;
		}
		AppGraph.of(this).feedClient().resolveChannel(url).whenComplete((channel, error) -> runOnUiThread(() -> {
			if (error != null || channel == null) {
				ToastUtils.show(this, R.string.subs_add_failed);
				return;
			}
			LocalSubscriptions.get().add(channel);
			ToastUtils.show(this, R.string.subs_followed);
			if (nativeBrowser != null) nativeBrowser.reloadLocalSubscriptions();
		}));
	}
	private static final int MAX_IMPORT_BYTES = 5_000_000;

	private final ActivityResultLauncher<String> exportPicker =
					registerForActivityResult(new ActivityResultContracts.CreateDocument("text/csv"), uri -> {
						if (uri == null) return;
						boolean saved = false;
						try (OutputStream stream = getContentResolver().openOutputStream(uri)) {
							if (stream != null) {
								stream.write(LocalSubscriptions.get().exportCsv().getBytes(StandardCharsets.UTF_8));
								saved = true;
							}
						} catch (IOException | RuntimeException e) {
							Log.w("MainActivity", "channel export failed", e);
						}
						ToastUtils.show(this, saved ? R.string.subs_export_done : R.string.subs_export_failed);
					});

	private final ActivityResultLauncher<String[]> importPicker =
					registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
						if (uri == null) return;
						new Thread(() -> {
							int added = -1;
							try (InputStream stream = getContentResolver().openInputStream(uri)) {
								if (stream != null) {
									ByteArrayOutputStream buffer = new ByteArrayOutputStream();
									byte[] chunk = new byte[8192];
									int read;
									while ((read = stream.read(chunk)) != -1 && buffer.size() < MAX_IMPORT_BYTES) {
										buffer.write(chunk, 0, read);
									}
									String text = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
									added = LocalSubscriptions.get().addAll(LocalSubscriptions.parseImport(text));
								}
							} catch (IOException | RuntimeException e) {
								Log.w("MainActivity", "channel import failed", e);
							}
							int count = added;
							runOnUiThread(() -> {
								if (count < 0) {
									ToastUtils.show(this, R.string.subs_import_failed);
									return;
								}
								ToastUtils.show(this, getString(R.string.subs_import_done, count));
								if (nativeBrowser != null) nativeBrowser.reloadLocalSubscriptions();
							});
						}, "channel-import").start();
					});

	private void setSubscriptionsAccount(boolean enabled) {
		MMKV.defaultMMKV().encode(KEY_SUBS_ACCOUNT, enabled);
	}

	private void bootstrap() {
		if (bootstrapped) return;
		bootstrapped = true;
		goTo(R.id.nav_home);
		String initialUrl = restoredUrl;
		if (initialUrl != null && !initialUrl.isBlank()) {
			tabManager.openTab(initialUrl, UrlUtils.getPageClass(initialUrl));
		}
		handleIntent(getIntent());
	}

	private void setupBottomNav() {
		if (bottomNav == null) return;
		bottomNav.setOnItemSelectedListener(item -> {
			if (!navGuard) showRoot(item.getItemId());
			return true;
		});
		bottomNav.setOnItemReselectedListener(item -> {
			int id = item.getItemId();
			boolean onScreen = !watchVisible && (pageScreen == null || !pageScreen.isVisible())
							&& (id == R.id.nav_history) == historyVisible
							&& (id == R.id.nav_history || nativeBrowser == null
							|| nativeBrowser.mode() != NativeBrowser.Mode.SEARCH);
			if (onScreen && id != R.id.nav_history && nativeBrowser != null) {
				nativeBrowser.reselect();
			} else if (!onScreen) {
				showRoot(id);
			}
		});
	}

	private void goTo(int navId) {
		if (bottomNav != null && bottomNav.getSelectedItemId() != navId) {
			navGuard = true;
			bottomNav.setSelectedItemId(navId);
			navGuard = false;
		}
		showRoot(navId);
	}

	private void showRoot(int navId) {
		historyVisible = navId == R.id.nav_history;
		tabManager.showNative();
		if (pageScreen != null) pageScreen.clear();
		if (historyVisible) {
			buildHistory();
			if (historyController != null) historyController.reload();
		} else if (nativeBrowser != null) {
			nativeBrowser.show(navId == R.id.nav_subscriptions
							? NativeBrowser.Mode.SUBSCRIPTIONS : NativeBrowser.Mode.HOME);
		}
		updateContainers();
	}

	// History and the queue panel are built lazily; the app starts on Home.
	private void buildHistory() {
		if (historyController != null || historyContainer == null) return;
		getLayoutInflater().inflate(R.layout.view_local_history, historyContainer, true);
		historyController = new LocalHistoryController(historyContainer, watchHistory,
						videoId -> openVideo(Constant.HOME_URL + "/watch?v=" + videoId));
		setupQueuePanel(historyContainer);
		setupPageSwitch(historyContainer);
		renderQueuePanel(uiState());
	}

	private void updateContainers() {
		boolean pageVisible = pageScreen != null && pageScreen.isVisible() && !watchVisible;
		boolean covered = watchVisible || pageVisible;
		if (watchContainer != null) watchContainer.setVisibility(watchVisible ? View.VISIBLE : View.GONE);
		if (pageContainer != null) pageContainer.setVisibility(pageVisible ? View.VISIBLE : View.GONE);
		if (nativeContainer != null) {
			nativeContainer.setVisibility(!covered && !historyVisible ? View.VISIBLE : View.GONE);
		}
		if (historyContainer != null) {
			historyContainer.setVisibility(!covered && historyVisible ? View.VISIBLE : View.GONE);
		}
	}

	private void openVideo(@NonNull String url) {
		tabManager.openTab(url, Constant.PAGE_WATCH);
	}

	@Override
	protected void onNewIntent(@NonNull Intent intent) {
		super.onNewIntent(intent);
		setIntent(intent);
		handleIntent(intent);
	}

	@Override
	public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, @NonNull Configuration newConfig) {
		super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
		player.onPictureInPictureModeChanged(isInPictureInPictureMode);
	}

	private void handleIntent(@Nullable Intent intent) {
		if (intent == null || !bootstrapped) return;
		String action = intent.getAction();
		boolean isDownloadAction = "TRIGGER_DOWNLOAD_FROM_SHARE".equals(action);

		if ("OPEN_DOWNLOADS".equals(action)) {
			startActivity(new Intent(this, DownloadActivity.class));
			return;
		}

		String url = null;
		if (Intent.ACTION_VIEW.equals(action) && intent.getData() != null) {
			url = intent.getData().toString();
		} else if (Intent.ACTION_SEND.equals(action) || isDownloadAction) {
			// pull a YouTube URL out of the shared text
			String text = intent.getStringExtra(Intent.EXTRA_TEXT);
			if (text != null) {
				Pattern pat = Pattern.compile("https?://[\\w./?=&%#-]+", Pattern.CASE_INSENSITIVE);
				Matcher m = pat.matcher(text);
				url = m.find() ? m.group() : null;
			}
		}

		// only accept YouTube links from other apps
		if (url != null && url.regionMatches(true, 0, "http://", 0, 7)) {
			url = "https://" + url.substring(7);
		}
		if (url != null && !UrlUtils.isYoutubeLink(url)) {
			url = null;
		}
		if (url != null) {
			if (isDownloadAction) {
				String loadUrl = url.replace(Constant.YOUTUBE_MOBILE_HOST, "www.youtube.com");
				long fetchToast = ToastUtils.show(this, R.string.fetching_download_links);
				handler.postDelayed(() -> ToastUtils.cancel(fetchToast), 1000);
				handler.postDelayed(() -> new DownloadDialog(loadUrl, this, youtubeExtractor).show(), 600);
			} else {
				String loadUrl = url.replace("www.youtube.com", Constant.YOUTUBE_MOBILE_HOST);
				tabManager.openTab(loadUrl, UrlUtils.getPageClass(loadUrl));
			}
		}
	}

	private void showQueueBottomSheet() {
		if (DeviceUtils.isInPictureInPictureMode(this)) return;
		BottomSheetDialog dialog = new BottomSheetDialog(this);
		View sheetView = getLayoutInflater().inflate(R.layout.bottom_sheet_queue, new FrameLayout(this), false);
		dialog.setContentView(sheetView);

		ImageButton closeButton = sheetView.findViewById(R.id.btn_queue_close);
		SwitchMaterial enabledSwitch = sheetView.findViewById(R.id.switch_queue_enabled);
		ImageButton downloadButton = sheetView.findViewById(R.id.btn_queue_download);
		ImageButton orderButton = sheetView.findViewById(R.id.btn_queue_order);
		ImageButton clearButton = sheetView.findViewById(R.id.btn_queue_clear);
		TextView emptyView = sheetView.findViewById(R.id.queue_empty);
		RecyclerView recyclerView = sheetView.findViewById(R.id.queue_items_recycler);
		QueueAdapter adapter = new QueueAdapter(new QueueAdapter.Actions() {
			@Override
			public void onPlayRequested(@NonNull QueueItem item) {
				dialog.dismiss();
				if (item.getVideoUrl() != null) {
					tabManager.playInWatch(item.getVideoUrl());
				}
			}

			@Override
			public void onDeleteRequested(@NonNull QueueItem item) {
				new MaterialAlertDialogBuilder(MainActivity.this)
								.setMessage(R.string.remove_queue_item_confirmation)
								.setPositiveButton(R.string.confirm, (d, which) -> {
									String videoId = item.getVideoId();
									if (videoId == null) return;
									viewModel.removeQueueItem(videoId);
								})
								.setNegativeButton(R.string.cancel, null)
								.show();
			}
		});
		QueueSheet sheet = new QueueSheet(enabledSwitch, orderButton, emptyView, recyclerView, adapter);
		queueSheet = sheet;
		recyclerView.setLayoutManager(new LinearLayoutManager(this));
		recyclerView.setAdapter(adapter);
		recyclerView.setNestedScrollingEnabled(true);
		recyclerView.setOverScrollMode(View.OVER_SCROLL_NEVER);
		new ItemTouchHelper(new QueueTouch(adapter::moveItem, new QueueTouch.DragStateCallback() {
			@Override
			public void onDragStateChanged(boolean dragging) {
				if (sheet.behavior != null) sheet.behavior.setDraggable(!dragging);
			}

			@Override
			public void onDragFinished() {
				viewModel.moveQueue(adapter.snapshotItems());
				if (sheet.behavior != null) sheet.behavior.setDraggable(true);
			}
		})).attachToRecyclerView(recyclerView);

		closeButton.setOnClickListener(v -> dialog.dismiss());
		enabledSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
			if (!buttonView.isPressed()) return;
			viewModel.setQueueEnabled(isChecked);
			ToastUtils.show(this, isChecked ? R.string.queue_enabled_on : R.string.queue_enabled_off);
		});
		downloadButton.setOnClickListener(v -> {
			dialog.dismiss();
			List<QueueItem> items = uiState().items();
			if (items.isEmpty()) {
				ToastUtils.show(this, R.string.queue_download_unavailable);
				return;
			}
			List<PlaylistDownloadItem> dialogItems = new ArrayList<>();
			for (int i = 0; i < items.size(); i++) {
				QueueItem queueItem = items.get(i);
				String videoId = queueItem.getVideoId() != null
								? queueItem.getVideoId()
								: YoutubeExtractor.getVideoId(queueItem.getVideoUrl());
				String itemUrl = queueItem.getVideoUrl() != null && !queueItem.getVideoUrl().isBlank()
								? queueItem.getVideoUrl()
								: videoId == null || videoId.isBlank()
								? null
								: "https://www.youtube.com/watch?v=" + videoId;
				PlaylistDownloadItem item = new PlaylistDownloadItem(
								i,
								videoId == null ? "unknown" : videoId,
								itemUrl == null ? "" : itemUrl);
				item.setTitle(queueItem.getTitle());
				item.setAuthor(queueItem.getAuthor());
				item.setThumbnailUrl(queueItem.getThumbnailUrl());
				if (videoId == null || itemUrl == null || itemUrl.isBlank()) {
					item.setAvailabilityStatus(PlaylistDownloadItem.AvailabilityStatus.LOAD_FAILED);
					item.setFailureReason(getString(R.string.playlist_download_status_failed));
					item.setSelected(false);
				} else {
					item.setAvailabilityStatus(PlaylistDownloadItem.AvailabilityStatus.READY);
					item.setSelected(true);
				}
				dialogItems.add(item);
			}
			new PlaylistDownloadDialog(
							getString(R.string.queue),
							dialogItems,
							this,
							youtubeExtractor).show();
		});
		orderButton.setOnClickListener(v -> {
			PlayerLoopMode newMode = uiState().loopMode().next();
			player.setLoopMode(newMode);
		});
		clearButton.setOnClickListener(v -> new MaterialAlertDialogBuilder(this)
						.setMessage(R.string.clear_queue_confirmation)
						.setPositiveButton(R.string.confirm, (d, which) -> viewModel.clearQueue())
						.setNegativeButton(R.string.cancel, null)
						.show());
		dialog.setOnShowListener(ignored -> {
			final FrameLayout bottomSheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
			if (bottomSheet == null) return;
			BottomSheetBehavior<FrameLayout> behavior = BottomSheetBehavior.from(bottomSheet);
			sheet.behavior = behavior;
			int sheetBasePaddingBottom = sheetView.getPaddingBottom();
			int recyclerBasePaddingBottom = recyclerView.getPaddingBottom();
			int recyclerTrailingSpace = Math.round(getResources().getDisplayMetrics().density * 24);
			View mainView = findViewById(R.id.main);
			WindowInsetsCompat rootInsets = mainView != null
							? ViewCompat.getRootWindowInsets(mainView)
							: ViewCompat.getRootWindowInsets(bottomSheet);
			int bottomInset = rootInsets != null
							? rootInsets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
							: 0;
			sheetView.setPadding(
							sheetView.getPaddingLeft(),
							sheetView.getPaddingTop(),
							sheetView.getPaddingRight(),
							sheetBasePaddingBottom + Math.max(0, bottomInset));
			// keep the last row visible
			recyclerView.setPadding(
							recyclerView.getPaddingLeft(),
							recyclerView.getPaddingTop(),
							recyclerView.getPaddingRight(),
							recyclerBasePaddingBottom + Math.max(Math.max(0, bottomInset), Math.max(0, recyclerTrailingSpace)));
			View playerRoot = findViewById(R.id.playerView);
			int mainHeight = mainView != null ? mainView.getHeight() : 0;
			int topInset = mainView != null ? mainView.getPaddingTop() : 0;
			int playerBottom = playerRoot != null ? playerRoot.getBottom() : 0;
			final int maxSheetHeight;
			if (mainHeight <= 0) {
				maxSheetHeight = 0;
			} else if (uiState().miniPlayer()) {
				maxSheetHeight = Math.max(0, mainHeight - Math.max(0, topInset));
			} else if (playerBottom <= 0 || playerBottom >= mainHeight) {
				maxSheetHeight = mainHeight;
			} else {
				maxSheetHeight = mainHeight - playerBottom;
			}
			final android.view.ViewGroup.LayoutParams bottomSheetLayoutParams = bottomSheet.getLayoutParams();
			if (bottomSheetLayoutParams != null && maxSheetHeight > 0) {
				bottomSheetLayoutParams.height = maxSheetHeight;
				bottomSheet.setLayoutParams(bottomSheetLayoutParams);
			}
			final android.view.ViewGroup.LayoutParams sheetLayoutParams = sheetView.getLayoutParams();
			if (sheetLayoutParams != null && maxSheetHeight > 0) {
				sheetLayoutParams.height = maxSheetHeight;
				sheetView.setLayoutParams(sheetLayoutParams);
			}
			behavior.setPeekHeight(maxSheetHeight > 0 ? maxSheetHeight : sheetView.getMeasuredHeight());
			behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
			sheet.scrollPending = true;
			renderQueueSheet(uiState());
		});
		dialog.setOnDismissListener(d -> {
			if (queueSheet == sheet) {
				queueSheet = null;
			}
		});
		renderQueueSheet(uiState());
		dialog.show();
	}

	@NonNull
	private MainActivityViewModel.UiState uiState() {
		final MainActivityViewModel.UiState state = viewModel.getState().getValue();
		if (state != null) return state;
		return new MainActivityViewModel.UiState(
						queueRepository.isEnabled(),
						queueRepository.getItems(),
						player.getVideoId(),
						player.getLoopMode(),
						player.isInMiniPlayer());
	}

	private void setupQueuePanel(@NonNull View root) {
		SwitchMaterial enabledSwitch = root.findViewById(R.id.panel_queue_enabled);
		ImageButton clearButton = root.findViewById(R.id.panel_queue_clear);
		TextView emptyView = root.findViewById(R.id.panel_queue_empty);
		RecyclerView recyclerView = root.findViewById(R.id.panel_queue_list);
		QueueAdapter adapter = new QueueAdapter(new QueueAdapter.Actions() {
			@Override
			public void onPlayRequested(@NonNull QueueItem item) {
				if (item.getVideoUrl() == null) return;
				openVideo(item.getVideoUrl());
			}

			@Override
			public void onDeleteRequested(@NonNull QueueItem item) {
				String videoId = item.getVideoId();
				if (videoId != null) viewModel.removeQueueItem(videoId);
			}
		});
		recyclerView.setLayoutManager(new LinearLayoutManager(this));
		recyclerView.setAdapter(adapter);
		new ItemTouchHelper(new QueueTouch(adapter::moveItem, new QueueTouch.DragStateCallback() {
			@Override
			public void onDragStateChanged(boolean dragging) {
			}

			@Override
			public void onDragFinished() {
				viewModel.moveQueue(adapter.snapshotItems());
			}
		})).attachToRecyclerView(recyclerView);
		enabledSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
			if (!buttonView.isPressed()) return;
			viewModel.setQueueEnabled(isChecked);
		});
		clearButton.setOnClickListener(v -> new MaterialAlertDialogBuilder(this)
						.setMessage(R.string.clear_queue_confirmation)
						.setPositiveButton(R.string.confirm, (d, which) -> viewModel.clearQueue())
						.setNegativeButton(R.string.cancel, null)
						.show());
		queuePanel = new QueueSheet(enabledSwitch, clearButton, emptyView, recyclerView, adapter);
	}

	/**
	 * Queue (left) and History (right) are full pages; the header switch flips between them and
	 * the clear button acts on whichever one is showing.
	 */
	private void setupPageSwitch(@NonNull View root) {
		SwitchMaterial pageSwitch = root.findViewById(R.id.panel_page_switch);
		TextView queueTitle = root.findViewById(R.id.panel_queue_title);
		TextView historyTitle = root.findViewById(R.id.panel_history_title);
		View queuePage = root.findViewById(R.id.panel_queue_page);
		View historyPage = root.findViewById(R.id.panel_history_page);
		View queueClear = root.findViewById(R.id.panel_queue_clear);
		View historyClear = root.findViewById(R.id.history_clear);
		pageSwitch.setOnCheckedChangeListener((button, showHistory) -> {
			queuePage.setVisibility(showHistory ? View.GONE : View.VISIBLE);
			historyPage.setVisibility(showHistory ? View.VISIBLE : View.GONE);
			queueClear.setVisibility(showHistory ? View.GONE : View.VISIBLE);
			historyClear.setVisibility(showHistory ? View.VISIBLE : View.GONE);
			queueTitle.setAlpha(showHistory ? 0.5f : 1f);
			historyTitle.setAlpha(showHistory ? 1f : 0.5f);
		});
		queueTitle.setOnClickListener(v -> pageSwitch.setChecked(false));
		historyTitle.setOnClickListener(v -> pageSwitch.setChecked(true));
		queueTitle.setAlpha(0.5f);
	}

	private void renderQueuePanel(@NonNull MainActivityViewModel.UiState state) {
		QueueSheet panel = queuePanel;
		if (panel == null) return;
		if (panel.enabledSwitch.isChecked() != state.queueEnabled()) {
			panel.enabledSwitch.setChecked(state.queueEnabled());
		}
		panel.adapter.replaceItems(state.items(), state.videoId());
		boolean empty = state.items().isEmpty();
		panel.emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
		panel.recyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
	}

	private void renderQueueSheet(@NonNull MainActivityViewModel.UiState state) {
		QueueSheet sheet = queueSheet;
		if (sheet == null) return;
		if (sheet.enabledSwitch.isChecked() != state.queueEnabled()) {
			sheet.enabledSwitch.setChecked(state.queueEnabled());
		}
		renderLoop(sheet.orderButton, state.loopMode());
		sheet.adapter.replaceItems(state.items(), state.videoId());
		boolean empty = state.items().isEmpty();
		sheet.emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
		sheet.recyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
		if (sheet.scrollPending) {
			scrollQueueToPlaying(sheet.recyclerView, state.items(), state.videoId());
			sheet.scrollPending = false;
		}
	}

	private void scrollQueueToPlaying(@NonNull RecyclerView recyclerView,
	                                  @NonNull List<QueueItem> items,
	                                  @Nullable String playingId) {
		if (playingId == null) return;
		int playingPosition = -1;
		for (int i = 0; i < items.size(); i++) {
			if (playingId.equals(items.get(i).getVideoId())) {
				playingPosition = i;
				break;
			}
		}
		if (playingPosition < 0) return;
		int target = playingPosition;
		recyclerView.post(() -> {
			final RecyclerView.LayoutManager layoutManager = recyclerView.getLayoutManager();
			if (layoutManager instanceof LinearLayoutManager linearLayoutManager) {
				linearLayoutManager.scrollToPositionWithOffset(
								target,
								Math.max(0, recyclerView.getPaddingTop()) + Math.max(0, recyclerView.getHeight()) / 3);
				return;
			}
			recyclerView.scrollToPosition(target);
		});
	}

	private void renderLoop(@NonNull ImageButton button, @NonNull PlayerLoopMode mode) {
		switch (mode) {
			case PLAYLIST_NEXT -> {
				button.setImageResource(R.drawable.ic_playback_end_next);
				button.setContentDescription(getString(R.string.playback_end_next));
			}
			case LOOP_ONE -> {
				button.setImageResource(R.drawable.ic_playback_end_loop);
				button.setContentDescription(getString(R.string.playback_end_loop));
			}
			case PAUSE_AT_END -> {
				button.setImageResource(R.drawable.ic_playback_end_pause);
				button.setContentDescription(getString(R.string.playback_end_pause));
			}
			case PLAYLIST_RANDOM -> {
				button.setImageResource(R.drawable.ic_playback_end_shuffle);
				button.setContentDescription(getString(R.string.playback_end_playlist_random));
			}
		}
	}

	public void handleAppBack() {
		if (loginController != null && loginController.isShowing()) {
			loginController.handleBack();
			return;
		}
		if (DeviceUtils.isInPictureInPictureMode(this)) {
			if (appBackCallback != null) {
				appBackCallback.setEnabled(false);
			}
			getOnBackPressedDispatcher().onBackPressed();
			if (appBackCallback != null) {
				appBackCallback.setEnabled(true);
			}
			return;
		}
		if (player != null && player.isFullscreen()) {
			player.exitFullscreen();
			return;
		}
		if (watchVisible) {
			tabManager.goBack();
			return;
		}
		if (pageScreen != null && pageScreen.back()) {
			updateContainers();
			return;
		}
		if (historyVisible) {
			goTo(R.id.nav_home);
			return;
		}
		if (nativeBrowser != null) {
			if (nativeBrowser.handleBack()) return;
			if (nativeBrowser.mode() == NativeBrowser.Mode.SUBSCRIPTIONS) {
				goTo(R.id.nav_home);
				return;
			}
		}
		long time = System.currentTimeMillis();
		if (time - lastBackTime < 2_000L) {
			finish();
		} else {
			lastBackTime = time;
			ToastUtils.show(this, R.string.press_back_again_to_exit);
		}
	}

	public void showHint(@NonNull String text, long durationMs) {
		if (hintText == null || DeviceUtils.isInPictureInPictureMode(this)) return;
		hintText.setText(text);
		hintText.setVisibility(View.VISIBLE);
		hintText.bringToFront();
		hintText.setTranslationZ(1000f);
		hintText.setAlpha(1.0f);
		ViewUtils.animateViewAlpha(hintText, 1.0f, View.GONE);
		handler.removeCallbacks(hideHintRunnable);
		if (durationMs > 0) {
			handler.postDelayed(hideHintRunnable, durationMs);
		}
	}

	public void hideHint() {
		if (hintText != null) {
			ViewUtils.animateViewAlpha(hintText, 0.0f, View.GONE);
		}
	}

	@Override
	protected void onStart() {
		super.onStart();
		if (player != null) player.setVideoEnabled(true);
	}

	// in the background we only play audio, video isn't fetched until the app is back
	@Override
	protected void onStop() {
		super.onStop();
		if (player != null && !isChangingConfigurations() && !DeviceUtils.isInPictureInPictureMode(this)) {
			player.setVideoEnabled(false);
		}
	}

	@Override
	protected void onResume() {
		super.onResume();
		if (player != null && player.isInMiniPlayer() && !DeviceUtils.isInPictureInPictureMode(this)) {
			player.restoreInAppMiniPlayerUiIfNeeded();
		}
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		if (nativeBrowser != null) nativeBrowser.release();
		if (serviceConnection != null) unbindService(serviceConnection);
		if (!isChangingConfigurations() && player != null) player.release();
	}

	@Override
	public void onRequestPermissionsResult(int requestCode,
	                                       @NonNull String[] permissions,
	                                       @NonNull int[] grantResults) {
		super.onRequestPermissionsResult(requestCode, permissions, grantResults);
		if (requestCode != PermissionUtils.REQUEST_STORAGE_PERMISSION) return;
		Runnable action = pendingPermissionAction;
		pendingPermissionAction = null;
		if (grantResults.length == 0) return;
		for (int result : grantResults) {
			if (result != PackageManager.PERMISSION_GRANTED) return;
		}
		if (action != null) action.run();
	}

	@Override
	protected void onSaveInstanceState(@NonNull Bundle outState) {
		super.onSaveInstanceState(outState);
		String url = watchVisible ? tabManager.getWatchUrl() : null;
		if (url != null) {
			outState.putString(STATE_LAST_URL, url);
		}
	}

	@Override
	public void requestDownloadStoragePermission(@NonNull Runnable onGranted) {
		if (!PermissionUtils.needsLegacyStoragePermission()
						|| PermissionUtils.hasDownloadStoragePermission(this)) {
			onGranted.run();
			return;
		}
		pendingPermissionAction = onGranted;
		ActivityCompat.requestPermissions(
						this,
						PermissionUtils.downloadStoragePermissions(),
						PermissionUtils.REQUEST_STORAGE_PERMISSION);
	}

	private static final class QueueSheet {
		@NonNull
		private final SwitchMaterial enabledSwitch;
		@NonNull
		private final ImageButton orderButton;
		@NonNull
		private final TextView emptyView;
		@NonNull
		private final RecyclerView recyclerView;
		@NonNull
		private final QueueAdapter adapter;
		@Nullable
		private BottomSheetBehavior<FrameLayout> behavior;
		private boolean scrollPending;

		private QueueSheet(@NonNull SwitchMaterial enabledSwitch,
		                   @NonNull ImageButton orderButton,
		                   @NonNull TextView emptyView,
		                   @NonNull RecyclerView recyclerView,
		                   @NonNull QueueAdapter adapter) {
			this.enabledSwitch = enabledSwitch;
			this.orderButton = orderButton;
			this.emptyView = emptyView;
			this.recyclerView = recyclerView;
			this.adapter = adapter;
		}
	}
}
