package io.tehred5pade.frontporch.ui.player

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.tehred5pade.frontporch.data.video.VideoDetailsAndComments
import io.tehred5pade.frontporch.data.video.VideoMetadataRepository

@Composable
fun VideoDetailsAndCommentsSection(
    videoId: String,
    fallbackTitle: String,
    fallbackChannel: String,
    metadataRepo: VideoMetadataRepository
) {
    val context = LocalContext.current
    var details by remember(videoId) {
        mutableStateOf(
            VideoDetailsAndComments(
                title = fallbackTitle,
                channelName = fallbackChannel,
                description = "",
                comments = emptyList()
            )
        )
    }
    var isLoadingComments by remember(videoId) { mutableStateOf(true) }
    var descriptionExpanded by remember(videoId) { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme

    LaunchedEffect(videoId) {
        isLoadingComments = true
        details = metadataRepo.loadDetailsAndComments(videoId, fallbackTitle, fallbackChannel)
        isLoadingComments = false
    }

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        // Title & Channel Name
        item(key = "header") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = details.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = scheme.onSurface
                )
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = details.channelName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = {
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, "https://www.youtube.com/watch?v=$videoId")
                        }
                        val chooser = Intent.createChooser(shareIntent, "Share Video").apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        runCatching { context.startActivity(chooser) }
                    }) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Share Video",
                            tint = scheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Collapsible Description Card
        if (details.description.isNotBlank()) {
            item(key = "description") {
                Surface(
                    color = scheme.surfaceVariant,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { descriptionExpanded = !descriptionExpanded }
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = if (descriptionExpanded) "Description (tap to collapse)" else "Description",
                            style = MaterialTheme.typography.labelMedium,
                            color = scheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = details.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurface,
                            maxLines = if (descriptionExpanded) Int.MAX_VALUE else 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        // Comments Header
        item(key = "comments_header") {
            HorizontalDivider(color = scheme.outlineVariant)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (isLoadingComments) "Comments..." else "Comments (${details.comments.size})",
                style = MaterialTheme.typography.titleSmall,
                color = scheme.onSurface
            )
        }

        // Comments List
        items(details.comments, key = { it.id }) { comment ->
            Column(
                verticalArrangement = Arrangement.spacedBy(3.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = comment.author,
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant
                )
                Text(
                    text = comment.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurface
                )
                val metaLine = listOfNotNull(
                    comment.likeCount.takeIf { it.isNotBlank() }?.let { "$it likes" },
                    comment.publishedTime.takeIf { it.isNotBlank() }
                ).joinToString(" • ")
                if (metaLine.isNotBlank()) {
                    Text(
                        text = metaLine,
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
