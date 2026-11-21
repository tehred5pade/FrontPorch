package io.tehred5pade.frontporch.ui.feed

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

enum class VideoMenuAction(val label: String, val icon: ImageVector) {
    BACKGROUND("Play in background (audio stream)", Icons.Outlined.Headphones),
    POPUP("Start in pop-up window", Icons.Outlined.PictureInPictureAlt),
    DOWNLOAD("Download", Icons.Outlined.Download),
    ADD_TO_PLAYLIST("Add to playlist", Icons.AutoMirrored.Outlined.PlaylistAdd),
    SHARE("Share", Icons.Outlined.Share),
    OPEN_IN_BROWSER("Open in browser", Icons.Outlined.OpenInBrowser)
}

/**
 * Single, non-scrolling action menu drawn inside the video card. It stays next to
 * the video and, unlike a platform popup window, participates in the app's back chain.
 */
@Composable
fun VideoOptionsPopup(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onActionSelected: (VideoMenuAction) -> Unit
) {
    if (!expanded) return

    BackHandler { onDismiss() }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
        modifier = Modifier.width(244.dp)
    ) {
        Column {
            VideoMenuAction.entries.forEach { action ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = action.label,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = action.icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    modifier = Modifier.height(44.dp),
                    onClick = {
                        onDismiss()
                        onActionSelected(action)
                    }
                )
            }
        }
    }
}
