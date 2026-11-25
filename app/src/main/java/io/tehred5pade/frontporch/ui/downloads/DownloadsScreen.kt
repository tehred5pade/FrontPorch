package io.tehred5pade.frontporch.ui.downloads

import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.LayoutInflater
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import io.tehred5pade.frontporch.R
import io.tehred5pade.frontporch.data.download.VideoDownloadManager
import io.tehred5pade.frontporch.data.innertube.formatDurationSeconds
import io.tehred5pade.frontporch.data.local.DownloadedVideoDao
import io.tehred5pade.frontporch.data.local.DownloadedVideoEntity
import io.tehred5pade.frontporch.data.local.WatchProgressRepository
import io.tehred5pade.frontporch.data.playlist.PlaylistEntry
import io.tehred5pade.frontporch.data.playlist.PlaylistStore
import io.tehred5pade.frontporch.data.sponsorblock.SponsorBlockPreferences
import io.tehred5pade.frontporch.data.sponsorblock.SponsorBlockRepository
import io.tehred5pade.frontporch.data.sponsorblock.SponsorSegment
import io.tehred5pade.frontporch.data.sponsorblock.SponsorSkipBehavior
import io.tehred5pade.frontporch.service.PlaybackSessionHolder
import io.tehred5pade.frontporch.ui.common.InlineMenu
import io.tehred5pade.frontporch.ui.common.frostedGlass
import io.tehred5pade.frontporch.ui.feed.DurationBadge
import io.tehred5pade.frontporch.ui.feed.WatchProgressBar
import io.tehred5pade.frontporch.ui.player.KeepScreenOnEffect
import io.tehred5pade.frontporch.ui.player.PlayerGestureOverlay
import io.tehred5pade.frontporch.ui.player.SegmentedDotScrubber
import io.tehred5pade.frontporch.ui.playlist.PlaylistDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed

/** Deletes the Room record together with the local video and thumbnail files. */
suspend fun deleteDownloadedVideos(
    videos: List<DownloadedVideoEntity>,
    dao: DownloadedVideoDao,
    context: android.content.Context
) {
    withContext(Dispatchers.IO) {
        videos.forEach { item ->
            runCatching {
                val videoPath = item.localVideoPath
                if (videoPath.isNotBlank()) {
                    val uri = Uri.parse(videoPath)
                    if (uri.scheme == "content") {
                        runCatching { context.contentResolver.delete(uri, null, null) }
                        runCatching { DocumentFile.fromSingleUri(context, uri)?.delete() }
                    } else {
                        fileSystemPath(videoPath)?.let { File(it).delete() }
                    }
                }

                val thumbPath = item.localThumbnailPath
                if (!thumbPath.isNullOrBlank()) {
                    val thumbUri = Uri.parse(thumbPath)
                    if (thumbUri.scheme == "content") {
                        runCatching { context.contentResolver.delete(thumbUri, null, null) }
                        runCatching { DocumentFile.fromSingleUri(context, thumbUri)?.delete() }
                    } else {
                        fileSystemPath(thumbPath)?.let { File(it).delete() }
                    }
                }
            }
            dao.deleteById(item.videoId)
        }
    }
}

