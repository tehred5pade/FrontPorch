package io.tehred5pade.frontporch.ui.player

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import io.tehred5pade.frontporch.data.local.WatchProgressRepository
import io.tehred5pade.frontporch.service.PlaybackSessionHolder
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.hazeEffect
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun DraggableVideoPlayerOverlay(
    videoId: String,
    title: String,
    channelName: String,
    okHttpClient: OkHttpClient,
    visitorData: String?,
    watchProgressRepository: WatchProgressRepository,
    bottomBarHeightPx: Int,
    hazeState: HazeState,
    glassStyle: HazeStyle,
    hasPreviousVideo: Boolean = false,
    hasNextVideo: Boolean = false,
    onPreviousVideo: () -> Unit = {},
    onNextVideo: () -> Unit = {},
    onClosePlayer: () -> Unit,
    onPlaybackEnded: () -> Unit = {},
    onMinimizedChange: (Boolean) -> Unit,
    onCollapseProgressChange: (Float) -> Unit = {},
    preferredAudioLanguage: String = "en",
    startAtMs: Long = 0L,
    localVideoPath: String? = null,
    fallbackTitle: String? = null,
    fallbackChannel: String? = null,
    playerGesturesEnabled: Boolean = true,
    commentsContent: @Composable ColumnScope.() -> Unit
) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    // 0f = Fullscreen Expanded, 1f = Docked Mini-Player
    val collapseProgress = remember { Animatable(0f) }
    val dismissOffsetX = remember { Animatable(0f) }

    LaunchedEffect(videoId) {
        dismissOffsetX.snapTo(0f)
        collapseProgress.animateTo(
            targetValue = 0f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMediumLow
            )
        )
    }

    LaunchedEffect(isLandscape) {
        if (isLandscape && collapseProgress.value > 0f) {
            collapseProgress.snapTo(0f)
        }
    }

    val isMinimized by remember {
        derivedStateOf { collapseProgress.value > 0.92f }
    }

    LaunchedEffect(isMinimized) {
        onMinimizedChange(isMinimized)
    }

    // Publish the raw drag progress so the bottom tab bar can cross-fade with
    // the comments/scrim instead of popping in only when fully minimized.
    val reportCollapseProgress = rememberUpdatedState(onCollapseProgressChange)
    LaunchedEffect(Unit) {
        snapshotFlow { collapseProgress.value }
            .collect { value -> reportCollapseProgress.value(value) }
    }

    // Only hide full system bars in landscape so portrait NavigationBar never shifts or breaks
    DisposableEffect(isLandscape) {
        val window = (context as? android.app.Activity)?.window
        if (window != null) {
            val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            if (isLandscape) {
                controller.systemBarsBehavior =
                    androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            } else {
                controller.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose { }
    }

    BackHandler {
        if (localVideoPath != null || isMinimized) {
            // Back from offline video or mini player leaves the video section entirely.
            PlaybackSessionHolder.stopAndReleaseAll()
            onClosePlayer()
        } else {
            // Back from the expanded player docks it so the main tabs are usable again.
            scope.launch {
                collapseProgress.animateTo(
                    1f,
                    spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
                )
            }
        }
    }

    val nowPlaying by PlaybackSessionHolder.nowPlaying.collectAsState()
    val isPlaying = nowPlaying?.isPlaying ?: true

    // Pad the overlay's coordinate space by the status bar so the expanded player
    // sits below it. A small extra portrait gap keeps the video from touching the
    // status bar; the docked mini player still lands in the same absolute spot
    // because dockedBarTop is measured from this padded height.
    BoxWithConstraints(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        val screenWidth = maxWidth
        val screenHeight = maxHeight
        val portraitTopGap = if (isLandscape) 0.dp else 10.dp

        val expandedVideoWidth = screenWidth
        val expandedVideoHeight = screenWidth * (9f / 16f)

        val miniBarHeight = 64.dp
        val miniVideoHeight = 52.dp
        val miniVideoWidth = miniVideoHeight * (16f / 9f)
        val miniVerticalPadding = (miniBarHeight - miniVideoHeight) / 2f

        val p = if (isLandscape) 0f else collapseProgress.value.coerceIn(0f, 1f)

        val currentVideoWidth = if (isLandscape) screenWidth else lerp(expandedVideoWidth, miniVideoWidth, p)
        val currentVideoHeight = if (isLandscape) screenHeight else lerp(expandedVideoHeight, miniVideoHeight, p)
        // The player morphs from a sharp fullscreen surface into a dock that caps
        // the bottom tab bar: only the top corners round off as it minimizes.
        val topCornerRadius = if (isLandscape) 0.dp else lerp(0.dp, 20.dp, p)
        val morphedDockShape = RoundedCornerShape(
            topStart = topCornerRadius,
            topEnd = topCornerRadius,
            bottomStart = 0.dp,
            bottomEnd = 0.dp
        )

        // Exact bottom bar height measured from Scaffold's NavigationBar
        val measuredNavHeightDp = with(density) {
            if (bottomBarHeightPx > 0) bottomBarHeightPx.toDp() else 80.dp
        }
        val dockedBarTop = (screenHeight - measuredNavHeightDp - miniBarHeight).coerceAtLeast(0.dp)
        val currentBarTop = if (isLandscape) 0.dp else lerp(portraitTopGap, dockedBarTop, p)
        val travelPx = with(density) {
            (dockedBarTop - portraitTopGap).coerceAtLeast(1.dp).toPx()
        }

        val verticalDragState = rememberDraggableState { deltaPx ->
            if (!isLandscape) {
                val next = (collapseProgress.value + deltaPx / travelPx).coerceIn(0f, 1f)
                scope.launch { collapseProgress.snapTo(next) }
            }
        }

        val horizontalDismissState = rememberDraggableState { deltaPx ->
            if (isMinimized) {
                scope.launch { dismissOffsetX.snapTo(dismissOffsetX.value + deltaPx) }
            }
        }

        // 1. Background scrim - clears on the same curve as the comments so the
        //    bottom tab bar underneath is revealed gradually during the drag.
        //    Fully unmounted once docked so it never intercepts feed/tab gestures.
        if (!isLandscape && !isMinimized && p < 0.4f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = (1f - p * 2.5f).coerceIn(0f, 1f) }
                    .background(MaterialTheme.colorScheme.background)
            )
        }

        // 2. Comments layer - unmounted once docked so vertical feed scrolls and
        //    horizontal tab swipes pass straight through above the mini-player.
        if (!isLandscape && !isMinimized) {
            val commentsAlpha = (1f - p * 2.5f).coerceIn(0f, 1f)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = expandedVideoHeight + portraitTopGap)
                    .graphicsLayer {
                        alpha = commentsAlpha
                        translationY = p * 180f
                    }
            ) {
                commentsContent()
            }
        }

        // 3. Sliding Player Bar (docks cleanly above the bottom NavigationBar, morphing into tab bar)
        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        x = dismissOffsetX.value.roundToInt(),
                        y = with(density) { currentBarTop.toPx().roundToInt() }
                    )
                }
                .fillMaxWidth()
                .height(if (isLandscape) screenHeight else lerp(expandedVideoHeight, miniBarHeight, p))
                .shadow(
                    // Fade the border/shadow out at the dock so it merges seamlessly
                    // into the tab bar's glass panel.
                    elevation = lerp(0.dp, 10.dp, p) * (1f - p),
                    shape = morphedDockShape,
                    ambientColor = Color.Black.copy(alpha = 0.45f),
                    spotColor = Color.Black.copy(alpha = 0.45f)
                )
                .clip(morphedDockShape)
                .then(
                    if (p > 0.05f && !isLandscape) {
                        Modifier
                            .hazeEffect(state = hazeState, style = glassStyle)
                            .background(scheme.surfaceContainer.copy(alpha = 0.62f))
                    } else {
                        Modifier.background(Color.Black)
                    }
                )
                .draggable(
                    orientation = Orientation.Vertical,
                    enabled = !isLandscape,
                    state = verticalDragState,
                    onDragStopped = { velocityPx ->
                        val shouldMinimize = when {
                            velocityPx > 900f -> true
                            velocityPx < -900f -> false
                            else -> collapseProgress.value > 0.35f
                        }
                        scope.launch {
                            collapseProgress.animateTo(
                                targetValue = if (shouldMinimize) 1f else 0f,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMediumLow
                                )
                            )
                        }
                    }
                )
                .draggable(
                    orientation = Orientation.Horizontal,
                    enabled = isMinimized,
                    state = horizontalDismissState,
                    onDragStopped = { velocityPx ->
                        val dismissThreshold = with(density) { (screenWidth * 0.35f).toPx() }
                        if (abs(dismissOffsetX.value) > dismissThreshold || abs(velocityPx) > 1200f) {
                            PlaybackSessionHolder.stopAndReleaseAll()
                            onClosePlayer()
                        } else {
                            scope.launch { dismissOffsetX.animateTo(0f, spring()) }
                        }
                    }
                )
        ) {
            // Mini-player metadata & controls (Musicast layout)
            if (p > 0.35f && !isLandscape) {
                val contentAlpha = ((p - 0.35f) / 0.65f).coerceIn(0f, 1f)

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable {
                            scope.launch {
                                collapseProgress.animateTo(
                                    0f,
                                    spring(
                                        dampingRatio = Spring.DampingRatioNoBouncy,
                                        stiffness = Spring.StiffnessMediumLow
                                    )
                                )
                            }
                        }
                        .padding(start = miniVideoWidth + 18.dp, end = 6.dp)
                        .graphicsLayer { alpha = contentAlpha }
                ) {
                    Column(
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = title,
                            color = scheme.onSurface,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = channelName,
                            color = scheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    IconButton(onClick = { PlaybackSessionHolder.togglePlayPause() }) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = scheme.onSurface
                        )
                    }

                    IconButton(
                        onClick = {
                            PlaybackSessionHolder.stopAndReleaseAll()
                            onClosePlayer()
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Close player",
                            tint = scheme.onSurfaceVariant
                        )
                    }
                }

                // Animated wave tucked directly under the video thumbnail;
                // it flattens to a straight line when paused.
                val progressMap by watchProgressRepository.progressFlow.collectAsState(initial = emptyMap())
                val currentProgress = progressMap[videoId] ?: 0f
                if (currentProgress > 0f) {
                    AnimatedWavyProgressBar(
                        progress = currentProgress,
                        isPlaying = isPlaying,
                        activeColor = scheme.primary,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = lerp(0.dp, 12.dp, p))
                            .width(currentVideoWidth)
                            .graphicsLayer { alpha = contentAlpha }
                    )
                }

            }

            // Resize the real layout bounds (not a graphicsLayer scale) so the
            // SurfaceView / WebView surface renders correctly at every dock
            // position. VideoPlayerScreen stays mounted, so playback continues.
            Box(
                modifier = Modifier
                    .padding(
                        start = lerp(0.dp, 12.dp, p),
                        top = lerp(0.dp, miniVerticalPadding, p)
                    )
                    .width(currentVideoWidth)
                    .height(currentVideoHeight)
                    .clip(RoundedCornerShape(lerp(0.dp, 10.dp, p)))
                    .background(Color.Black)
                    .then(
                        if (isMinimized) {
                            Modifier.clickable {
                                scope.launch {
                                    collapseProgress.animateTo(
                                        0f,
                                        spring(
                                            dampingRatio = Spring.DampingRatioNoBouncy,
                                            stiffness = Spring.StiffnessMediumLow
                                        )
                                    )
                                }
                            }
                        } else {
                            Modifier
                        }
                    )
            ) {
                VideoPlayerScreen(
                    videoId = videoId,
                    okHttpClient = okHttpClient,
                    visitorData = visitorData,
                    watchProgressRepository = watchProgressRepository,
                    preferredAudioLanguage = preferredAudioLanguage,
                    startAtMs = startAtMs,
                    localVideoPath = localVideoPath,
                    fallbackTitle = fallbackTitle,
                    fallbackChannel = fallbackChannel,
                    embeddedMode = true,
                    showExpandedControls = (p < 0.25f),
                    hasPreviousVideo = hasPreviousVideo,
                    hasNextVideo = hasNextVideo,
                    onPreviousVideo = onPreviousVideo,
                    onNextVideo = onNextVideo,
                    onPlaybackEnded = onPlaybackEnded,
                    playerGesturesEnabled = playerGesturesEnabled,
                    onPullDown = { dragAmountY ->
                        if (!isLandscape) {
                            val next = (collapseProgress.value + dragAmountY / travelPx).coerceIn(0f, 1f)
                            scope.launch { collapseProgress.snapTo(next) }
                        }
                    },
                    onPullDownEnd = {
                        if (!isLandscape) {
                            val shouldMinimize = collapseProgress.value > 0.35f
                            scope.launch {
                                collapseProgress.animateTo(
                                    targetValue = if (shouldMinimize) 1f else 0f,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioNoBouncy,
                                        stiffness = Spring.StiffnessMediumLow
                                    )
                                )
                            }
                        }
                    },
                    onBack = {
                        scope.launch {
                            collapseProgress.animateTo(
                                1f,
                                spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMediumLow
                                )
                            )
                        }
                    }
                )

            }
        }
    }
}
