package com.example.frontporch.ui.playlist

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.frontporch.data.playlist.PlaylistEntry
import com.example.frontporch.data.playlist.PlaylistStore
import com.example.frontporch.ui.feed.YouTubeThumbnail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File

/**
 * One dialog serves two modes:
 * - [entry] != null: pick a playlist for that video (also allows creating one).
 * - [entry] == null: browse/play/manage playlists from the Downloads tab.
 */
@Composable
fun PlaylistDialog(
    store: PlaylistStore,
    okHttpClient: OkHttpClient,
    entry: PlaylistEntry?,
    onPlayEntry: (PlaylistEntry) -> Unit,
    onDismiss: () -> Unit
) {
    val playlists by store.playlists.collectAsState()
    var newName by remember { mutableStateOf("") }
    var collapsedIds by remember { mutableStateOf(emptySet<String>()) }
    val scheme = MaterialTheme.colorScheme

    // Tapping a video's title or thumbnail plays it and closes the dialog.
    val play: (PlaylistEntry) -> Unit = { playlistEntry ->
        onPlayEntry(playlistEntry)
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (entry == null) "Playlists" else "Add to playlist",
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 340.dp)
                ) {
                    if (playlists.isEmpty()) {
                        item(key = "empty_playlists") {
                            Text(
                                text = "No playlists yet. Create one below.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = scheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 12.dp)
                            )
                        }
                    }

                    items(playlists, key = { it.id }) { playlist ->
                        val isExpanded = playlist.id !in collapsedIds
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(
                                        if (entry == null) {
                                            Modifier.clickable {
                                                collapsedIds = if (isExpanded) {
                                                    collapsedIds + playlist.id
                                                } else {
                                                    collapsedIds - playlist.id
                                                }
                                            }
                                        } else {
                                            Modifier
                                        }
                                    )
                            ) {
                                if (entry == null) {
                                    Icon(
                                        imageVector = if (isExpanded) {
                                            Icons.Default.KeyboardArrowDown
                                        } else {
                                            Icons.Default.KeyboardArrowRight
                                        },
                                        contentDescription = if (isExpanded) {
                                            "Collapse ${playlist.name}"
                                        } else {
                                            "Expand ${playlist.name}"
                                        },
                                        tint = scheme.onSurfaceVariant
                                    )
                                }

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = playlist.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${playlist.entries.size} video(s)",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = scheme.onSurfaceVariant
                                    )
                                }

                                if (entry != null) {
                                    TextButton(
                                        onClick = {
                                            store.addToPlaylist(playlist.id, entry)
                                            onDismiss()
                                        },
                                        enabled = entry.videoId !in playlist.entries.map { it.videoId }
                                    ) {
                                        Text(
                                            text = if (entry.videoId in playlist.entries.map { it.videoId }) {
                                                "Added"
                                            } else {
                                                "Add"
                                            }
                                        )
                                    }
                                } else {
                                    IconButton(onClick = { store.deletePlaylist(playlist.id) }) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "Delete ${playlist.name}",
                                            tint = scheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }

                            if (entry == null && isExpanded) {
                                playlist.entries.forEach { playlistEntry ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(start = 12.dp, top = 2.dp)
                                    ) {
                                        PlaylistEntryThumbnail(
                                            entry = playlistEntry,
                                            okHttpClient = okHttpClient,
                                            onClick = { play(playlistEntry) },
                                            modifier = Modifier
                                                .width(84.dp)
                                                .aspectRatio(16f / 9f)
                                        )

                                        Spacer(modifier = Modifier.width(10.dp))

                                        Text(
                                            text = playlistEntry.title.ifBlank { playlistEntry.videoId },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = scheme.onSurface,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier
                                                .weight(1f)
                                                .clickable { play(playlistEntry) }
                                        )

                                        IconButton(
                                            onClick = {
                                                store.removeFromPlaylist(playlist.id, playlistEntry.videoId)
                                            },
                                            modifier = Modifier.size(34.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Delete,
                                                contentDescription = "Remove ${playlistEntry.title}",
                                                tint = scheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("New playlist") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        runCatching { store.create(newName, entry) }
                        newName = ""
                        if (entry != null) onDismiss()
                    },
                    enabled = newName.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.size(6.dp))
                    Text("Create playlist")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done")
            }
        }
    )
}

@Composable
private fun PlaylistEntryThumbnail(
    entry: PlaylistEntry,
    okHttpClient: OkHttpClient,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val localPath = entry.localThumbnailPath
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black)
            .clickable(onClick = onClick)
    ) {
        if (localPath != null) {
            val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(
                initialValue = null,
                key1 = localPath
            ) {
                value = withContext(Dispatchers.IO) {
                    val file = File(localPath)
                    if (file.exists()) {
                        BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
                    } else {
                        null
                    }
                }
            }
            bitmap?.let { bmp ->
                Image(
                    bitmap = bmp,
                    contentDescription = "Play ${entry.title}",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        } else {
            YouTubeThumbnail(
                videoId = entry.videoId,
                contentDescription = "Play ${entry.title}",
                okHttpClient = okHttpClient,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
