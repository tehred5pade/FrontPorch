package com.example.frontporch.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.frontporch.data.search.YouTubeSearchRepository
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import okhttp3.OkHttpClient

/**
 * The Search tab: a pinned search field above the live results list.
 *
 * The field stays editable for as long as this tab is the active pager page. Because the pager
 * keeps every page mounted, [isPageActive] also gates focus and back handling so a backgrounded
 * Search tab never steals the keyboard or consumes the root back gesture.
 */
@Composable
fun SearchScreen(
    query: String,
    onQueryChange: (String) -> Unit,
    searchRepo: YouTubeSearchRepository,
    okHttpClient: OkHttpClient,
    subscribedChannelIds: Set<String>,
    hazeState: HazeState,
    glassStyle: HazeStyle,
    isPageActive: Boolean,
    topContentPadding: Dp = 0.dp,
    bottomContentPadding: Dp = 0.dp,
    onPlayVideo: (videoId: String, title: String, channelName: String) -> Unit,
    onPlayShort: (videoId: String) -> Unit,
    onToggleSubscribe: (channelId: String, channelTitle: String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        FrostedTopSearchBar(
            query = query,
            isSearchActive = isPageActive,
            showBackButton = false,
            hazeState = hazeState,
            glassStyle = glassStyle,
            onQueryChange = onQueryChange,
            onActivateSearch = {},
            onCloseSearch = { onQueryChange("") },
            onSubmitSearch = {},
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = topContentPadding)
        )

        YouTubeSearchResultsScreen(
            query = query,
            searchRepo = searchRepo,
            okHttpClient = okHttpClient,
            subscribedChannelIds = subscribedChannelIds,
            hazeState = hazeState,
            glassStyle = glassStyle,
            topContentPadding = 8.dp,
            bottomContentPadding = bottomContentPadding,
            onPlayVideo = onPlayVideo,
            onPlayShort = onPlayShort,
            onToggleSubscribe = onToggleSubscribe,
            onDismiss = { onQueryChange("") },
            handleBack = isPageActive && query.isNotBlank()
        )
    }
}
