package io.tehred5pade.frontporch.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.tehred5pade.frontporch.ui.models.CommentItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchScreen(
    videoId: String,
    onBack: () -> Unit
) {
    val comments = remember {
        listOf(
            CommentItem("Alice", "This video is incredible! Thanks for sharing.", "245", "1 day ago"),
            CommentItem("Bob", "Very informative walkthrough.", "52", "3 hours ago")
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Top Player Box
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp)
                .background(Color.Black)
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.padding(8.dp)
            ) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Text(
                text = "ExoPlayer View ($videoId)",
                color = Color.White,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // Details & Comments
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text(text = "Playing Video Title $videoId", style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = "Creator Channel • 10M views • 1 month ago", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            item {
                Divider()
                Text(text = "Comments (${comments.size})", style = MaterialTheme.typography.titleMedium)
            }

            items(comments) { comment ->
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(text = comment.author, style = MaterialTheme.typography.labelMedium)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(text = comment.text, style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = "${comment.likes} likes • ${comment.timestamp}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
