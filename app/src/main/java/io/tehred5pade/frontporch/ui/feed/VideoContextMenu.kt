package io.tehred5pade.frontporch.ui.feed

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import io.tehred5pade.frontporch.ui.common.frostedGlass

enum class VideoMenuAction(val label: String, val icon: ImageVector) {
    BACKGROUND("Play in background (audio stream)", Icons.Outlined.Headphones),
    POPUP("Start in pop-up window", Icons.Outlined.PictureInPictureAlt),
    DOWNLOAD("Download", Icons.Outlined.Download),
    ADD_TO_PLAYLIST("Add to playlist", Icons.AutoMirrored.Outlined.PlaylistAdd),
    SHARE("Share", Icons.Outlined.Share),
    OPEN_IN_BROWSER("Open in browser", Icons.Outlined.OpenInBrowser)
}

/**
 * Single, non-scrolling action menu. It is hosted in its own (Haze-aware) dialog so
 * its scrim is not captured by the feed's hazeSource, and it uses the exact same
 * frosted-glass recipe as the tab bar.
 */
@Composable
fun VideoOptionsPopup(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onActionSelected: (VideoMenuAction) -> Unit,
    hazeState: HazeState? = null,
    glassStyle: HazeStyle? = null
) {
    if (!expanded) return

    BackHandler { onDismiss() }

    val menuShape = RoundedCornerShape(12.dp)
    Surface(
        shape = menuShape,
        color = Color.Transparent,
        modifier = Modifier
            .width(244.dp)
            .frostedGlass(
                hazeState = hazeState,
                glassStyle = glassStyle,
                shape = menuShape,
                elevation = 10.dp
            )
    ) {
        Column {
            VideoMenuAction.entries.forEach { action ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = action.label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
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
