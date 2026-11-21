package com.example.frontporch.ui.subscriptions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.example.frontporch.data.db.SubscriptionEntity
import com.example.frontporch.ui.models.VideoItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionsScreen(
    subscriptions: List<SubscriptionEntity> = emptyList(),
    onVideoClick: (String) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var hideWatched by remember { mutableStateOf(false) }

    val sampleVideos = remember {
        mutableStateListOf(
            VideoItem("dQw4w9WgXcQ", "Never Gonna Give You Up", "Rick Astley", "UCuAXFkgsw1L7xaCfnd5JJOw", "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg", "1.2B views", "14 years ago", "3:33", false),
            VideoItem("jNQXAC9IVRw", "Me at the zoo", "jawed", "UC4QobUD8wWqIvTZC9nBw10w", "https://i.ytimg.com/vi/jNQXAC9IVRw/hqdefault.jpg", "300M views", "19 years ago", "0:19", true),
            VideoItem("L_LUpnjgPso", "Exploring Jetpack Compose M3", "Android Developers", "UCVHFbqXmqYvEWu1DqFpnvjQ", "https://i.ytimg.com/vi/L_LUpnjgPso/hqdefault.jpg", "45K views", "2 days ago", "12:45", false)
        )
    }

    val filteredVideos = sampleVideos.filter { video ->
        if (hideWatched && video.isWatched) false
        else video.title.contains(searchQuery, ignoreCase = true) || video.channelTitle.contains(searchQuery, ignoreCase = true)
    }

    val filteredSubscriptions = subscriptions.filter {
        it.name.contains(searchQuery, ignoreCase = true) || it.channelId.contains(searchQuery, ignoreCase = true)
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Search subscriptions...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true
            )

            IconButton(onClick = { hideWatched = !hideWatched }) {
                Icon(
                    Icons.Default.VisibilityOff,
                    contentDescription = "Hide Watched",
                    tint = if (hideWatched) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (subscriptions.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    Icons.Default.Subscriptions,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "${subscriptions.size} Subscribed Channels",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(filteredSubscriptions) { sub ->
                    SuggestionChip(
                        onClick = { searchQuery = sub.name },
                        label = { Text(sub.name) }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(filteredVideos) { video ->
                VideoCard(video = video, onClick = { onVideoClick(video.id) })
            }
        }
    }
}

@Composable
fun VideoCard(video: VideoItem, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column {
            Box(modifier = Modifier.fillMaxWidth().height(200.dp)) {
                AsyncImage(
                    model = video.thumbnailUrl,
                    contentDescription = video.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = video.durationText,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }
            Column(modifier = Modifier.padding(12.dp)) {
                Text(text = video.title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${video.channelTitle} • ${video.viewCountText} • ${video.publishedText}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
