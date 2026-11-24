package io.tehred5pade.frontporch.ui.search

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.tehred5pade.frontporch.data.search.SearchFilter
import io.tehred5pade.frontporch.data.search.SearchSort
import io.tehred5pade.frontporch.data.search.YouTubeSearchRepository
import io.tehred5pade.frontporch.data.search.YouTubeSearchResult
import io.tehred5pade.frontporch.ui.common.InlineMenu
import io.tehred5pade.frontporch.ui.common.frostedGlass
import io.tehred5pade.frontporch.ui.feed.YouTubeThumbnail
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient

private const val SEARCH_CHUNK_SIZE = 5
private const val SEARCH_CHUNK_DELAY_MS = 80L
private const val SEARCH_PREFETCH_THRESHOLD = 8

@Composable
fun FrostedTopSearchBar(
    query: String,
    isSearchActive: Boolean,
    // A dedicated Search tab keeps the field editable but has no back affordance.
    showBackButton: Boolean = true,
    hazeState: HazeState,
    glassStyle: HazeStyle,
    onQueryChange: (String) -> Unit,
    onActivateSearch: () -> Unit,
    onCloseSearch: () -> Unit,
    onSubmitSearch: (String) -> Unit,
    trailingAction: @Composable () -> Unit = {},
    isLoading: Boolean = false,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    val density = LocalDensity.current
    // 52.dp tall field, so a 50% corner radius produces a perfect pill.
    val pillShape = RoundedCornerShape(50)
    val barHeightPx = with(density) { 52.dp.toPx() }

    LaunchedEffect(isSearchActive) {
        if (isSearchActive) {
            runCatching { focusRequester.requestFocus() }
        } else {
            // The pager keeps this composable mounted; release the keyboard when the
            // Search tab is no longer the active page.
            focusManager.clearFocus()
        }
    }

    Box(
        modifier = modifier
            .height(52.dp)
    ) {
        // A navy pill hides directly behind the search bar, slides straight down
        // (never out the sides), and vanishes once it has fully emerged.
        if (isLoading) {
            val infiniteTransition = rememberInfiniteTransition(label = "search_loading")
            val slide by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 1400, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "search_loading_slide"
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        translationY = slide * barHeightPx
                        alpha = when {
                            slide < 0.12f -> slide / 0.12f
                            slide > 0.78f -> (1f - slide) / 0.22f
                            else -> 1f
                        }
                    }
                    .clip(pillShape)
                    .background(Color(0xFF0A1F44))
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .frostedGlass(
                    hazeState = hazeState,
                    glassStyle = glassStyle,
                    shape = pillShape,
                    elevation = 10.dp
                )
                .clickable(enabled = !isSearchActive) { onActivateSearch() }
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxSize()
            ) {
                if (isSearchActive && showBackButton) {
                    IconButton(
                        onClick = {
                            focusManager.clearFocus()
                            onCloseSearch()
                        },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Exit search",
                            tint = scheme.onSurface
                        )
                    }
                } else {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = "Search YouTube",
                        tint = scheme.onSurface,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Box(
                    contentAlignment = Alignment.CenterStart,
                    modifier = Modifier.weight(1f)
                ) {
                    BasicTextField(
                        value = query,
                        onValueChange = {
                            if (!isSearchActive) onActivateSearch()
                            onQueryChange(it)
                        },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = scheme.onSurface),
                        cursorBrush = SolidColor(scheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(
                            onSearch = {
                                focusManager.clearFocus()
                                onSubmitSearch(query)
                            }
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                    )
                }

                if (query.isNotEmpty()) {
                    IconButton(
                        onClick = { onQueryChange("") },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Clear search",
                            tint = scheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                if (!isSearchActive) {
                    trailingAction()
                }
            }
        }
    }
}

@Composable
private fun SearchPillButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailingIcon: @Composable (() -> Unit)? = null
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) scheme.onSurface else scheme.onSurfaceVariant,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            )
            if (trailingIcon != null) {
                Spacer(modifier = Modifier.width(4.dp))
                trailingIcon()
            }
        }
    }
}

