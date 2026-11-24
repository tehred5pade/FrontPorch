package io.tehred5pade.frontporch

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material3.*
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.tehred5pade.frontporch.data.db.SubscriptionEntity
import io.tehred5pade.frontporch.data.download.DownloadSettingsStore
import io.tehred5pade.frontporch.data.importexport.NewPipeSubscriptionImporter
import io.tehred5pade.frontporch.data.innertube.InnerTubeShortsService
import io.tehred5pade.frontporch.data.innertube.SubscriptionFeedService
import io.tehred5pade.frontporch.data.local.FeedCacheStore
import io.tehred5pade.frontporch.data.local.FrontPorchDatabase
import io.tehred5pade.frontporch.data.local.WatchProgressRepository
import io.tehred5pade.frontporch.data.playlist.PlaylistEntry
import io.tehred5pade.frontporch.data.playlist.PlaylistStore
import io.tehred5pade.frontporch.data.prefs.UserSettings
import io.tehred5pade.frontporch.data.prefs.UserSettingsRepository
import io.tehred5pade.frontporch.data.search.YouTubeSearchRepository
import io.tehred5pade.frontporch.ui.common.frostedGlass
import io.tehred5pade.frontporch.ui.downloads.DownloadQuality
import io.tehred5pade.frontporch.ui.downloads.DownloadsScreen
import io.tehred5pade.frontporch.ui.feed.FeedScreen
import io.tehred5pade.frontporch.ui.feed.FeedSelectionBar
import io.tehred5pade.frontporch.ui.feed.FeedSelectionBarState
import io.tehred5pade.frontporch.ui.feed.FeedVideoMenuOverlay
import io.tehred5pade.frontporch.ui.feed.FeedVideoMenuState
import io.tehred5pade.frontporch.ui.feed.FeedViewModel
import io.tehred5pade.frontporch.ui.player.DraggableVideoPlayerOverlay
import io.tehred5pade.frontporch.ui.player.FloatingVideoWindow
import io.tehred5pade.frontporch.ui.player.VideoDetailsAndCommentsSection
import io.tehred5pade.frontporch.ui.player.VideoPlayerScreen
import io.tehred5pade.frontporch.ui.player.KeepScreenOnEffect
import io.tehred5pade.frontporch.ui.search.SearchScreen
import io.tehred5pade.frontporch.data.video.VideoMetadataRepository
import io.tehred5pade.frontporch.ui.settings.SettingsScreen
import io.tehred5pade.frontporch.ui.shorts.ShortsScreen
import io.tehred5pade.frontporch.ui.shorts.ShortsViewModel
import io.tehred5pade.frontporch.ui.subscriptions.ChannelDetailScreen
import io.tehred5pade.frontporch.ui.subscriptions.ChannelDetailViewModel
import io.tehred5pade.frontporch.data.sponsorblock.SponsorBlockPreferences
import io.tehred5pade.frontporch.service.PlaybackQueueController
import io.tehred5pade.frontporch.service.PlaybackSessionHolder
import io.tehred5pade.frontporch.service.QueueVideoItem
import io.tehred5pade.frontporch.ui.subscriptions.ChannelListScreen
import io.tehred5pade.frontporch.ui.theme.FrontPorchTheme
import io.tehred5pade.frontporch.ui.theme.rememberMusicastGlassStyle
import io.tehred5pade.frontporch.ui.theme.ThemePreferences
import io.tehred5pade.frontporch.update.AppUpdater
import io.tehred5pade.frontporch.update.ReleaseInfo
import io.tehred5pade.frontporch.update.UpdateCheckResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class ActiveVideoMeta(
    val videoId: String,
    val title: String,
    val channelName: String,
    val localVideoPath: String? = null
)

enum class PlaybackMode {
    NORMAL,
    FLOATING,
    BACKGROUND_AUDIO
}

enum class AppTab(val label: String, val icon: ImageVector) {
    SUBSCRIPTIONS("Subscriptions", Icons.Default.Subscriptions),
    DOWNLOADS("Downloads", Icons.Default.Download),
    SEARCH("Search", Icons.Default.Search),
    SHORTS("Shorts", Icons.Default.PlayArrow),
    SETTINGS("Settings", Icons.Default.Settings)
}

class MainActivity : ComponentActivity() {
    private lateinit var settingsRepository: UserSettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SponsorBlockPreferences.init(applicationContext)
        val app = applicationContext as FrontPorchApp
        settingsRepository = UserSettingsRepository(applicationContext)
        val database = FrontPorchDatabase.getDatabase(applicationContext)
        val subscriptionDao = database.subscriptionsDao()
        val shortsService = InnerTubeShortsService(app.okHttpClient)
        val cachedFeedDao = database.cachedFeedVideoDao()
        val downloadedDao = database.downloadedVideoDao()
        val feedService = SubscriptionFeedService(app.okHttpClient, cachedFeedDao, downloadedDao)
        val feedCacheStore = FeedCacheStore(applicationContext)
        val watchProgressRepository = WatchProgressRepository(database.watchHistoryDao(), database.downloadedVideoDao())

