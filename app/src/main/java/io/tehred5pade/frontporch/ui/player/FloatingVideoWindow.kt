package io.tehred5pade.frontporch.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.tehred5pade.frontporch.data.local.WatchProgressRepository
import okhttp3.OkHttpClient
import kotlin.math.roundToInt

private val FloatingWidth = 244.dp
private val FloatingHeight = FloatingWidth * (9f / 16f)

/**
 * Compact draggable video surface. Tapping it promotes the same video to the normal
 * player (with comments in portrait, fullscreen in landscape).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FloatingVideoWindow(
    videoId: String,
    title: String,
    channelName: String,
    localVideoPath: String?,
    okHttpClient: OkHttpClient,
    visitorData: String?,
    watchProgressRepository: WatchProgressRepository,
    preferredAudioLanguage: String = "en",
    hasPreviousVideo: Boolean = false,
    hasNextVideo: Boolean = false,
    onPreviousVideo: () -> Unit = {},
    onNextVideo: () -> Unit = {},
    onPlaybackEnded: () -> Unit = {},
    onOpenFullPlayer: () -> Unit,
    onClose: () -> Unit
) {
    val density = LocalDensity.current

    BackHandler { onClose() }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val widthPx = with(density) { FloatingWidth.toPx() }
        val heightPx = with(density) { FloatingHeight.toPx() }
        val topInsetPx = with(density) {
            WindowInsets.statusBars.asPaddingValues().calculateTopPadding().toPx()
        }
        val maxX = (constraints.maxWidth - widthPx).coerceAtLeast(0f)
        val maxY = (constraints.maxHeight - heightPx).coerceAtLeast(topInsetPx)

        val offsetX = remember { mutableFloatStateOf(maxX) }
        val offsetY = remember { mutableFloatStateOf(topInsetPx) }

        LaunchedEffect(videoId) {
            offsetX.floatValue = maxX
            offsetY.floatValue = topInsetPx
        }
        LaunchedEffect(maxX, maxY) {
            offsetX.floatValue = offsetX.floatValue.coerceIn(0f, maxX)
            offsetY.floatValue = offsetY.floatValue.coerceIn(topInsetPx, maxY)
        }

        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        x = offsetX.floatValue.roundToInt(),
                        y = offsetY.floatValue.roundToInt()
                    )
                }
                .size(FloatingWidth, FloatingHeight)
                .shadow(12.dp, RoundedCornerShape(14.dp))
                .clip(RoundedCornerShape(14.dp))
                .pointerInput(Unit) {
                    detectDragGestures { _, dragAmount ->
                        offsetX.floatValue = (offsetX.floatValue + dragAmount.x).coerceIn(0f, maxX)
                        offsetY.floatValue = (offsetY.floatValue + dragAmount.y)
                            .coerceIn(topInsetPx, maxY)
                    }
                }
        ) {
            Surface(
                color = Color.Black,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                VideoPlayerScreen(
                    videoId = videoId,
                    okHttpClient = okHttpClient,
                    visitorData = visitorData,
                    watchProgressRepository = watchProgressRepository,
                    preferredAudioLanguage = preferredAudioLanguage,
                    localVideoPath = localVideoPath,
                    fallbackTitle = title,
                    fallbackChannel = channelName,
                    embeddedMode = true,
                    showExpandedControls = false,
                    hasPreviousVideo = hasPreviousVideo,
                    hasNextVideo = hasNextVideo,
                    onPreviousVideo = onPreviousVideo,
                    onNextVideo = onNextVideo,
                    onPlaybackEnded = onPlaybackEnded,
                    onBack = onOpenFullPlayer
                )
            }

            // The WebView consumes its own touches, so a transparent layer above it
            // owns the tap-to-expand gesture. The close button below stays on top.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .combinedClickable(onClick = onOpenFullPlayer, onLongClick = {})
            )

            // Small label makes the tap-to-expand behavior discoverable without
            // covering the video itself.
            Text(
                text = title,
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 8.dp, end = 40.dp, bottom = 5.dp)
            )

            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(2.dp)
                    .size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close pop-up player",
                    tint = Color.White
                )
            }
        }
    }
}
