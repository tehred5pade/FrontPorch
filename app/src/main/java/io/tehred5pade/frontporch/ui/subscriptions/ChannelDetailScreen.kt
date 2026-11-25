package io.tehred5pade.frontporch.ui.subscriptions

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.tehred5pade.frontporch.FrontPorchApp
import io.tehred5pade.frontporch.data.db.SubscriptionEntity
import io.tehred5pade.frontporch.data.innertube.FeedVideoItem
import io.tehred5pade.frontporch.data.innertube.formatRelativeTime
import io.tehred5pade.frontporch.data.local.WatchProgressRepository
import io.tehred5pade.frontporch.ui.feed.DurationBadge
import io.tehred5pade.frontporch.ui.feed.WatchProgressBar
import io.tehred5pade.frontporch.ui.feed.YouTubeThumbnail
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import okhttp3.OkHttpClient

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelDetailScreen(
    channel: SubscriptionEntity,
    viewModel: ChannelDetailViewModel,
    watchProgressRepository: WatchProgressRepository,
    onBack: () -> Unit,
    onVideoClick: (String) -> Unit,
    onShortClick: (videoId: String, title: String, channelId: String, channelTitle: String) -> Unit = { _, _, _, _ -> },
    isSubscribed: Boolean = false,
    onToggleSubscribe: () -> Unit = {}
) {
    val videos by viewModel.videos.collectAsState()
    val shorts by viewModel.shorts.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isLoadingMore by viewModel.isLoadingMore.collectAsState()
    val videosEndReached by viewModel.videosEndReached.collectAsState()
    val shortsEndReached by viewModel.shortsEndReached.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()

    // Watched fraction per videoId, used for the progress bar on each video thumbnail.
    val progressById by watchProgressRepository.progressFlow.collectAsState(initial = emptyMap())

    val okHttpClient = (LocalContext.current.applicationContext as FrontPorchApp).okHttpClient

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val videosListState = rememberLazyListState()
    val shortsListState = rememberLazyGridState()

    val currentList = if (selectedTab == 0) videos else shorts

    // Page through the channel's uploads playlist as the user approaches the end. The
    // effect restarts whenever a page arrives (or loading state changes), so it keeps
    // filling the viewport until the list is no longer near the end.
    LaunchedEffect(
        selectedTab,
        videosListState,
        videosEndReached,
        isLoadingMore,
        videos.size
    ) {
        if (selectedTab != 0 || videosEndReached || videos.isEmpty()) return@LaunchedEffect
        snapshotFlow {
            val info = videosListState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount > 0 && lastVisible >= info.totalItemsCount - 4
        }
            .distinctUntilChanged()
            .filter { it }
            .collect { viewModel.loadMoreVideos() }
    }

    LaunchedEffect(
        selectedTab,
        shortsListState,
        shortsEndReached,
        isLoadingMore,
        shorts.size
    ) {
        if (selectedTab != 1 || shortsEndReached || shorts.isEmpty()) return@LaunchedEffect
        snapshotFlow {
            val info = shortsListState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount > 0 && lastVisible >= info.totalItemsCount - 6
        }
            .distinctUntilChanged()
            .filter { it }
            .collect { viewModel.loadMoreShorts() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = channel.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Videos and Shorts",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    Button(onClick = onToggleSubscribe) {
                        Text(if (isSubscribed) "Subscribed" else "Subscribe")
                    }
                    Spacer(Modifier.width(8.dp))
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            PrimaryTabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = {
                        Text(
                            if (videos.isNotEmpty()) "Videos (${videos.size})" else "Videos"
                        )
                    }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = {
                        Text(
                            if (shorts.isNotEmpty()) "Shorts (${shorts.size})" else "Shorts"
                        )
                    }
                )
            }

            when {
                isLoading && currentList.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }

                currentList.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = if (selectedTab == 0) {
                                    errorMessage ?: "No videos posted by ${channel.name}."
                                } else {
                                    errorMessage ?: "No Shorts posted by ${channel.name}."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(onClick = { viewModel.load() }) {
                                Text("Retry")
                            }
                        }
                    }
                }

                selectedTab == 0 -> {
                    LazyColumn(
                        state = videosListState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(videos, key = { it.videoId }) { video ->
                            ChannelVideoRow(
                                video = video,
                                okHttpClient = okHttpClient,
                                watchedFraction = progressById[video.videoId] ?: 0f,
                                onClick = {
                                    if (video.isShort) {
                                        onShortClick(video.videoId, video.title, channel.channelId, channel.name)
                                    } else {
                                        onVideoClick(video.videoId)
                                    }
                                }
                            )
                        }

                        if (isLoadingMore && !videosEndReached) {
                            item(key = "videos-loading-more") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(26.dp),
                                        strokeWidth = 2.dp
                                    )
                                }
                            }
                        }
                    }
                }

                else -> {
                    LazyVerticalGrid(
                        state = shortsListState,
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(shorts, key = { it.videoId }) { short ->
                            ChannelShortCard(
                                short = short,
                                okHttpClient = okHttpClient,
                                onClick = {
                                    onShortClick(short.videoId, short.title, channel.channelId, channel.name)
                                }
                            )
                        }

                        if (isLoadingMore && !shortsEndReached) {
                            item(
                                key = "shorts-loading-more",
                                span = { GridItemSpan(maxLineSpan) }
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(26.dp),
                                        strokeWidth = 2.dp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelVideoRow(
    video: FeedVideoItem,
    okHttpClient: OkHttpClient,
    watchedFraction: Float,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(4.dp)
    ) {
        Box(
            modifier = Modifier
                .width(148.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(8.dp))
        ) {
            YouTubeThumbnail(
                videoId = video.videoId,
                contentDescription = video.title,
                okHttpClient = okHttpClient,
                modifier = Modifier.fillMaxSize()
            )

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

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = video.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            val timeLabel = formatRelativeTime(video.publishedEpochMs)
            Text(
                text = if (timeLabel.isNotEmpty()) "$timeLabel • Video" else "Video",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ChannelShortCard(
    short: FeedVideoItem,
    okHttpClient: OkHttpClient,
    onClick: () -> Unit
) {
    Column(modifier = Modifier.clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(9f / 16f)
                .clip(RoundedCornerShape(8.dp))
        ) {
            YouTubeThumbnail(
                videoId = short.videoId,
                contentDescription = short.title,
                okHttpClient = okHttpClient,
                modifier = Modifier.fillMaxSize()
            )

            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(horizontal = 2.dp, vertical = 1.dp)
                        .size(18.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = short.title,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        val timeLabel = formatRelativeTime(short.publishedEpochMs)
        if (timeLabel.isNotEmpty()) {
            Text(
                text = timeLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