        enableEdgeToEdge()
        setContent {
            val nowPlayingMeta by PlaybackSessionHolder.nowPlaying.collectAsState()
            KeepScreenOnEffect(keepScreenOn = nowPlayingMeta?.isPlaying == true)

            val userSettings by settingsRepository.settingsFlow.collectAsState(initial = UserSettings())
            val subscriptions by subscriptionDao.observeAll().collectAsState(initial = emptyList())
            val navController = rememberNavController()
            val navBackStackEntry by navController.currentBackStackEntryAsState()
            val currentRoute = navBackStackEntry?.destination?.route ?: "tabs"
            val scope = rememberCoroutineScope()

            var activeVideo by remember { mutableStateOf<ActiveVideoMeta?>(null) }
            var playbackMode by remember { mutableStateOf(PlaybackMode.NORMAL) }
            val queueController = remember { PlaybackQueueController() }
            var isPlayerMinimized by rememberSaveable { mutableStateOf(false) }
            // True while a downloaded (offline) video owns the player. The top search bar is
            // hidden for the duration and restored as soon as playback closes or finishes.
            var isOfflineVideoPlaying by rememberSaveable { mutableStateOf(false) }
            // Raw 0f..1f drag progress of the video overlay, used to cross-fade the tab bar
            var playerCollapseProgress by remember { mutableFloatStateOf(0f) }

            val tabs = remember { AppTab.entries }
            val pagerState = rememberPagerState(initialPage = AppTab.SUBSCRIPTIONS.ordinal) { tabs.size }

            // Tapping the already-selected tab bumps its tick. Every screen watches its own
            // tick to scroll back to the top and reload. A pending index handles tab taps
            // made while a detail route (channels, a video, …) is on screen: the pager is
            // detached there, so the page change is applied once "tabs" is composed again.
            val tabReloadTicks = remember { mutableStateListOf(0, 0, 0, 0, 0) }
            var pendingTabIndex by remember { mutableIntStateOf(-1) }

            // Download sort order is owned here so the search bar can host the sort action.
            val context = LocalContext.current
            val downloadSettingsStore = remember(context) { DownloadSettingsStore(context) }
            val playlistStore = remember(context) { PlaylistStore(context) }
            var downloadSortNewestFirst by rememberSaveable {
                mutableStateOf(downloadSettingsStore.getSortNewestFirst())
            }

            // Automatic update check on launch. Only a newer GitHub release is surfaced;
            // being up to date stays silent so startup isn't interrupted every time.
            var startupUpdate by remember { mutableStateOf<ReleaseInfo?>(null) }
            LaunchedEffect(Unit) {
                if (!AppUpdater.isAutoCheckEnabled(context)) return@LaunchedEffect
                when (val result = AppUpdater.checkForUpdates(context)) {
                    is UpdateCheckResult.UpdateAvailable -> startupUpdate = result.release
                    is UpdateCheckResult.UpToDate,
                    is UpdateCheckResult.Error -> Unit // Stay silent at launch.
                }
            }
            // A newly opened video always starts expanded.
            LaunchedEffect(activeVideo?.videoId) {
                if (activeVideo != null) {
                    isPlayerMinimized = false
                    playerCollapseProgress = 0f
                }
            }

            // Keep the offline flag in sync with the actual player. This restores the search
            // bar whenever the player is closed (or replaced by an online video), even when
            // the close came from a gesture or a tab change rather than DownloadsScreen.
            LaunchedEffect(activeVideo?.videoId, activeVideo?.localVideoPath) {
                isOfflineVideoPlaying = activeVideo?.localVideoPath != null
            }

            // Shorts and Settings own the screen: release the mini-player as soon as the
            // pager commits (or starts committing) to either destination.
            val currentTabIndex = pagerState.currentPage
            val settledTabIndex = pagerState.settledPage
            LaunchedEffect(currentTabIndex, settledTabIndex) {
                val currentTab = tabs.getOrNull(currentTabIndex)
                val settledTab = tabs.getOrNull(settledTabIndex)
                if (currentTab == AppTab.SHORTS || currentTab == AppTab.SETTINGS ||
                    settledTab == AppTab.SHORTS || settledTab == AppTab.SETTINGS
                ) {
                    if (activeVideo != null || PlaybackSessionHolder.nowPlaying.value != null) {
                        PlaybackSessionHolder.stopAndReleaseAll()
                        queueController.clear()
                        activeVideo = null
                        isPlayerMinimized = false
                        playerCollapseProgress = 0f
                        playbackMode = PlaybackMode.NORMAL
                    }
                }
            }

            val searchRepo = remember { YouTubeSearchRepository(app.okHttpClient) }
            var searchQuery by rememberSaveable { mutableStateOf("") }

            // Menus and dialogs use popup windows, so their back callbacks can run in
            // addition to the root handler. Children register here while they are open
            // so a back swipe only dismisses the topmost section.
            var blockingBackOverlays by remember { mutableStateOf(emptySet<String>()) }
            val overlayDismissJobs = remember { mutableMapOf<String, kotlinx.coroutines.Job>() }
            fun setBackOverlay(key: String, visible: Boolean) {
                overlayDismissJobs[key]?.cancel()
                if (visible) {
                    blockingBackOverlays = blockingBackOverlays + key
                } else {
                    // Keep the root handler disabled until the back event has fully
                    // finished; popup onDismissRequest runs before the root callback.
                    overlayDismissJobs[key] = scope.launch {
                        delay(200)
                        blockingBackOverlays = blockingBackOverlays - key
                    }
                }
            }

            fun selectTab(index: Int) {
                // Re-selecting the current tab reloads it and returns it to the top.
                // currentPage and settledPage can briefly disagree right after the pager
                // reattaches from a detail route, so either one is enough to count.
                if (pagerState.currentPage == index || pagerState.settledPage == index) {
                    tabReloadTicks[index] = tabReloadTicks[index] + 1
                }
                // Always return to the tab host first. popBackStack is a no-op when "tabs"
                // is already on top, and it removes every detail route otherwise.
                navController.popBackStack("tabs", inclusive = false)
                // The pager is detached while another route owns the screen, so defer the
                // page change until "tabs" is composed again. This also handles same-tab
                // taps, where the route is already "tabs" and no navigation happens.
                pendingTabIndex = index
            }

            LaunchedEffect(pendingTabIndex, currentRoute) {
                if (pendingTabIndex >= 0 && currentRoute == "tabs") {
                    val target = pendingTabIndex
                    pendingTabIndex = -1
                    pagerState.scrollToPage(target)
                }
            }

            // Re-tapping Search clears the query; the results list resets and scrolls to top.
            val searchReloadTick = tabReloadTicks[AppTab.SEARCH.ordinal]
            LaunchedEffect(searchReloadTick) {
                if (searchReloadTick > 0) searchQuery = ""
            }

            // Root back handler: child sections (search, player, dialogs, menus, routes)
            // register their own handlers later and take priority. Only when none of them
            // is active does a back swipe close the app from the main pager.
            BackHandler(
                enabled = currentRoute == "tabs" &&
                    activeVideo == null &&
                    blockingBackOverlays.isEmpty()
            ) {
                (context as? Activity)?.finish()
            }

            val downloadManager = app.downloadManager

            val feedViewModel: FeedViewModel = viewModel(
                factory = object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        return FeedViewModel(
                            subscriptionDao = subscriptionDao,
                            feedService = feedService,
                            feedDao = cachedFeedDao,
                            cacheStore = feedCacheStore
                        ) as T
                    }
                }
            )

            val feedVideos by feedViewModel.videos.collectAsState(initial = emptyList())
            val feedIsRefreshing by feedViewModel.isRefreshing.collectAsState()

            // Queue helpers: feed opens carry the subscription feed as context;
            // search/channel/watch open a single-item queue.
            fun stopPlayback() {
                PlaybackSessionHolder.stopAndReleaseAll()
                queueController.clear()
                activeVideo = null
                isOfflineVideoPlaying = false
                isPlayerMinimized = false
                playerCollapseProgress = 0f
                playbackMode = PlaybackMode.NORMAL
            }

            fun openQueueVideo(
                item: QueueVideoItem,
                contextList: List<QueueVideoItem> = emptyList(),
                mode: PlaybackMode = PlaybackMode.NORMAL
            ) {
                if (activeVideo != null) PlaybackSessionHolder.stopAndReleaseAll()
                queueController.openVideo(item, contextList)
                activeVideo = ActiveVideoMeta(item.videoId, item.title, item.channelName)
                playbackMode = mode
                isPlayerMinimized = false
                playerCollapseProgress = 0f
                isOfflineVideoPlaying = false
            }

            fun switchQueueVideo(item: QueueVideoItem) {
                activeVideo = ActiveVideoMeta(item.videoId, item.title, item.channelName)
                isPlayerMinimized = false
                playerCollapseProgress = 0f
            }

            fun openPlaylistEntry(entry: PlaylistEntry) {
                if (entry.localVideoPath != null) {
                    if (activeVideo != null) PlaybackSessionHolder.stopAndReleaseAll()
                    queueController.clear()
                    activeVideo = ActiveVideoMeta(
                        videoId = entry.videoId,
                        title = entry.title,
                        channelName = entry.channelName,
                        localVideoPath = entry.localVideoPath
                    )
                    isOfflineVideoPlaying = true
                    playbackMode = PlaybackMode.NORMAL
                    isPlayerMinimized = false
                    playerCollapseProgress = 0f
                } else {
                    openQueueVideo(QueueVideoItem(entry.videoId, entry.title, entry.channelName))
                }
            }

            // Background audio has no on-screen player to handle back, so one back
            // swipe stops it instead of leaving the user unable to leave the app.
            BackHandler(
                enabled = playbackMode == PlaybackMode.BACKGROUND_AUDIO && activeVideo != null
            ) {
                stopPlayback()
            }

            // Notification / lock-screen Prev and Next taps go through the same queue
            // controller as the in-app transport controls.
            DisposableEffect(queueController) {
                val skipPrevious: () -> Unit = {
                    queueController.playPrevious()?.let { switchQueueVideo(it) }
                }
                val skipNext: () -> Unit = {
                    queueController.playNext()?.let { switchQueueVideo(it) }
                }
                PlaybackSessionHolder.onSkipPrevious = skipPrevious
                PlaybackSessionHolder.onSkipNext = skipNext
                onDispose {
                    if (PlaybackSessionHolder.onSkipPrevious === skipPrevious) {
                        PlaybackSessionHolder.onSkipPrevious = null
                    }
                    if (PlaybackSessionHolder.onSkipNext === skipNext) {
                        PlaybackSessionHolder.onSkipNext = null
                    }
                }
            }

            val shortsViewModel: ShortsViewModel = viewModel(
                factory = object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        return ShortsViewModel(
                            application = app,
                            okHttpClient = app.okHttpClient,
                            shortsService = shortsService,
                            settingsRepository = settingsRepository,
                            subscriptionDao = subscriptionDao,
                            searchRepository = YouTubeSearchRepository(app.okHttpClient)
                        ) as T
                    }
                }
            )

            val liveShorts by shortsViewModel.shorts.collectAsState()
            val isLoadingShorts by shortsViewModel.isLoading.collectAsState()
            val shortsErrorMessage by shortsViewModel.errorMessage.collectAsState()

            val ctx = LocalContext.current
            val themePrefs = remember { ThemePreferences(ctx) }
            var backgroundTheme by remember { mutableStateOf(themePrefs.getBackgroundTheme()) }
            var highlightColor by remember { mutableStateOf(themePrefs.getHighlightColor()) }

            FrontPorchTheme(
                backgroundTheme = backgroundTheme,
                highlightColor = highlightColor
            ) {
                val scheme = MaterialTheme.colorScheme
                val sharedGlassStyle = rememberMusicastGlassStyle()

                val isLightTheme = !backgroundTheme.isDark
                SideEffect {
                    val window = (ctx as? android.app.Activity)?.window ?: return@SideEffect
                    val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
                    controller.isAppearanceLightStatusBars = isLightTheme
                    controller.isAppearanceLightNavigationBars = isLightTheme
                }

                val configuration = androidx.compose.ui.platform.LocalConfiguration.current
                val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                val isWatchRoute = currentRoute.startsWith("watch/")
                val hasVisiblePlayer =
                    activeVideo != null && playbackMode != PlaybackMode.BACKGROUND_AUDIO
                val hasOfflineFullscreenPlayer = isOfflineVideoPlaying && activeVideo == null

                val isShortsSection = currentRoute == "tabs" &&
                    tabs.getOrNull(pagerState.currentPage) == AppTab.SHORTS

                // Lock screen orientation to PORTRAIT when on the Shorts tab
                DisposableEffect(isShortsSection) {
                    val activity = (ctx as? Activity)
                    if (activity != null) {
                        if (isShortsSection) {
                            activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        } else {
                            activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        }
                    }
                    onDispose {
                        (ctx as? Activity)?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    }
                }

                // Status bar is visible everywhere in portrait except YouTube Shorts.
                // In landscape mode, status bar is hidden for maximum viewing area.
                val showStatusBar = !isShortsSection && !isLandscape
                DisposableEffect(showStatusBar) {
                    val activityWindow = (ctx as? android.app.Activity)?.window
                    val controller = activityWindow?.decorView?.windowInsetsController
                    if (showStatusBar) {
                        controller?.show(android.view.WindowInsets.Type.statusBars())
                    } else {
                        controller?.hide(android.view.WindowInsets.Type.statusBars())
                    }
                    onDispose { }
                }

                val hazeState = remember { HazeState() }
                var bottomBarHeightPx by remember { mutableIntStateOf(0) }
                var feedSelectionBar by remember { mutableStateOf<FeedSelectionBarState?>(null) }
                var feedVideoMenu by remember { mutableStateOf<FeedVideoMenuState?>(null) }
                val density = LocalDensity.current
                val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
                val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

                // Search bar is 52.dp tall plus a 16.dp margin; feed starts below it at scroll 0.
                // In landscape the search bar is gone, so tabs only need the status-bar inset.
                val feedTopClearance = when {
                    isLandscape -> if (showStatusBar) statusBarTop + 8.dp else 0.dp
                    else -> statusBarTop + 16.dp
                }
                // Settings has no pinned search bar, so it only needs status-bar clearance.
                val settingsTopClearance = if (showStatusBar) statusBarTop + 12.dp else 0.dp
                // Tab bar is measured from the real composable so clearance tracks theme/insets.
                val measuredTabBarDp = with(density) {
                    if (bottomBarHeightPx > 0) bottomBarHeightPx.toDp() else 80.dp + navBarBottom
                }
                val feedBottomClearance = if (isLandscape) {
                    12.dp
                } else {
                    measuredTabBarDp + if (hasVisiblePlayer) 74.dp else 12.dp
                }
                val feedEndClearance = if (isLandscape) measuredTabBarDp else 0.dp

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(scheme.background)
                ) {
                    // 1. Full-bleed scrolling content; hazeSource covers the whole screen
                    //    behind the pinned search bar, mini-player, and bottom tab bar.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .hazeSource(state = hazeState)
                    ) {
                            NavHost(
                                navController = navController,
                                startDestination = "tabs",
                                modifier = Modifier.fillMaxSize()
                            ) {
                                composable("tabs") {
                                    HorizontalPager(
                                        state = pagerState,
                                        // Keep every tab mounted so returning to Shorts restores the
                                        // exact Short and its already-buffered player.
                                        beyondViewportPageCount = tabs.size - 1,
                                        userScrollEnabled = (!hasVisiblePlayer || isPlayerMinimized) && !hasOfflineFullscreenPlayer,
                                        modifier = Modifier.fillMaxSize()
                                    ) { pageIndex ->
                                        when (tabs[pageIndex]) {
                                            AppTab.SUBSCRIPTIONS -> FeedScreen(
                                                viewModel = feedViewModel,
                                                reloadTick = tabReloadTicks[AppTab.SUBSCRIPTIONS.ordinal],
                                                downloadManager = downloadManager,
                                                watchProgressRepository = watchProgressRepository,
                                                hazeState = hazeState,
                                                glassStyle = sharedGlassStyle,
                                                onVideoClick = { video ->
                                                    openQueueVideo(
                                                        QueueVideoItem(video.videoId, video.title, video.channelName),
                                                        feedVideos.map {
                                                            QueueVideoItem(it.videoId, it.title, it.channelName)
                                                        }
                                                    )
                                                },
                                                onNavigateToSettings = { selectTab(AppTab.SETTINGS.ordinal) },
                                                topContentPadding = feedTopClearance +
                                                    if (feedSelectionBar != null) 64.dp else 0.dp,
                                                bottomContentPadding = feedBottomClearance,
                                                preferredAudioLanguage = userSettings.preferredAudioLanguage,
                                                defaultDownloadQuality = DownloadQuality.fromSettings(
                                                    context.currentDownloadMaxHeight(userSettings),
                                                    context.currentDownloadTargetFps(userSettings)
                                                ).name,
                                                allowHighResDownloads = userSettings.allowHighResContent,
                                                playlistStore = playlistStore,
                                                onPlayPlaylistEntry = { entry -> openPlaylistEntry(entry) },
                                                onStartBackgroundPlay = { video ->
                                                    openQueueVideo(
                                                        QueueVideoItem(video.videoId, video.title, video.channelName),
                                                        feedVideos.map {
                                                            QueueVideoItem(it.videoId, it.title, it.channelName)
                                                        },
                                                        PlaybackMode.BACKGROUND_AUDIO
                                                    )
                                                },
                                                onStartPopupPlay = { video ->
                                                    openQueueVideo(
                                                        QueueVideoItem(video.videoId, video.title, video.channelName),
                                                        feedVideos.map {
                                                            QueueVideoItem(it.videoId, it.title, it.channelName)
                                                        },
                                                        PlaybackMode.FLOATING
                                                    )
                                                },
                                                onBackOverlayChanged = { key, visible -> setBackOverlay(key, visible) },
                                                onSelectionBarStateChanged = { feedSelectionBar = it },
                                                onVideoMenuStateChanged = { feedVideoMenu = it }
                                            )

                                            AppTab.SEARCH -> SearchScreen(
                                                query = searchQuery,
                                                onQueryChange = { searchQuery = it },
                                                searchRepo = searchRepo,
                                                okHttpClient = app.okHttpClient,
                                                subscribedChannelIds = subscriptions.map { it.channelId }.toSet(),
                                                hazeState = hazeState,
                                                glassStyle = sharedGlassStyle,
                                                isPageActive = pagerState.currentPage == AppTab.SEARCH.ordinal,
                                                topContentPadding = feedTopClearance,
                                                bottomContentPadding = feedBottomClearance,
                                                onPlayVideo = { id, title, channel ->
                                                    openQueueVideo(QueueVideoItem(id, title, channel))
                                                },
                                                onPlayShort = {
                                                    scope.launch {
                                                        pagerState.animateScrollToPage(AppTab.SHORTS.ordinal)
                                                    }
                                                },
                                                onToggleSubscribe = { channelId, channelTitle ->
                                                    scope.launch {
                                                        if (channelId in subscriptions.map { it.channelId }) {
                                                            subscriptionDao.deleteSubscription(channelId)
                                                        } else {
                                                            subscriptionDao.insertSubscription(
                                                                SubscriptionEntity(channelId = channelId, name = channelTitle)
                                                            )
                                                        }
                                                    }
                                                }
                                            )

                                            AppTab.DOWNLOADS -> DownloadsScreen(
                                                reloadTick = tabReloadTicks[AppTab.DOWNLOADS.ordinal],
                                                dao = database.downloadedVideoDao(),
                                                downloadManager = downloadManager,
                                                watchProgressRepository = watchProgressRepository,
                                                okHttpClient = app.okHttpClient,
                                                sortNewestFirst = downloadSortNewestFirst,
                                                hazeState = hazeState,
                                                glassStyle = sharedGlassStyle,
                                                topContentPadding = feedTopClearance,
                                                bottomContentPadding = feedBottomClearance,
                                                preferredAudioLanguage = userSettings.preferredAudioLanguage,
                                                playlistStore = playlistStore,
                                                onPlayPlaylistEntry = { entry -> openPlaylistEntry(entry) },
                                                onOfflinePlayingChanged = { playing ->
                                                    isOfflineVideoPlaying = playing
                                                },
                                                onPlayVideo = { item ->
                                                    playbackMode = PlaybackMode.NORMAL
                                                    isPlayerMinimized = false
                                                    playerCollapseProgress = 0f
                                                    activeVideo = ActiveVideoMeta(
                                                        videoId = item.videoId,
                                                        title = item.title,
                                                        channelName = item.channelName,
                                                        localVideoPath = item.localVideoPath
                                                    )
                                                },
                                                onBackOverlayChanged = { key, visible -> setBackOverlay(key, visible) }
                                            )

                                            AppTab.SHORTS -> ShortsScreen(
                                                viewModel = shortsViewModel,
                                                reloadTick = tabReloadTicks[AppTab.SHORTS.ordinal],
                                                shorts = liveShorts,
                                                isLoading = isLoadingShorts,
                                                errorMessage = shortsErrorMessage,
                                                onLoadMore = { shortsViewModel.loadMoreShorts() },
                                                onRetry = { shortsViewModel.reloadFeed() },
                                                onShortViewed = { id -> shortsViewModel.recordShortViewed(id) },
                                                likedShortIds = userSettings.likedShortIds,
                                                dislikedShortIds = userSettings.dislikedShortIds,
                                                onLikeShort = { short -> shortsViewModel.likeShort(short) },
                                                onDislikeShort = { short -> shortsViewModel.dislikeShort(short) },
                                                isPageActive = pagerState.currentPage == AppTab.SHORTS.ordinal,
                                                maxStreamHeight = context.currentStreamMaxHeight(userSettings),
                                                streamTargetFps = context.currentStreamTargetFps(userSettings),
                                                preferredAudioLanguage = userSettings.preferredAudioLanguage,
                                                subscribedChannelIds = subscriptions.mapTo(HashSet()) { it.channelId },
                                                onOpenChannel = { channelId, channelTitle ->
                                                    navController.navigate(
                                                        "channel/$channelId?name=${Uri.encode(channelTitle)}"
                                                    )
                                                },
                                                onToggleSubscribe = { channelId, channelTitle ->
                                                    scope.launch {
                                                        if (channelId in subscriptions.map { it.channelId }) {
                                                            subscriptionDao.deleteSubscription(channelId)
                                                        } else {
                                                            subscriptionDao.insertSubscription(
                                                                SubscriptionEntity(
                                                                    channelId = channelId,
                                                                    name = channelTitle
                                                                )
                                                            )
                                                        }
                                                    }
                                                }
                                            )

                                            AppTab.SETTINGS -> SettingsScreen(
                                                reloadTick = tabReloadTicks[AppTab.SETTINGS.ordinal],
                                                userSettings = userSettings,
                                                subscriptions = subscriptions,
                                                onNavigateToChannels = { navController.navigate("channels") },
                                                backgroundTheme = backgroundTheme,
                                                highlightColor = highlightColor,
                                                onBackgroundThemeSelected = { theme ->
                                                    themePrefs.setBackgroundTheme(theme)
                                                    backgroundTheme = theme
                                                },
                                                onHighlightColorSelected = { color ->
                                                    themePrefs.setHighlightColor(color)
                                                    highlightColor = color
                                                },
                                                onSponsorBlockChanged = { enabled ->
                                                    scope.launch { settingsRepository.updateSponsorBlock(enabled) }
                                                },
                                                onCategoriesChanged = { categories ->
                                                    scope.launch { settingsRepository.updateSelectedCategories(categories) }
                                                },
                                                onCustomCategoriesChanged = { customs ->
                                                    scope.launch { settingsRepository.updateCustomCategories(customs) }
                                                },
                                                onRepeatCooldownChanged = { cooldown ->
                                                    scope.launch { settingsRepository.updateRepeatCooldown(cooldown) }
                                                },
                                                onAudioLanguageChanged = { code ->
                                                    scope.launch { settingsRepository.updatePreferredAudioLanguage(code) }
                                                },
                                                onWifiQualityChanged = { height, fps ->
                                                    scope.launch {
                                                        settingsRepository.updateWifiStreamHeight(height)
                                                        settingsRepository.updateWifiStreamTargetFps(fps)
                                                    }
                                                },
                                                onMobileQualityChanged = { height, fps ->
                                                    scope.launch {
                                                        settingsRepository.updateMobileStreamHeight(height)
                                                        settingsRepository.updateMobileStreamTargetFps(fps)
                                                    }
                                                },
                                                onWifiDownloadQualityChanged = { height, fps ->
                                                    scope.launch {
                                                        settingsRepository.updateWifiDownloadHeight(height)
                                                        settingsRepository.updateWifiDownloadTargetFps(fps)
                                                    }
                                                },
                                                onMobileDownloadQualityChanged = { height, fps ->
                                                    scope.launch {
                                                        settingsRepository.updateMobileDownloadHeight(height)
                                                        settingsRepository.updateMobileDownloadTargetFps(fps)
                                                    }
                                                },
                                                allowHighResContent = userSettings.allowHighResContent,
                                                onAllowHighResContentChanged = { enabled ->
                                                    scope.launch { settingsRepository.updateAllowHighResContent(enabled) }
                                                },
                                                onImportUri = { uri ->
                                                    NewPipeSubscriptionImporter.importFromUri(applicationContext, uri)
                                                        .onSuccess { channels ->
                                                            subscriptionDao.insertAll(channels)
                                                        }
                                                        .map { it.size }
                                                },
                                                topContentPadding = settingsTopClearance,
                                                bottomContentPadding = feedBottomClearance,
                                                endContentPadding = feedEndClearance,
                                                onBackOverlayChanged = { key, visible -> setBackOverlay(key, visible) }
                                            )
                                        }
                                    }
                                }

                                composable("channels") {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(bottom = measuredTabBarDp)
                                    ) {
                                        ChannelListScreen(
                                            subscriptions = subscriptions,
                                            searchRepo = searchRepo,
                                            onChannelClick = { subscription ->
                                                navController.navigate(
                                                    "channel/${subscription.channelId}?name=${Uri.encode(subscription.name)}"
                                                )
                                            },
                                            onToggleSubscribe = { channel ->
                                                scope.launch {
                                                    if (channel.channelId in subscriptions.map { it.channelId }) {
                                                        subscriptionDao.deleteSubscription(channel.channelId)
                                                    } else {
                                                        subscriptionDao.insertSubscription(channel)
                                                    }
                                                }
                                            },
                                            hazeState = hazeState,
                                            glassStyle = sharedGlassStyle,
                                            onAvatarResolved = { channelId, thumbnailUrl ->
                                                scope.launch {
                                                    subscriptionDao.updateThumbnail(channelId, thumbnailUrl)
                                                }
                                            }
                                        )
                                    }
                                }

                                composable(
                                    route = "channel/{channelId}?name={name}",
                                    arguments = listOf(
                                        navArgument("channelId") { type = NavType.StringType },
                                        navArgument("name") {
                                            type = NavType.StringType
                                            defaultValue = ""
                                        }
                                    )
                                ) { backStackEntry ->
                                    val channelId = backStackEntry.arguments?.getString("channelId").orEmpty()
                                    val channelName = backStackEntry.arguments?.getString("name").orEmpty()
                                    val subscription = subscriptions.firstOrNull { it.channelId == channelId }
                                        ?: SubscriptionEntity(
                                            channelId = channelId,
                                            name = channelName.ifBlank { "Channel" }
                                        )
                                    val channelViewModel: ChannelDetailViewModel = viewModel(
                                        key = "channel-$channelId",
                                        factory = object : ViewModelProvider.Factory {
                                            @Suppress("UNCHECKED_CAST")
                                            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                                                return ChannelDetailViewModel(
                                                    subscription = subscription,
                                                    feedService = feedService
                                                ) as T
                                            }
                                        }
                                    )
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(bottom = measuredTabBarDp)
                                    ) {
                                        ChannelDetailScreen(
                                            channel = subscription,
                                            viewModel = channelViewModel,
                                            watchProgressRepository = watchProgressRepository,
                                            onBack = { navController.popBackStack() },
                                            onVideoClick = { videoId ->
                                                openQueueVideo(QueueVideoItem(videoId, "Video", subscription.name))
                                            },
                                            isSubscribed = channelId in subscriptions.map { it.channelId },
                                            onToggleSubscribe = {
                                                scope.launch {
                                                    if (channelId in subscriptions.map { it.channelId }) {
                                                        subscriptionDao.deleteSubscription(channelId)
                                                    } else {
                                                        subscriptionDao.insertSubscription(subscription)
                                                    }
                                                }
                                            }
                                        )
                                    }
                                }

                                composable("watch/{videoId}") { backStackEntry ->
                                    val videoId = backStackEntry.arguments?.getString("videoId") ?: ""
                                    LaunchedEffect(videoId) {
                                        openQueueVideo(QueueVideoItem(videoId, "Video", ""))
                                        navController.popBackStack()
                                    }
                                }
                            }
                    }

                // Frosted status bar overlay in portrait mode
                if (showStatusBar) {
                    val barShape = RoundedCornerShape(
                        bottomStart = 20.dp,
                        bottomEnd = 20.dp
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .height(statusBarTop + 4.dp)
                            .zIndex(2f)
                            .shadow(
                                elevation = 6.dp,
                                shape = barShape,
                                ambientColor = Color.Black.copy(alpha = 0.25f),
                                spotColor = Color.Black.copy(alpha = 0.25f)
                            )
                            .clip(barShape)
                            .hazeEffect(state = hazeState, style = sharedGlassStyle)
                            .background(scheme.surfaceContainer.copy(alpha = 0.62f))
                    )
                }

                // Feed multi-select bar. It is drawn as a sibling of the haze source,
                // exactly like the tab bar, so it samples the same content with the same
                // haze state/style and therefore matches the tab bar and download dialog.
                val selectionBar = feedSelectionBar
                if (
                    selectionBar != null &&
                    !isWatchRoute &&
                    tabs.getOrNull(pagerState.currentPage) == AppTab.SUBSCRIPTIONS
                ) {
                    FeedSelectionBar(
                        state = selectionBar,
                        hazeState = hazeState,
                        glassStyle = sharedGlassStyle,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = statusBarTop)
                            .fillMaxWidth()
                            .zIndex(6f)
                    )
                }

                // Feed video action menu. A sibling of the haze source (not a dialog window)
                // so the scrim cannot blur the feed, and the menu can still sample the feed's
                // haze state for the same frosted-glass look as the tab bar.
                val videoMenu = feedVideoMenu
                if (
                    videoMenu != null &&
                    !isWatchRoute &&
                    tabs.getOrNull(pagerState.currentPage) == AppTab.SUBSCRIPTIONS
                ) {
                    FeedVideoMenuOverlay(
                        state = videoMenu,
                        hazeState = hazeState,
                        glassStyle = sharedGlassStyle,
                        modifier = Modifier
                            .fillMaxSize()
                            .zIndex(7f)
                    )
                }

                // Search now lives in its own centered tab, Channels is opened from
                // Settings, and Downloads has its own sort control. No top pill is needed.

                // 3. Frosted tab bar: NavigationRail on right in landscape, NavigationBar on bottom in portrait.
                if (!isWatchRoute) {
                    if (isLandscape) {
                        val hideTabBar = activeVideo != null || hasOfflineFullscreenPlayer
                        val tabBarAlpha = if (hideTabBar) 0f else 1f
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .fillMaxHeight()
                                .width(measuredTabBarDp)
                                .zIndex(if (hideTabBar) -10f else 3f)
                                .graphicsLayer { alpha = tabBarAlpha }
                                .hazeEffect(state = hazeState, style = sharedGlassStyle)
                                .background(scheme.surfaceContainer.copy(alpha = 0.62f))
                        ) {
                            Column(
                                verticalArrangement = Arrangement.SpaceEvenly,
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth()
                                    .navigationBarsPadding()
                                    .padding(vertical = 16.dp)
                            ) {
                                tabs.forEachIndexed { index, tab ->
                                    NavigationRailItem(
                                        selected = pagerState.currentPage == index,
                                        onClick = { selectTab(index) },
                                        icon = {
                                            Icon(
                                                imageVector = tab.icon,
                                                contentDescription = tab.label
                                            )
                                        },
                                        label = {
                                            Text(
                                                text = tab.label,
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                        },
                                        colors = NavigationRailItemDefaults.colors(
                                            selectedIconColor = scheme.onSurface,
                                            selectedTextColor = scheme.onSurface,
                                            indicatorColor = scheme.primary.copy(alpha = 0.25f),
                                            unselectedIconColor = scheme.onSurfaceVariant,
                                            unselectedTextColor = scheme.onSurfaceVariant
                                        )
                                    )
                                }
                            }
                        }
                    } else {
                        // Fade in on the same curve the comments and scrim fade out.
                        val tabBarAlpha = when {
                            activeVideo != null -> (playerCollapseProgress * 2.5f).coerceIn(0f, 1f)
                            hasOfflineFullscreenPlayer -> 0f
                            else -> 1f
                        }
                        // The tab bar's top corners square off as the mini-player docks,
                        // so the two panels become one continuous glass bar.
                        val mergeProgress = if (activeVideo != null) playerCollapseProgress else 0f
                        val tabTopCorner = lerp(20.dp, 0.dp, mergeProgress)
                        val tabBarShape = RoundedCornerShape(
                            topStart = tabTopCorner,
                            topEnd = tabTopCorner,
                            bottomStart = 0.dp,
                            bottomEnd = 0.dp
                        )
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .zIndex(if ((activeVideo == null && !hasOfflineFullscreenPlayer) || playerCollapseProgress > 0.3f) 3f else -10f)
                                .graphicsLayer { alpha = tabBarAlpha }
                                .onSizeChanged { size ->
                                    if (size.height > 0) bottomBarHeightPx = size.height
                                }
                                .frostedGlass(
                                    hazeState = hazeState,
                                    glassStyle = sharedGlassStyle,
                                    shape = tabBarShape,
                                    elevation = 10.dp * (1f - mergeProgress)
                                )
                        ) {
                            NavigationBar(
                                containerColor = Color.Transparent,
                                contentColor = scheme.onSurface,
                                tonalElevation = 0.dp,
                                modifier = Modifier.navigationBarsPadding()
                            ) {
                                tabs.forEachIndexed { index, tab ->
                                    NavigationBarItem(
                                        selected = pagerState.currentPage == index,
                                        onClick = { selectTab(index) },
                                        icon = {
                                            Icon(
                                                imageVector = tab.icon,
                                                contentDescription = tab.label
                                            )
                                        },
                                        label = {
                                            Text(
                                                text = tab.label,
                                                style = MaterialTheme.typography.labelSmall,
                                                maxLines = 1
                                            )
                                        },
                                        colors = NavigationBarItemDefaults.colors(
                                            selectedIconColor = scheme.onSurface,
                                            selectedTextColor = scheme.onSurface,
                                            indicatorColor = scheme.primary.copy(alpha = 0.25f),
                                            unselectedIconColor = scheme.onSurfaceVariant,
                                            unselectedTextColor = scheme.onSurfaceVariant
                                        )
                                    )
                                }
                            }
                        }
                    }
                }

                // 4. Player host. Normal uses the draggable full/mini player with comments;
                //    FLOATING is a compact draggable window; BACKGROUND_AUDIO is invisible.
                activeVideo?.let { video ->
                    val onPlaybackEndedForMode: () -> Unit = {
                        val isOffline = video.localVideoPath != null
                        stopPlayback()
                        if (isOffline) {
                            selectTab(AppTab.DOWNLOADS.ordinal)
                        } else if (queueController.hasNext) {
                            queueController.playNext()?.let { switchQueueVideo(it) }
                        }
                    }

                    when (playbackMode) {
                        PlaybackMode.NORMAL -> {
                            val metadataRepo = remember { VideoMetadataRepository(app.okHttpClient) }
                            DraggableVideoPlayerOverlay(
                                videoId = video.videoId,
                                title = video.title,
                                channelName = video.channelName,
                                localVideoPath = video.localVideoPath,
                                fallbackTitle = video.title,
                                fallbackChannel = video.channelName,
                                okHttpClient = app.okHttpClient,
                                visitorData = shortsViewModel.visitorData,
                                watchProgressRepository = watchProgressRepository,
                                bottomBarHeightPx = bottomBarHeightPx,
                                hazeState = hazeState,
                                glassStyle = sharedGlassStyle,
                                hasPreviousVideo = queueController.hasPrevious,
                                hasNextVideo = queueController.hasNext,
                                onPreviousVideo = {
                                    queueController.playPrevious()?.let { switchQueueVideo(it) }
                                },
                                onNextVideo = {
                                    queueController.playNext()?.let { switchQueueVideo(it) }
                                },
                                onClosePlayer = {
                                    val isOffline = video.localVideoPath != null
                                    stopPlayback()
                                    if (isOffline) {
                                        selectTab(AppTab.DOWNLOADS.ordinal)
                                    }
                                },
                                onPlaybackEnded = onPlaybackEndedForMode,
                                onMinimizedChange = { isPlayerMinimized = it },
                                onCollapseProgressChange = { playerCollapseProgress = it },
                                preferredAudioLanguage = userSettings.preferredAudioLanguage,
                                commentsContent = {
                                    VideoDetailsAndCommentsSection(
                                        videoId = video.videoId,
                                        fallbackTitle = video.title,
                                        fallbackChannel = video.channelName,
                                        metadataRepo = metadataRepo
                                    )
                                }
                            )
                        }

                        PlaybackMode.FLOATING -> {
                            FloatingVideoWindow(
                                videoId = video.videoId,
                                title = video.title,
                                channelName = video.channelName,
                                localVideoPath = video.localVideoPath,
                                okHttpClient = app.okHttpClient,
                                visitorData = shortsViewModel.visitorData,
                                watchProgressRepository = watchProgressRepository,
                                preferredAudioLanguage = userSettings.preferredAudioLanguage,
                                hasPreviousVideo = queueController.hasPrevious,
                                hasNextVideo = queueController.hasNext,
                                onPreviousVideo = {
                                    queueController.playPrevious()?.let { switchQueueVideo(it) }
                                },
                                onNextVideo = {
                                    queueController.playNext()?.let { switchQueueVideo(it) }
                                },
                                onPlaybackEnded = onPlaybackEndedForMode,
                                onOpenFullPlayer = {
                                    // Portrait promotes to the regular player with comments;
                                    // landscape DraggableVideoPlayerOverlay is already fullscreen.
                                    playbackMode = PlaybackMode.NORMAL
                                    isPlayerMinimized = false
                                    playerCollapseProgress = 0f
                                },
                                onClose = { stopPlayback() }
                            )
                        }

                        PlaybackMode.BACKGROUND_AUDIO -> {
                            // Keep the real player mounted but visually absent. BackgroundWebView
                            // keeps Chromium's audio renderer alive while the app is hidden.
                            Box(
                                modifier = Modifier
                                    .size(1.dp)
                                    .alpha(0f)
                            ) {
                                VideoPlayerScreen(
                                    videoId = video.videoId,
                                    okHttpClient = app.okHttpClient,
                                    visitorData = shortsViewModel.visitorData,
                                    watchProgressRepository = watchProgressRepository,
                                    preferredAudioLanguage = userSettings.preferredAudioLanguage,
                                    localVideoPath = video.localVideoPath,
                                    fallbackTitle = video.title,
                                    fallbackChannel = video.channelName,
                                    embeddedMode = true,
                                    showExpandedControls = false,
                                    hasPreviousVideo = queueController.hasPrevious,
                                    hasNextVideo = queueController.hasNext,
                                    onPreviousVideo = {
                                        queueController.playPrevious()?.let { switchQueueVideo(it) }
                                    },
                                    onNextVideo = {
                                        queueController.playNext()?.let { switchQueueVideo(it) }
                                    },
                                    onPlaybackEnded = onPlaybackEndedForMode
                                )
                            }
                        }
                    }
                }
                }

                // Only a newer release is surfaced at launch.
                startupUpdate?.let { release ->
                    StartupUpdateDialog(
                        release = release,
                        onDismiss = { startupUpdate = null }
                    )
                }
            }
        }
    }
}

/** Dialog shown when the automatic launch check discovers a newer APK on GitHub Releases. */
@Composable
private fun StartupUpdateDialog(
    release: ReleaseInfo,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isDownloading by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!isDownloading) onDismiss() },
        title = { Text("Update Available (${release.tagName})") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Installed: v${AppUpdater.getInstalledVersionName(context)} → Latest: ${release.tagName}",
                    style = MaterialTheme.typography.labelMedium
                )
                Text(release.releaseNotes, style = MaterialTheme.typography.bodySmall)
                if (isDownloading) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                error?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !isDownloading,
                onClick = {
                    scope.launch {
                        isDownloading = true
                        error = null
                        progress = 0f
                        AppUpdater.downloadAndInstallApk(context, release.apkDownloadUrl) {
                            progress = it
                        }.onFailure {
                            error = it.message
                        }.onSuccess {
                            onDismiss()
                        }
                        isDownloading = false
                    }
                }
            ) {
                Text(if (isDownloading) "Downloading..." else "Download & Install")
            }
        },
        dismissButton = {
            TextButton(enabled = !isDownloading, onClick = onDismiss) {
                Text("Later")
            }
        }
    )
}
