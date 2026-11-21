package io.tehred5pade.frontporch.ui.feed

import android.content.res.Configuration
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import io.tehred5pade.frontporch.FrontPorchApp
import io.tehred5pade.frontporch.data.download.VideoDownloadManager
import io.tehred5pade.frontporch.data.innertube.FeedVideoItem
import io.tehred5pade.frontporch.data.innertube.formatRelativeTime
import io.tehred5pade.frontporch.data.local.WatchProgressRepository
import io.tehred5pade.frontporch.data.playlist.PlaylistEntry
import io.tehred5pade.frontporch.data.playlist.PlaylistStore
import io.tehred5pade.frontporch.ui.common.frostedGlass
import io.tehred5pade.frontporch.ui.downloads.DownloadQualityDialog
import io.tehred5pade.frontporch.ui.playlist.PlaylistDialog
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(
    viewModel: FeedViewModel,
    // Bumped when the Subscriptions tab is tapped again: reload and return to the top.
    reloadTick: Int = 0,
    downloadManager: VideoDownloadManager,
    watchProgressRepository: WatchProgressRepository,
    hazeState: HazeState? = null,
    glassStyle: HazeStyle? = null,
    onVideoClick: (FeedVideoItem) -> Unit,
    onNavigateToSettings: () -> Unit,
    topContentPadding: Dp = 0.dp,
    bottomContentPadding: Dp = 0.dp,
    preferredAudioLanguage: String = "en",
    defaultDownloadQuality: String = "BEST_AVAILABLE",
    playlistStore: PlaylistStore? = null,
    onPlayPlaylistEntry: (PlaylistEntry) -> Unit = {},
    onStartBackgroundPlay: (FeedVideoItem) -> Unit = { onVideoClick(it) },
    onStartPopupPlay: (FeedVideoItem) -> Unit = { onVideoClick(it) },
    onBackOverlayChanged: (String, Boolean) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val app = context.applicationContext as FrontPorchApp
    val okHttpClient = app.okHttpClient

    val subscriptions by viewModel.subscriptions.collectAsState()
    val videos by viewModel.videos.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val isLoadingMore by viewModel.isLoadingMore.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val newVideosAvailable by viewModel.newVideosAvailable.collectAsState()

    // Watched fraction per videoId, used for the progress bar on each thumbnail.
    val progressById by watchProgressRepository.progressFlow.collectAsState(initial = emptyMap())

    var activeMenuVideo by remember { mutableStateOf<FeedVideoItem?>(null) }
    var selectedVideoIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var isSelectionMode by remember { mutableStateOf(false) }
    var pendingDownloadVideos by remember { mutableStateOf<List<FeedVideoItem>>(emptyList()) }
    var playlistDialogEntry by remember { mutableStateOf<PlaylistEntry?>(null) }

    // Keep these sections from also triggering the root "exit app" back handler.
    DisposableEffect(activeMenuVideo != null) {
        onBackOverlayChanged("feed-video-menu", activeMenuVideo != null)
        onDispose { onBackOverlayChanged("feed-video-menu", false) }
    }
    DisposableEffect(isSelectionMode) {
        onBackOverlayChanged("feed-selection", isSelectionMode)
        onDispose { onBackOverlayChanged("feed-selection", false) }
    }
    DisposableEffect(pendingDownloadVideos.isNotEmpty()) {
        onBackOverlayChanged("download-quality-dialog", pendingDownloadVideos.isNotEmpty())
        onDispose { onBackOverlayChanged("download-quality-dialog", false) }
    }
    DisposableEffect(playlistDialogEntry != null) {
        onBackOverlayChanged("playlist-dialog", playlistDialogEntry != null)
        onDispose { onBackOverlayChanged("playlist-dialog", false) }
    }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Whether the user was still at the top when the refresh started / while it ran. When
    // they are, new videos are pulled into view; otherwise the "new videos" arrow is shown.
    var keepAtTop by remember { mutableStateOf(true) }

    LaunchedEffect(reloadTick) {
        if (reloadTick == 0) return@LaunchedEffect
        listState.scrollToItem(0)
        keepAtTop = true
        viewModel.refreshFeed()
    }

    val isScrolledDown by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0
        }
    }

    LaunchedEffect(Unit) {
        snapshotFlow { isScrolledDown to isRefreshing }.collect { (scrolled, refreshing) ->
            // Once new videos have arrived, the list may re-anchor and look scrolled;
            // do not let that overwrite the decision made while the refresh was running.
            if (refreshing && !newVideosAvailable) keepAtTop = !scrolled
        }
    }

    LaunchedEffect(newVideosAvailable, isScrolledDown) {
        if (!newVideosAvailable) return@LaunchedEffect
        if (keepAtTop || !isScrolledDown) {
            keepAtTop = true
            listState.animateScrollToItem(0)
            viewModel.clearNewVideosAvailable()
        }
    }

    val shouldLoadMore = remember {
        derivedStateOf {
            val totalItems = listState.layoutInfo.totalItemsCount
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            totalItems > 0 && lastVisible >= (totalItems - 5)
        }
    }

    LaunchedEffect(shouldLoadMore.value) {
        if (shouldLoadMore.value) {
            viewModel.loadNextPage()
        }
    }

    if (pendingDownloadVideos.isNotEmpty()) {
        DownloadQualityDialog(
            videoCount = pendingDownloadVideos.size,
            defaultQualityName = defaultDownloadQuality,
            onDismiss = { pendingDownloadVideos = emptyList() },
            onConfirm = { quality ->
                val targets = pendingDownloadVideos
                pendingDownloadVideos = emptyList()
                downloadManager.enqueueVideos(targets, quality, preferredAudioLanguage)
                selectedVideoIds = emptySet()
                isSelectionMode = false
                Toast.makeText(
                    context,
                    "Downloading ${targets.size} video(s) (${quality.label})",
                    Toast.LENGTH_SHORT
                ).show()
            }
        )
    }

    val dialogEntry = playlistDialogEntry
    if (dialogEntry != null && playlistStore != null) {
        PlaylistDialog(
            store = playlistStore,
            okHttpClient = okHttpClient,
            entry = dialogEntry,
            onPlayEntry = onPlayPlaylistEntry,
            onDismiss = { playlistDialogEntry = null }
        )
    }

    fun handleMenuAction(video: FeedVideoItem, action: VideoMenuAction) {
        handleVideoMenuAction(
            context = context,
            video = video,
            action = action,
            onOpenPlaylistDialog = { videoItem ->
                playlistDialogEntry = PlaylistEntry(
                    videoId = videoItem.videoId,
                    title = videoItem.title,
                    channelName = videoItem.channelName,
                    thumbnailUrl = videoItem.thumbnailUrl
                )
            },
            onQueueDownload = { videoItem ->
                pendingDownloadVideos = listOf(videoItem)
            },
            onStartBackgroundPlay = { videoItem -> onStartBackgroundPlay(videoItem) },
            onStartPopupPlay = { videoItem -> onStartPopupPlay(videoItem) }
        )
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        if (subscriptions.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "No Subscriptions Imported Yet",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Import your NewPipe subscriptions in Settings to load videos from creators you follow.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = onNavigateToSettings) {
                        Text("Go to Settings")
                    }
                }
            }
            return@Surface
        }

        Column(modifier = Modifier.fillMaxSize()) {
            if (selectedVideoIds.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    tonalElevation = 4.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        IconButton(onClick = {
                            selectedVideoIds = emptySet()
                            isSelectionMode = false
                        }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Cancel selection"
                            )
                        }
                        Text(
                            text = "${selectedVideoIds.size} selected",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = {
                                pendingDownloadVideos = videos.filter { it.videoId in selectedVideoIds }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Download,
                                contentDescription = "Download selected",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Download")
                        }
                    }
                }
            }

            val pullToRefreshState = rememberPullToRefreshState()
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = {
                    keepAtTop = !isScrolledDown
                    viewModel.refreshFeed()
                },
                state = pullToRefreshState,
                modifier = Modifier.fillMaxSize(),
                indicator = {
                    if (isRefreshing || pullToRefreshState.distanceFraction > 0f) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(64.dp)
                                    .frostedGlass(
                                        hazeState = hazeState,
                                        glassStyle = glassStyle,
                                        shape = RoundedCornerShape(32.dp),
                                        elevation = 12.dp
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                val progress = if (isRefreshing) {
                                    null
                                } else {
                                    pullToRefreshState.distanceFraction.coerceIn(0f, 1f)
                                }

                                if (progress != null) {
                                    CircularProgressIndicator(
                                        progress = { progress },
                                        modifier = Modifier.size(32.dp),
                                        strokeWidth = 3.dp,
                                        color = MaterialTheme.colorScheme.primary,
                                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                                    )
                                } else {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(32.dp),
                                        strokeWidth = 3.dp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
            ) {
                when {
                    videos.isEmpty() && isRefreshing -> {
                        // The pinned search bar carries the launch-loading animation,
                        // so keep this area clear while the first batch arrives.
                        Box(modifier = Modifier.fillMaxSize())
                    }
                    videos.isEmpty() -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = errorMessage
                                        ?: "Loaded ${subscriptions.size} channels, but no recent videos appeared.",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Button(onClick = { viewModel.refreshFeed() }) {
                                    Text("Refresh Feed")
                                }
                            }
                        }
                    }
                    else -> {
                        val configuration = LocalConfiguration.current
                        val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                        Box(modifier = Modifier.fillMaxSize()) {
                            if (isLandscape) {
                                LazyVerticalGrid(
                                    columns = GridCells.Fixed(3),
                                    contentPadding = PaddingValues(
                                        start = 16.dp,
                                        end = 96.dp,
                                        top = topContentPadding.coerceAtLeast(60.dp),
                                        bottom = 16.dp
                                    ),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(16.dp),
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    items(
                                        items = videos,
                                        key = { it.videoId }
                                    ) { video ->
                                        val isSelected = selectedVideoIds.contains(video.videoId)
                                        SubscriptionVideoCard(
                                            video = video,
                                            okHttpClient = okHttpClient,
                                            watchedFraction = progressById[video.videoId] ?: 0f,
                                            isSelectionMode = isSelectionMode,
                                            isSelected = isSelected,
                                            isMenuExpanded = activeMenuVideo?.videoId == video.videoId,
                                            onDismissMenu = { activeMenuVideo = null },
                                            onMenuAction = { action -> handleMenuAction(video, action) },
                                            onClick = {
                                                if (isSelectionMode) {
                                                    selectedVideoIds = if (isSelected) {
                                                        selectedVideoIds - video.videoId
                                                    } else {
                                                        selectedVideoIds + video.videoId
                                                    }
                                                    if (selectedVideoIds.isEmpty()) {
                                                        isSelectionMode = false
                                                    }
                                                } else {
                                                    onVideoClick(video)
                                                }
                                            },
                                            onLongPress = {
                                                activeMenuVideo = video
                                            }
                                        )
                                    }

                                    if (isLoadingMore) {
                                        item(span = { GridItemSpan(3) }) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(16.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(32.dp),
                                                    strokeWidth = 3.dp
                                                )
                                            }
                                        }
                                    }
                                }
                            } else {
                                LazyColumn(
                                    state = listState,
                                    contentPadding = PaddingValues(
                                        start = 12.dp,
                                        end = 12.dp,
                                        top = topContentPadding,
                                        bottom = bottomContentPadding
                                    ),
                                    verticalArrangement = Arrangement.spacedBy(16.dp),
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    items(
                                        items = videos,
                                        key = { it.videoId }
                                    ) { video ->
                                        val isSelected = selectedVideoIds.contains(video.videoId)
                                        SubscriptionVideoCard(
                                            video = video,
                                            okHttpClient = okHttpClient,
                                            watchedFraction = progressById[video.videoId] ?: 0f,
                                            isSelectionMode = isSelectionMode,
                                            isSelected = isSelected,
                                            isMenuExpanded = activeMenuVideo?.videoId == video.videoId,
                                            onDismissMenu = { activeMenuVideo = null },
                                            onMenuAction = { action -> handleMenuAction(video, action) },
                                            onClick = {
                                                if (isSelectionMode) {
                                                    selectedVideoIds = if (isSelected) {
                                                        selectedVideoIds - video.videoId
                                                    } else {
                                                        selectedVideoIds + video.videoId
                                                    }
                                                    if (selectedVideoIds.isEmpty()) {
                                                        isSelectionMode = false
                                                    }
                                                } else {
                                                    onVideoClick(video)
                                                }
                                            },
                                            onLongPress = {
                                                activeMenuVideo = video
                                            }
                                        )
                                    }

                                    if (isLoadingMore) {
                                        item {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(16.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(32.dp),
                                                    strokeWidth = 3.dp
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            if (newVideosAvailable && isScrolledDown) {
                                FilledTonalButton(
                                    onClick = {
                                        keepAtTop = true
                                        viewModel.clearNewVideosAvailable()
                                        scope.launch { listState.animateScrollToItem(0) }
                                    },
                                    modifier = Modifier
                                        .align(Alignment.TopCenter)
                                        .padding(top = 8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ArrowUpward,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("New videos")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SubscriptionVideoCard(
    video: FeedVideoItem,
    okHttpClient: OkHttpClient,
    watchedFraction: Float,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    isMenuExpanded: Boolean,
    onDismissMenu: () -> Unit,
    onMenuAction: (VideoMenuAction) -> Unit,
    onClick: () -> Unit,
    onLongPress: () -> Unit
) {
    val haptics = LocalHapticFeedback.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    // Exclude Shorts from the long-press action menu
                    if (!video.isShort) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongPress()
                    }
                }
            )
            .padding(vertical = 4.dp)
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                ) {
                    // Only the video artwork is clipped; the action menu is overlaid
                    // at the card level so it can extend past the thumbnail.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(12.dp))
                    ) {
                        YouTubeThumbnail(
                            videoId = video.videoId,
                            contentDescription = video.title,
                            okHttpClient = okHttpClient,
                            modifier = Modifier.fillMaxSize()
                        )

                        // Length pill and watched-progress bar. Shorts never show a time.
                        if (!video.isShort) {
                            DurationBadge(
                                durationSeconds = video.durationSeconds,
                                isShort = video.isShort,
                                modifier = Modifier.align(Alignment.BottomEnd)
                            )
                            WatchProgressBar(
                                fraction = watchedFraction,
                                modifier = Modifier.align(Alignment.BottomStart)
                            )
                        }
                    }

                    if (isSelectionMode) {
                        Icon(
                            imageVector = if (isSelected) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                            contentDescription = if (isSelected) "Selected" else "Not selected",
                            tint = if (isSelected) MaterialTheme.colorScheme.primary else Color.White,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp)
                                .size(28.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = video.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                val timeLabel = formatRelativeTime(video.publishedEpochMs)
                Text(
                    text = if (timeLabel.isNotEmpty()) "${video.channelName} • $timeLabel" else video.channelName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Anchored 8.dp inside the artwork's top-left, but measured against the
            // whole card so all six flat actions remain visible without scrolling.
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = 8.dp, y = 8.dp)
                    .zIndex(2f)
            ) {
                VideoOptionsPopup(
                    expanded = isMenuExpanded,
                    onDismiss = onDismissMenu,
                    onActionSelected = onMenuAction
                )
            }
        }
    }
}
