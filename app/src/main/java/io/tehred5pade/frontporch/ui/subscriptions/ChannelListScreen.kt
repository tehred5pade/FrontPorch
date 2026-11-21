package io.tehred5pade.frontporch.ui.subscriptions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.tehred5pade.frontporch.data.db.SubscriptionEntity
import io.tehred5pade.frontporch.ui.common.frostedGlass
import io.tehred5pade.frontporch.data.search.SearchFilter
import io.tehred5pade.frontporch.data.search.SearchSort
import io.tehred5pade.frontporch.data.search.YouTubeSearchRepository
import io.tehred5pade.frontporch.data.search.YouTubeSearchResult
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

@Composable
fun ChannelListScreen(
    subscriptions: List<SubscriptionEntity>,
    searchRepo: YouTubeSearchRepository,
    onChannelClick: (SubscriptionEntity) -> Unit,
    onToggleSubscribe: (SubscriptionEntity) -> Unit,
    hazeState: HazeState,
    glassStyle: HazeStyle,
    onAvatarResolved: (channelId: String, thumbnailUrl: String) -> Unit = { _, _ -> }
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<YouTubeSearchResult.Channel>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }

    // Imported subscriptions only carry an ID and name, so resolve their avatars on demand.
    // Keep this out of Compose state: writing it would recompose the whole list on scroll.
    val requestedAvatars = remember { mutableSetOf<String>() }
    val avatarFetchLimit = remember { Semaphore(4) }

    LaunchedEffect(subscriptions) {
        subscriptions
            .filter { it.thumbnailUrl.isBlank() && it.channelId !in requestedAvatars }
            .forEach { subscription ->
                requestedAvatars += subscription.channelId
                launch {
                    avatarFetchLimit.withPermit {
                        searchRepo.fetchChannelAvatar(subscription.channelId)?.let { avatarUrl ->
                            onAvatarResolved(subscription.channelId, avatarUrl)
                        }
                    }
                }
            }
    }

    val isYouTubeSearch = searchQuery.isNotBlank()

    // Sort once per subscriptions change instead of on every scroll/recomposition.
    val sortedSubscriptions = remember(subscriptions) {
        subscriptions.sortedBy { it.name.lowercase() }
    }

    // A blank field keeps the local subscription list; a non-blank one searches all of
    // YouTube for channels, ranked by relevance.
    LaunchedEffect(searchQuery) {
        if (searchQuery.isBlank()) {
            searchResults = emptyList()
            isSearching = false
            return@LaunchedEffect
        }
        delay(320L)
        isSearching = true
        val page = searchRepo.searchFirstPage(
            query = searchQuery,
            filter = SearchFilter.CHANNELS,
            sort = SearchSort.RELEVANCE
        )
        searchResults = page.items.filterIsInstance<YouTubeSearchResult.Channel>()
        isSearching = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        // Same frosted-glass recipe as the tab bar (shared haze state, style and alpha).
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .frostedGlass(
                    hazeState = hazeState,
                    glassStyle = glassStyle,
                    shape = RoundedCornerShape(28.dp)
                )
        ) {
            TextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    errorIndicatorColor = Color.Transparent,
                    cursorColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }

        when {
            isYouTubeSearch -> {
                when {
                    isSearching && searchResults.isEmpty() -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }

                    !isSearching && searchResults.isEmpty() -> CenteredMessage(
                        icon = false,
                        title = "No channels found",
                        body = "Try a different search term."
                    )

                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 16.dp)
                    ) {
                        items(searchResults, key = { it.id }) { channel ->
                            val subscribed = subscriptions.any { it.channelId == channel.id }
                            SearchChannelRow(
                                channel = channel,
                                isSubscribed = subscribed,
                                onClick = {
                                    onChannelClick(
                                        SubscriptionEntity(
                                            channelId = channel.id,
                                            name = channel.title,
                                            thumbnailUrl = channel.avatarUrl,
                                            subscriberCountText = channel.subscriberCountText
                                        )
                                    )
                                },
                                onToggleSubscribe = {
                                    onToggleSubscribe(
                                        SubscriptionEntity(
                                            channelId = channel.id,
                                            name = channel.title,
                                            thumbnailUrl = channel.avatarUrl,
                                            subscriberCountText = channel.subscriberCountText
                                        )
                                    )
                                }
                            )
                        }
                    }
                }
            }

            subscriptions.isEmpty() -> CenteredMessage(
                icon = true,
                title = "No subscriptions yet",
                body = "Import your NewPipe subscriptions in Settings to see your channels here."
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(
                    sortedSubscriptions,
                    key = { it.channelId }
                ) { subscription ->
                    ChannelRow(
                        subscription = subscription,
                        onClick = { onChannelClick(subscription) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ChannelRow(
    subscription: SubscriptionEntity,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ChannelAvatar(
            name = subscription.name,
            thumbnailUrl = subscription.thumbnailUrl,
            size = 44.dp
        )

        Spacer(modifier = Modifier.width(12.dp))

        Text(
            text = subscription.name,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun SearchChannelRow(
    channel: YouTubeSearchResult.Channel,
    isSubscribed: Boolean,
    onClick: () -> Unit,
    onToggleSubscribe: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ChannelAvatar(
            name = channel.title,
            thumbnailUrl = channel.avatarUrl,
            size = 44.dp
        )

        Spacer(modifier = Modifier.width(12.dp))

        Text(
            text = channel.title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        Spacer(modifier = Modifier.width(8.dp))

        Button(
            onClick = onToggleSubscribe,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isSubscribed) scheme.surfaceContainerHigh else scheme.primary,
                contentColor = if (isSubscribed) scheme.onSurface else scheme.onPrimary
            ),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
        ) {
            Text(
                text = if (isSubscribed) "Subscribed" else "Subscribe",
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}

@Composable
fun ChannelAvatar(
    name: String,
    thumbnailUrl: String = "",
    size: Dp = 40.dp
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center
    ) {
        if (thumbnailUrl.isNotBlank()) {
            AsyncImage(
                model = thumbnailUrl,
                contentDescription = "Avatar for $name",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
    }
}

@Composable
private fun CenteredMessage(
    icon: Boolean,
    title: String,
    body: String
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (icon) {
                Icon(
                    imageVector = Icons.Default.Subscriptions,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}