@Composable
fun YouTubeSearchResultsScreen(
    query: String,
    searchRepo: YouTubeSearchRepository,
    okHttpClient: OkHttpClient,
    subscribedChannelIds: Set<String>,
    hazeState: HazeState? = null,
    glassStyle: HazeStyle? = null,
    topContentPadding: Dp = 0.dp,
    bottomContentPadding: Dp = 0.dp,
    onPlayVideo: (videoId: String, title: String, channelName: String) -> Unit,
    onPlayShort: (videoId: String) -> Unit,
    onToggleSubscribe: (channelId: String, channelTitle: String) -> Unit,
    onDismiss: () -> Unit,
    // The pager keeps every tab mounted, so only the visible Search page may intercept back.
    handleBack: Boolean = true
) {
    val scheme = MaterialTheme.colorScheme
    val listState = rememberLazyListState()

    var selectedFilter by remember { mutableStateOf(SearchFilter.ALL) }
    var selectedSort by remember { mutableStateOf(SearchSort.RELEVANCE) }
    var sortMenuExpanded by remember { mutableStateOf(false) }

    val allResults = remember { mutableStateListOf<YouTubeSearchResult>() }
    var visibleCount by remember { mutableIntStateOf(0) }
    var continuationToken by remember { mutableStateOf<String?>(null) }
    var isInitialLoading by remember { mutableStateOf(false) }
    var isLoadingMore by remember { mutableStateOf(false) }
    var searchGeneration by remember { mutableIntStateOf(0) }
    // Bumped after every page so the load-more effect re-evaluates even when the
    // next continuation token is unchanged or the user hasn't moved the list.
    var loadMoreTick by remember { mutableIntStateOf(0) }

    // Only this prefix is rendered. Growing it a few rows at a time makes each fetched
    // page appear as a small, smooth chunk instead of popping in all at once.
    val visibleResults by remember {
        derivedStateOf { allResults.take(visibleCount.coerceAtMost(allResults.size)) }
    }

    BackHandler(enabled = handleBack) { onDismiss() }

    // Reload from page one when the query, category filter, or sort order changes.
    LaunchedEffect(query, selectedFilter, selectedSort) {
        val generation = ++searchGeneration
        isLoadingMore = false
        loadMoreTick++

        if (query.isBlank()) {
            allResults.clear()
            continuationToken = null
            isInitialLoading = false
            return@LaunchedEffect
        }

        delay(320L)
        if (generation != searchGeneration) return@LaunchedEffect

        isInitialLoading = true
        continuationToken = null

        val firstPage = searchRepo.searchFirstPage(
            query = query,
            filter = selectedFilter,
            sort = selectedSort
        )
        if (generation != searchGeneration) return@LaunchedEffect

        allResults.clear()
        allResults.addAll(firstPage.items)
        continuationToken = firstPage.continuationToken
        isInitialLoading = false
        listState.scrollToItem(0)
        visibleCount = SEARCH_CHUNK_SIZE.coerceAtMost(allResults.size)
        loadMoreTick++
    }

    // Reveal newly fetched items in small chunks. Restarted whenever a page is appended
    // or the query changes so an old reveal loop can't grow the new result set.
    LaunchedEffect(allResults.size, searchGeneration) {
        val generation = searchGeneration
        while (visibleCount < allResults.size && generation == searchGeneration) {
            delay(SEARCH_CHUNK_DELAY_MS)
            visibleCount = (visibleCount + SEARCH_CHUNK_SIZE).coerceAtMost(allResults.size)
        }
    }

    // Queue the next page once the user is within four items of the bottom, or once the
    // revealed prefix is close to the fetched edge so chunks keep flowing ahead of scroll.
    val shouldLoadMore by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val nearBottom = totalItems > 0 && lastVisible >= totalItems - 4
            val nearFetchedEdge = allResults.isNotEmpty() &&
                visibleCount >= allResults.size - SEARCH_PREFETCH_THRESHOLD
            nearBottom || nearFetchedEdge
        }
    }

    // Owns the request. Re-runs on each tick (after append / initial load) so when the
    // user is already at the bottom we keep fetching the next page until items actually
    // extend the list or the continuation ends. `isLoadingMore` is UI state, not a key:
    // keying on it would restart/cancel this effect when flipped to true.
    LaunchedEffect(shouldLoadMore, continuationToken, isInitialLoading, searchGeneration, loadMoreTick) {
        val token = continuationToken
        if (!shouldLoadMore || token.isNullOrBlank() || isInitialLoading || isLoadingMore) return@LaunchedEffect

        val generation = searchGeneration
        val seenBefore = allResults.size
        isLoadingMore = true
        try {
            val nextPage = searchRepo.searchNextPage(
                continuationToken = token,
                filter = selectedFilter
            )
            if (generation != searchGeneration) return@LaunchedEffect

            val existingKeys = allResults.map { "${it::class.simpleName}_${it.id}" }.toSet()
            val freshItems = nextPage.items.filter { "${it::class.simpleName}_${it.id}" !in existingKeys }
            allResults.addAll(freshItems)
            continuationToken = if (freshItems.isEmpty() && nextPage.continuationToken == token) {
                null
            } else {
                nextPage.continuationToken
            }
            // If nothing visibly extended the list but more pages exist (e.g. a shelf-only
            // continuation or an all-duplicate batch), loop immediately while still near
            // the bottom so results keep flowing without a scroll-back nudge.
            if (continuationToken != null && allResults.size == seenBefore) {
                loadMoreTick++
            }
        } finally {
            isLoadingMore = false
            // Re-evaluate after append/finish so a settled "already at bottom" state can
            // trigger the next page on its own.
            loadMoreTick++
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.background)
            .padding(top = topContentPadding)
    ) {
        // Sort selector plus content-type filters share one frosted pill. The menu is inline
        // (not a platform popup) so a back swipe closes it instead of leaving the screen.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .frostedGlass(
                    hazeState = hazeState,
                    glassStyle = glassStyle,
                    shape = RoundedCornerShape(50),
                    elevation = 10.dp
                )
                .pointerInput(Unit) { detectHorizontalDragGestures { _, _ -> } }
                .padding(horizontal = 6.dp, vertical = 4.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    SearchPillButton(
                        label = selectedSort.label,
                        selected = true,
                        onClick = { sortMenuExpanded = true },
                        trailingIcon = {
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = "Change sort order",
                                tint = scheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    )

                    InlineMenu(
                        expanded = sortMenuExpanded,
                        onDismiss = { sortMenuExpanded = false },
                        hazeState = hazeState,
                        glassStyle = glassStyle,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(top = 44.dp)
                            .zIndex(3f)
                    ) {
                        SearchSort.entries.forEach { sortOption ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = sortOption.label,
                                        color = if (sortOption == selectedSort) {
                                            scheme.primary
                                        } else {
                                            scheme.onSurface
                                        }
                                    )
                                },
                                onClick = {
                                    selectedSort = sortOption
                                    sortMenuExpanded = false
                                },
                                trailingIcon = {
                                    if (sortOption == selectedSort) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = "Selected",
                                            tint = scheme.onSurface
                                        )
                                    }
                                }
                            )
                        }
                    }
                }

                SearchFilter.entries.forEach { filter ->
                    SearchPillButton(
                        label = filter.label,
                        selected = filter == selectedFilter,
                        onClick = { selectedFilter = filter },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        when {
            isInitialLoading && allResults.isEmpty() -> {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 48.dp)
                ) {
                    CircularProgressIndicator(color = scheme.primary)
                }
            }

            query.isNotBlank() && !isInitialLoading && allResults.isEmpty() -> {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 48.dp)
                ) {
                    Text(
                        text = "No results found for \"$query\"",
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant
                    )
                }
            }

            else -> {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(
                        start = 12.dp,
                        end = 12.dp,
                        top = 6.dp,
                        bottom = bottomContentPadding
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (isInitialLoading) {
                        item(key = "search_initial_loading") {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp)
                            )
                        }
                    }
                    items(visibleResults, key = { "${it::class.simpleName}_${it.id}" }) { item ->
                        when (item) {
                            is YouTubeSearchResult.Video -> {
                                SearchVideoRow(
                                    video = item,
                                    okHttpClient = okHttpClient,
                                    onClick = { onPlayVideo(item.id, item.title, item.channelName) }
                                )
                            }
                            is YouTubeSearchResult.Short -> {
                                SearchShortRow(
                                    shortItem = item,
                                    okHttpClient = okHttpClient,
                                    onClick = { onPlayShort(item.id) }
                                )
                            }
                            is YouTubeSearchResult.Channel -> {
                                SearchChannelRow(
                                    channel = item,
                                    isSubscribed = item.id in subscribedChannelIds,
                                    onToggleSubscribe = { onToggleSubscribe(item.id, item.title) },
                                    hazeState = hazeState,
                                    glassStyle = glassStyle
                                )
                            }
                        }
                    }

                    if (isLoadingMore) {
                        item(key = "search_loading_more") {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 16.dp)
                            ) {
                                CircularProgressIndicator(
                                    color = scheme.primary,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchVideoRow(
    video: YouTubeSearchResult.Video,
    okHttpClient: OkHttpClient,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(4.dp)
    ) {
        Box(
            modifier = Modifier
                .width(156.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.Black)
        ) {
            YouTubeThumbnail(
                videoId = video.id,
                contentDescription = video.title,
                okHttpClient = okHttpClient,
                modifier = Modifier.fillMaxSize()
            )
            if (video.durationText.isNotBlank()) {
                Surface(
                    color = Color.Black.copy(alpha = 0.82f),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                ) {
                    Text(
                        text = video.durationText,
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = video.title,
                style = MaterialTheme.typography.titleSmall,
                color = scheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = video.channelName,
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (video.metaText.isNotBlank()) {
                Text(
                    text = video.metaText,
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun SearchShortRow(
    shortItem: YouTubeSearchResult.Short,
    okHttpClient: OkHttpClient,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(scheme.surfaceVariant.copy(alpha = 0.55f))
            .clickable(onClick = onClick)
            .padding(8.dp)
    ) {
        Box(
            modifier = Modifier
                .width(64.dp)
                .height(96.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black)
        ) {
            YouTubeThumbnail(
                videoId = shortItem.id,
                contentDescription = shortItem.title,
                okHttpClient = okHttpClient,
                modifier = Modifier.fillMaxSize()
            )
            Surface(
                color = scheme.primary,
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
            ) {
                Text(
                    text = "SHORTS",
                    color = scheme.onPrimary,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = shortItem.title,
                style = MaterialTheme.typography.titleSmall,
                color = scheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (shortItem.viewsText.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = shortItem.viewsText,
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SearchChannelRow(
    channel: YouTubeSearchResult.Channel,
    isSubscribed: Boolean,
    onToggleSubscribe: () -> Unit,
    hazeState: HazeState?,
    glassStyle: HazeStyle?
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(scheme.surfaceVariant.copy(alpha = 0.55f))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(scheme.primaryContainer)
        ) {
            Text(
                text = channel.title.take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                color = scheme.onPrimaryContainer
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = channel.title,
                style = MaterialTheme.typography.titleSmall,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val subLine = listOf(channel.handle, channel.subscriberCountText)
                .filter { it.isNotBlank() }
                .joinToString(" • ")
            if (subLine.isNotBlank()) {
                Text(
                    text = subLine,
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        val subscribeShape = RoundedCornerShape(50)
        Box(
            modifier = Modifier
                .frostedGlass(
                    hazeState = hazeState,
                    glassStyle = glassStyle,
                    shape = subscribeShape,
                    elevation = 10.dp
                )
                .clip(subscribeShape)
                .clickable(onClick = onToggleSubscribe)
                .padding(horizontal = 14.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (isSubscribed) "Subscribed" else "Subscribe",
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurface
            )
        }
    }
}