/** Only file-backed paths can be deleted directly; SAF content:// URIs are system-managed. */
private fun fileSystemPath(rawPath: String): String? {
    if (rawPath.isBlank()) return null
    return when {
        rawPath.startsWith("file://") -> Uri.parse(rawPath).path
        rawPath.startsWith("/") -> rawPath
        else -> null
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DownloadsScreen(
    // Bumped when the Downloads tab is tapped again: return to the top.
    reloadTick: Int = 0,
    dao: DownloadedVideoDao,
    downloadManager: VideoDownloadManager,
    watchProgressRepository: WatchProgressRepository,
    okHttpClient: OkHttpClient,
    sortNewestFirst: Boolean,
    // Shared with the tab bar so the floating Play button can use the same frosted glass.
    hazeState: HazeState? = null,
    glassStyle: HazeStyle? = null,
    topContentPadding: Dp = 0.dp,
    bottomContentPadding: Dp = 0.dp,
    preferredAudioLanguage: String = "en",
    playlistStore: PlaylistStore? = null,
    onPlayPlaylistEntry: (PlaylistEntry) -> Unit = {},
    onOfflinePlayingChanged: (Boolean) -> Unit = {},
    onPlayVideo: (DownloadedVideoEntity) -> Unit = {},
    onBackOverlayChanged: (String, Boolean) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()

    LaunchedEffect(reloadTick) {
        if (reloadTick == 0) return@LaunchedEffect
        listState.scrollToItem(0)
        gridState.scrollToItem(0)
    }

    val downloads by dao.observeAll().collectAsState(initial = emptyList())
    val activeProgress by downloadManager.activeDownloads.collectAsState()
    val progressById by watchProgressRepository.progressFlow.collectAsState(initial = emptyMap())

    var internalSortNewestFirst by remember { mutableStateOf(sortNewestFirst) }
    val sortedDownloads = remember(downloads, internalSortNewestFirst) {
        if (internalSortNewestFirst) {
            downloads.sortedByDescending { it.downloadedAtMs }
        } else {
            downloads.sortedBy { it.downloadedAtMs }
        }
    }

    // Multi-select & long-press menu state
    var isSelectionMode by remember { mutableStateOf(false) }
    val selectedVideoIds = remember { mutableStateListOf<String>() }
    var menuExpandedForId by remember { mutableStateOf<String?>(null) }
    var showPlaylistDialog by remember { mutableStateOf(false) }
    var playlistDialogEntry by remember { mutableStateOf<PlaylistEntry?>(null) }
    var selectedErrorText by remember { mutableStateOf<String?>(null) }

    // Snapshot of the ordered list + starting index so DB updates at 90% don't interrupt playback
    var activePlaylist by remember { mutableStateOf<List<DownloadedVideoEntity>>(emptyList()) }
    var activePlaylistStartIndex by remember { mutableStateOf<Int?>(null) }

    DisposableEffect(isSelectionMode) {
        onBackOverlayChanged("downloads-selection", isSelectionMode)
        onDispose { onBackOverlayChanged("downloads-selection", false) }
    }
    DisposableEffect(menuExpandedForId != null) {
        onBackOverlayChanged("downloads-item-menu", menuExpandedForId != null)
        onDispose { onBackOverlayChanged("downloads-item-menu", false) }
    }
    val isPlaylistDialogOpen = showPlaylistDialog || playlistDialogEntry != null
    DisposableEffect(isPlaylistDialogOpen) {
        onBackOverlayChanged("playlist-dialog", isPlaylistDialogOpen)
        onDispose { onBackOverlayChanged("playlist-dialog", false) }
    }

    BackHandler(enabled = isSelectionMode) {
        isSelectionMode = false
        selectedVideoIds.clear()
    }

    LaunchedEffect(sortedDownloads.size) {
        if (sortedDownloads.isEmpty()) {
            isSelectionMode = false
            selectedVideoIds.clear()
        }
    }

    if (playlistStore != null && isPlaylistDialogOpen) {
        PlaylistDialog(
            store = playlistStore,
            okHttpClient = okHttpClient,
            entry = playlistDialogEntry,
            hazeState = hazeState,
            glassStyle = glassStyle,
            onPlayEntry = onPlayPlaylistEntry,
            onDismiss = {
                showPlaylistDialog = false
                playlistDialogEntry = null
            }
        )
    }

    val startIdx = activePlaylistStartIndex
    if (startIdx != null && activePlaylist.isNotEmpty()) {
        OfflineVideoPlayer(
            playlist = activePlaylist,
            startIndex = startIdx,
            sponsorBlockRepo = remember { SponsorBlockRepository(okHttpClient) },
            onMarkVideoWatched = { watchedVideoId ->
                scope.launch {
                    dao.setWatched(watchedVideoId, true)
                }
            },
            onPlayingStateChanged = onOfflinePlayingChanged,
            onClose = {
                activePlaylistStartIndex = null
                activePlaylist = emptyList()
                onOfflinePlayingChanged(false)
            }
        )
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
        val configuration = LocalConfiguration.current
        val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        val headerContent: @Composable () -> Unit = {
            if (isSelectionMode) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    IconButton(
                        onClick = {
                            isSelectionMode = false
                            selectedVideoIds.clear()
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Cancel selection",
                            tint = scheme.onBackground
                        )
                    }
                    Text(
                        text = "${selectedVideoIds.size} selected",
                        style = MaterialTheme.typography.titleMedium,
                        color = scheme.onBackground,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = {
                            if (selectedVideoIds.size == sortedDownloads.size) {
                                selectedVideoIds.clear()
                            } else {
                                selectedVideoIds.clear()
                                selectedVideoIds.addAll(sortedDownloads.map { it.videoId })
                            }
                        }
                    ) {
                        Text(
                            text = if (selectedVideoIds.size == sortedDownloads.size) {
                                "Deselect All"
                            } else {
                                "Select All"
                            },
                            color = scheme.primary
                        )
                    }
                }
            }
        }

        val emptyContent: @Composable () -> Unit = {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 48.dp)
            ) {
                Text(
                    text = "No downloaded videos yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant
                )
            }
        }

        val activeDownloadsContent: @Composable (Any) -> Unit = { activeAny ->
            val active = activeAny as? io.tehred5pade.frontporch.data.download.ActiveDownloadItem
            if (active != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = scheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = active.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = scheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "${active.channelName} • ${active.qualityLabel} • ${active.statusText}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (active.isFailed) MaterialTheme.colorScheme.error else scheme.onSurfaceVariant,
                            modifier = Modifier.let {
                                if (active.isFailed) {
                                    it.clickable { selectedErrorText = active.statusText }
                                } else it
                            }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            LinearProgressIndicator(
                                progress = { active.progress },
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 8.dp)
                            )
                            if (active.isFailed) {
                                TextButton(
                                    onClick = { downloadManager.retryDownload(active.videoId) },
                                    modifier = Modifier.padding(end = 4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Refresh,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(text = "Retry")
                                }
                            }
                            IconButton(
                                onClick = { downloadManager.cancelDownload(active.videoId) },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = if (active.isFailed) {
                                        "Remove failed download"
                                    } else {
                                        "Cancel download"
                                    },
                                    tint = scheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        if (isLandscape) {
            LazyVerticalGrid(
                state = gridState,
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
                item(span = { GridItemSpan(3) }, key = "downloads_header") {
                    headerContent()
                }

                if (sortedDownloads.isEmpty() && activeProgress.isEmpty()) {
                    item(span = { GridItemSpan(3) }, key = "empty_downloads") {
                        emptyContent()
                    }
                } else {
                    gridItems(activeProgress.values.toList(), key = { "active_${it.videoId}" }, span = { GridItemSpan(3) }) { active ->
                        activeDownloadsContent(active)
                    }

                    gridItemsIndexed(
                        items = sortedDownloads,
                        key = { _, item -> item.videoId }
                    ) { index, item ->
                        DownloadedVideoRow(
                            item = item,
                            watchedFraction = progressById[item.videoId] ?: 0f,
                            isSelectionMode = isSelectionMode,
                            isSelected = item.videoId in selectedVideoIds,
                            isMenuExpanded = menuExpandedForId == item.videoId,
                            hazeState = hazeState,
                            glassStyle = glassStyle,
                            onClick = {
                                if (isSelectionMode) {
                                    if (item.videoId in selectedVideoIds) {
                                        selectedVideoIds.remove(item.videoId)
                                    } else {
                                        selectedVideoIds.add(item.videoId)
                                    }
                                } else {
                                    activePlaylist = sortedDownloads.toList()
                                    activePlaylistStartIndex = index
                                }
                            },
                            onLongClick = {
                                if (!isSelectionMode) menuExpandedForId = item.videoId
                            },
                            onSelect = {
                                menuExpandedForId = null
                                isSelectionMode = true
                                if (item.videoId !in selectedVideoIds) {
                                    selectedVideoIds.add(item.videoId)
                                }
                            },
                            onDelete = {
                                menuExpandedForId = null
                                scope.launch { deleteDownloadedVideos(listOf(item), dao, context) }
                            },
                            onAddToPlaylist = {
                                menuExpandedForId = null
                                playlistDialogEntry = PlaylistEntry(
                                    videoId = item.videoId,
                                    title = item.title,
                                    channelName = item.channelName,
                                    localVideoPath = item.localVideoPath,
                                    localThumbnailPath = item.localThumbnailPath
                                )
                            },
                            onDismissMenu = { menuExpandedForId = null }
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = topContentPadding,
                    bottom = bottomContentPadding + 76.dp
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                item(key = "downloads_header") {
                    headerContent()
                }

                if (sortedDownloads.isEmpty() && activeProgress.isEmpty()) {
                    item(key = "empty_downloads") {
                        emptyContent()
                    }
                } else {
                    items(activeProgress.values.toList(), key = { "active_${it.videoId}" }) { active ->
                        activeDownloadsContent(active)
                    }

                    itemsIndexed(
                        items = sortedDownloads,
                        key = { _, item -> item.videoId }
                    ) { index, item ->
                        DownloadedVideoRow(
                            item = item,
                            watchedFraction = progressById[item.videoId] ?: 0f,
                            isSelectionMode = isSelectionMode,
                            isSelected = item.videoId in selectedVideoIds,
                            isMenuExpanded = menuExpandedForId == item.videoId,
                            hazeState = hazeState,
                            glassStyle = glassStyle,
                            onClick = {
                                if (isSelectionMode) {
                                    if (item.videoId in selectedVideoIds) {
                                        selectedVideoIds.remove(item.videoId)
                                    } else {
                                        selectedVideoIds.add(item.videoId)
                                    }
                                } else {
                                    activePlaylist = sortedDownloads.toList()
                                    activePlaylistStartIndex = index
                                }
                            },
                            onLongClick = {
                                if (!isSelectionMode) menuExpandedForId = item.videoId
                            },
                            onSelect = {
                                menuExpandedForId = null
                                isSelectionMode = true
                                if (item.videoId !in selectedVideoIds) {
                                    selectedVideoIds.add(item.videoId)
                                }
                            },
                            onDelete = {
                                menuExpandedForId = null
                                scope.launch { deleteDownloadedVideos(listOf(item), dao, context) }
                            },
                            onAddToPlaylist = {
                                menuExpandedForId = null
                                playlistDialogEntry = PlaylistEntry(
                                    videoId = item.videoId,
                                    title = item.title,
                                    channelName = item.channelName,
                                    localVideoPath = item.localVideoPath,
                                    localThumbnailPath = item.localThumbnailPath
                                )
                            },
                            onDismissMenu = { menuExpandedForId = null }
                        )
                    }
                }
            }
        }

        if (isSelectionMode) {
            val selectionBarShape = RoundedCornerShape(18.dp)
            Surface(
                color = Color.Transparent,
                shape = selectionBarShape,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = bottomContentPadding)
                    .padding(horizontal = 16.dp)
                    .frostedGlass(
                        hazeState = hazeState,
                        glassStyle = glassStyle,
                        shape = selectionBarShape,
                        elevation = 10.dp
                    )
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                ) {
                    IconButton(
                        enabled = selectedVideoIds.isNotEmpty(),
                        onClick = {
                            val toDelete = sortedDownloads.filter { item -> item.videoId in selectedVideoIds }
                            scope.launch {
                                deleteDownloadedVideos(toDelete, dao, context)
                                selectedVideoIds.clear()
                                isSelectionMode = false
                            }
                        },
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(
                                if (selectedVideoIds.isNotEmpty()) {
                                    scheme.primary
                                } else {
                                    scheme.onSurface.copy(alpha = 0.12f)
                                }
                            )
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = "Delete selected videos",
                            tint = if (selectedVideoIds.isNotEmpty()) {
                                scheme.onPrimary
                            } else {
                                scheme.onSurfaceVariant
                            }
                        )
                    }
                }
            }
        } else if (sortedDownloads.isNotEmpty() || playlistStore != null) {
            // Sort + playlist pill pinned top-right, using the exact same frosted
            // glass recipe as the tab bar. The playlists action must stay available
            // even with no downloads so playlists can always be browsed/managed.
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(
                        end = if (isLandscape) 96.dp else 16.dp,
                        top = topContentPadding
                    )
                    .frostedGlass(
                        hazeState = hazeState,
                        glassStyle = glassStyle,
                        shape = RoundedCornerShape(50),
                        elevation = 6.dp
                    )
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 2.dp)
                ) {
                    if (sortedDownloads.isNotEmpty()) {
                        IconButton(
                            onClick = { internalSortNewestFirst = !internalSortNewestFirst },
                            modifier = Modifier.size(44.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Sort,
                                contentDescription = if (internalSortNewestFirst) "Sorted Newest First" else "Sorted Oldest First",
                                tint = scheme.onSurface,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        Box(
                            modifier = Modifier
                                .size(width = 1.dp, height = 24.dp)
                                .background(scheme.onSurface.copy(alpha = 0.12f))
                        )
                    }

                    if (playlistStore != null) {
                        IconButton(
                            onClick = { showPlaylistDialog = true },
                            modifier = Modifier.size(44.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.PlaylistAdd,
                                contentDescription = "Playlists",
                                tint = scheme.onSurface,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            }

            // Squircle Play button, sharing the tab bar's frost recipe.
            if (sortedDownloads.isNotEmpty()) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(
                            end = if (isLandscape) 96.dp else 16.dp,
                            bottom = if (isLandscape) 16.dp else bottomContentPadding
                        )
                        .size(56.dp)
                        .frostedGlass(
                            hazeState = hazeState,
                            glassStyle = glassStyle,
                            shape = RoundedCornerShape(24.dp)
                        )
                        .clickable {
                            activePlaylist = sortedDownloads.toList()
                            activePlaylistStartIndex = 0
                        }
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = "Play All",
                        tint = scheme.onSurface,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }
    }

    if (selectedErrorText != null) {
        AlertDialog(
            onDismissRequest = { selectedErrorText = null },
            title = { Text("Download Error Details") },
            text = {
                SelectionContainer {
                    Text(
                        text = selectedErrorText ?: "",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedErrorText = null }) {
                    Text("Close")
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DownloadedVideoRow(
    item: DownloadedVideoEntity,
    watchedFraction: Float,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    isMenuExpanded: Boolean,
    hazeState: HazeState? = null,
    glassStyle: HazeStyle? = null,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onDismissMenu: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val thumbBitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(
        initialValue = null,
        key1 = item.localThumbnailPath
    ) {
        value = withContext(Dispatchers.IO) {
            val path = item.localThumbnailPath ?: return@withContext null
            val file = File(path)
            if (!file.exists()) return@withContext null
            BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
        }
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        Surface(
            color = if (isSelected) {
                scheme.primary.copy(alpha = 0.18f)
            } else {
                scheme.surfaceVariant.copy(alpha = 0.55f)
            },
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (isSelected) {
                        Modifier.border(
                            width = 1.dp,
                            color = scheme.primary,
                            shape = RoundedCornerShape(12.dp)
                        )
                    } else {
                        Modifier
                    }
                )
                .clip(RoundedCornerShape(12.dp))
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
            ) {
                if (isSelectionMode) {
                    Icon(
                        imageVector = if (isSelected) {
                            Icons.Filled.CheckCircle
                        } else {
                            Icons.Outlined.RadioButtonUnchecked
                        },
                        contentDescription = if (isSelected) "Selected" else "Not selected",
                        tint = if (isSelected) scheme.primary else scheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(end = 10.dp)
                            .size(24.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .width(136.dp)
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black)
                ) {
                    thumbBitmap?.let { bmp ->
                        Image(
                            bitmap = bmp,
                            contentDescription = item.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    DurationBadge(
                        durationSeconds = item.durationSeconds,
                        isShort = item.isShort,
                        modifier = Modifier.align(Alignment.BottomEnd)
                    )
                    WatchProgressBar(
                        fraction = watchedFraction,
                        modifier = Modifier.align(Alignment.BottomStart)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = scheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = item.channelName,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
                .zIndex(2f)
        ) {
            InlineMenu(
                expanded = isMenuExpanded,
                onDismiss = onDismissMenu
            ) {
                DropdownMenuItem(
                    text = { Text("Select") },
                    onClick = onSelect
                )
                DropdownMenuItem(
                    text = { Text("Add to playlist") },
                    onClick = onAddToPlaylist
                )
                DropdownMenuItem(
                    text = { Text("Delete") },
                    onClick = onDelete
                )
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
fun OfflineVideoPlayer(
    playlist: List<DownloadedVideoEntity>,
    startIndex: Int,
    sponsorBlockRepo: SponsorBlockRepository,
    onMarkVideoWatched: (videoId: String) -> Unit,
    onPlayingStateChanged: (Boolean) -> Unit,
    onClose: () -> Unit,
    playerGesturesEnabled: Boolean = true
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val density = LocalDensity.current

    var currentIndex by remember(playlist, startIndex) {
        mutableIntStateOf(startIndex.coerceIn(0, playlist.lastIndex.coerceAtLeast(0)))
    }
    val currentVideo = playlist.getOrNull(currentIndex) ?: return
    val markedWatchedIds = remember(playlist) { mutableStateListOf<String>() }

    var dragOffsetX by remember { mutableFloatStateOf(0f) }
    var showControls by remember { mutableStateOf(true) }
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubFraction by remember { mutableFloatStateOf(0f) }

    BackHandler {
        onClose()
    }

    DisposableEffect(Unit) {
        onPlayingStateChanged(true)
        onDispose { onPlayingStateChanged(false) }
    }

    val exoPlayer = remember {
        ExoPlayer.Builder(context)
            .setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS)
            .build()
            .apply {
                playWhenReady = true
                // Downloaded videos play once; reaching the end closes instead of looping.
                repeatMode = Player.REPEAT_MODE_OFF
            }
    }

    var isPlaying by remember { mutableStateOf(true) }
    var currentSeconds by remember { mutableLongStateOf(0L) }
    var durationSeconds by remember { mutableLongStateOf(0L) }

    KeepScreenOnEffect(keepScreenOn = isPlaying)

    LaunchedEffect(currentVideo.videoId) {
        val fileUri = runCatching { Uri.parse(currentVideo.localVideoPath) }.getOrNull()
            ?: Uri.fromFile(File(currentVideo.localVideoPath))
        exoPlayer.setMediaItem(MediaItem.fromUri(fileUri))
        exoPlayer.repeatMode = Player.REPEAT_MODE_OFF
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
        PlaybackSessionHolder.attachOfflinePlayer(
            context = context,
            exoPlayer = exoPlayer,
            videoId = currentVideo.videoId,
            title = currentVideo.title,
            channelName = currentVideo.channelName
        )
    }

    // Auto-hide controls after 3.5s of inactivity when playing
    LaunchedEffect(showControls, isPlaying, isScrubbing) {
        if (showControls && isPlaying && !isScrubbing) {
            delay(3500L)
            showControls = false
        }
    }

    // When the current downloaded video ends, mark watched and close back to downloads tab
    DisposableEffect(exoPlayer, playlist) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                PlaybackSessionHolder.updatePlayState(playing)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    // Freeze at the end so a late recomposition cannot restart the file.
                    exoPlayer.playWhenReady = false
                    exoPlayer.pause()
                    if (currentVideo.videoId !in markedWatchedIds) {
                        markedWatchedIds.add(currentVideo.videoId)
                        onMarkVideoWatched(currentVideo.videoId)
                    }
                    onClose()
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            PlaybackSessionHolder.stopAndReleaseAll()
            exoPlayer.release()
        }
    }

    val sponsorBlockEnabled by SponsorBlockPreferences.isEnabled.collectAsState()
    val categoryBehaviors by SponsorBlockPreferences.categoryBehaviors.collectAsState()
    var rawOfflineSegments by remember(currentVideo.videoId) { mutableStateOf<List<SponsorSegment>>(emptyList()) }
    val skippedOfflineUuids = remember(currentVideo.videoId) { mutableStateListOf<String>() }
    var offlineSkipBannerText by remember(currentVideo.videoId) { mutableStateOf<String?>(null) }

    val activeOfflineSegments = remember(rawOfflineSegments, categoryBehaviors, sponsorBlockEnabled) {
        if (!sponsorBlockEnabled) emptyList()
        else rawOfflineSegments.filter { seg ->
            (categoryBehaviors[seg.category] ?: seg.category.defaultBehavior) != SponsorSkipBehavior.NOTHING
        }
    }

    LaunchedEffect(currentVideo.videoId, sponsorBlockEnabled) {
        skippedOfflineUuids.clear()
        offlineSkipBannerText = null
        rawOfflineSegments = if (sponsorBlockEnabled) {
            sponsorBlockRepo.getSegments(currentVideo.videoId)
        } else {
            emptyList()
        }
    }

    // Poll progress, skip sponsor segments, and trigger the watched checkmark
    val latestShowControls by rememberUpdatedState(showControls)
    val latestIsScrubbing by rememberUpdatedState(isScrubbing)
    LaunchedEffect(exoPlayer, currentVideo.videoId, activeOfflineSegments, categoryBehaviors, sponsorBlockEnabled) {
        while (true) {
            delay(if (latestShowControls || latestIsScrubbing) 200L else 500L)
            val posMs = exoPlayer.currentPosition.coerceAtLeast(0L)
            val durMs = exoPlayer.duration.takeIf { it > 0L } ?: (currentVideo.durationSeconds.toLong() * 1000L)
            currentSeconds = posMs / 1000L
            durationSeconds = durMs / 1000L

            if ((latestShowControls || latestIsScrubbing) && !latestIsScrubbing && durMs > 0L) {
                scrubFraction = (posMs.toFloat() / durMs.toFloat()).coerceIn(0f, 1f)
            }

            if (sponsorBlockEnabled && activeOfflineSegments.isNotEmpty()) {
                val currentSec = posMs / 1000f
                val autoHit = activeOfflineSegments.firstOrNull { seg ->
                    val mode = categoryBehaviors[seg.category] ?: seg.category.defaultBehavior
                    mode == SponsorSkipBehavior.AUTO_SKIP &&
                        seg.actionType == "skip" &&
                        seg.uuid !in skippedOfflineUuids &&
                        currentSec >= (seg.startSec - 0.25f) &&
                        currentSec < (seg.endSec - 0.10f)
                }

                if (autoHit != null) {
                    skippedOfflineUuids.add(autoHit.uuid)
                    val totalDurSec = durMs / 1000f
                    val isEndOfVideo = totalDurSec > 0f && autoHit.endSec >= (totalDurSec - 0.75f)
                    val targetMs = if (isEndOfVideo) durMs else (autoHit.endSec * 1000f).toLong()
                    exoPlayer.seekTo(targetMs)
                    offlineSkipBannerText = "Skipped ${autoHit.category.title}"
                }
            }

            if (durMs > 0L &&
                (posMs.toFloat() / durMs.toFloat()) >= 0.90f &&
                currentVideo.videoId !in markedWatchedIds
            ) {
                markedWatchedIds.add(currentVideo.videoId)
                onMarkVideoWatched(currentVideo.videoId)
            }

            PlaybackSessionHolder.updateProgress(
                positionMs = posMs,
                durationMs = durMs,
                hasPrevious = currentIndex > 0,
                hasNext = currentIndex < playlist.lastIndex
            )
        }
    }

    LaunchedEffect(offlineSkipBannerText) {
        if (offlineSkipBannerText != null) {
            delay(2200L)
            offlineSkipBannerText = null
        }
    }

    val dismissThresholdPx = with(density) { 100.dp.toPx() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                translationX = dragOffsetX
                alpha = (1f - (dragOffsetX / (dismissThresholdPx * 3.5f))).coerceIn(0.15f, 1f)
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (dragOffsetX > dismissThresholdPx) {
                            onClose()
                        } else {
                            dragOffsetX = 0f
                        }
                    },
                    onDragCancel = {
                        dragOffsetX = 0f
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        dragOffsetX = (dragOffsetX + dragAmount).coerceAtLeast(0f)
                    }
                )
            }
            .background(Color.Black)
    ) {
        AndroidView(
            factory = { ctx ->
                (LayoutInflater.from(ctx).inflate(R.layout.view_offline_video_player, null) as PlayerView).apply {
                    player = exoPlayer
                    useController = false
                    keepScreenOn = true
                }
            },
            update = { playerView ->
                playerView.keepScreenOn = isPlaying
            },
            modifier = Modifier.fillMaxSize()
        )

        PlayerGestureOverlay(
            gesturesEnabled = playerGesturesEnabled,
            isPlaying = isPlaying,
            isControlsVisible = showControls,
            videoId = currentVideo.videoId,
            localThumbnailPath = currentVideo.localThumbnailPath,
            durationMs = durationSeconds * 1000L,
            currentPositionMs = currentSeconds * 1000L,
            onTogglePlayPause = {
                if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
            },
            onSeekRelative = { deltaMs: Long ->
                val target = (exoPlayer.currentPosition + deltaMs).coerceIn(0L, exoPlayer.duration.coerceAtLeast(0L))
                exoPlayer.seekTo(target)
            },
            onSeekToMs = { targetMs: Long -> exoPlayer.seekTo(targetMs) },
            onSingleTap = {
                showControls = !showControls
            },
            modifier = Modifier.fillMaxSize()
        ) {
            if (showControls) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.45f))
                ) {
                    IconButton(
                        onClick = {
                            if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                        },
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.6f))
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = Color.White,
                            modifier = Modifier.size(36.dp)
                        )
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(bottom = if (isLandscape) 0.dp else 76.dp)
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .align(Alignment.BottomCenter)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = formatDurationSeconds(currentSeconds.toInt()),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White
                            )
                            Text(
                                text = formatDurationSeconds(durationSeconds.toInt()),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.7f)
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        SegmentedDotScrubber(
                            value = scrubFraction,
                            durationSeconds = durationSeconds,
                            sponsorSegments = activeOfflineSegments,
                            onValueChange = { newVal: Float ->
                                isScrubbing = true
                                scrubFraction = newVal
                            },
                            onValueChangeFinished = {
                                isScrubbing = false
                                val targetMs = (scrubFraction * (durationSeconds * 1000L)).toLong()
                                exoPlayer.seekTo(targetMs)
                            }
                        )
                    }
                }
            }

            // Always show title so the user knows what is playing, even when controls hide.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    // Keep a scrim behind the title even while controls are visible so the
                    // white text stays readable over bright video frames in every theme.
                    .background(Color.Black.copy(alpha = 0.35f))
                    .statusBarsPadding()
                    .padding(16.dp)
            ) {
                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.6f))
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Close player",
                        tint = Color.White
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = currentVideo.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = currentVideo.channelName,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
