package io.tehred5pade.frontporch.ui.player

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brightness7
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VolumeMute
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.tehred5pade.frontporch.service.PlaybackSessionHolder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

enum class IndicatorType {
    NONE, BRIGHTNESS, VOLUME, REWIND, FAST_FORWARD, PLAY_PAUSE, SEEK_PREVIEW
}

private fun formatTimeDiff(diffMs: Long): String {
    val totalSeconds = abs(diffMs) / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    val sign = if (diffMs >= 0) "+" else "-"
    return "$sign$minutes:${seconds.toString().padStart(2, '0')}"
}

private fun formatAbsoluteTime(ms: Long): String {
    val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}

@Composable
fun PlayerGestureOverlay(
    gesturesEnabled: Boolean,
    isPlaying: Boolean,
    isControlsVisible: Boolean = false,
    videoId: String? = null,
    localThumbnailPath: String? = null,
    durationMs: Long = 0L,
    currentPositionMs: Long = 0L,
    onTogglePlayPause: () -> Unit,
    onSeekRelative: (deltaMs: Long) -> Unit,
    onSeekToMs: ((Long) -> Unit)? = null,
    onSingleTap: () -> Unit,
    onPullDown: ((dragAmountY: Float) -> Unit)? = null,
    onPullDownEnd: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit = {}
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    var activeIndicator by remember { mutableStateOf(IndicatorType.NONE) }
    var indicatorValue by remember { mutableFloatStateOf(0f) }
    var seekPreviewDiffMs by remember { mutableLongStateOf(0L) }
    var seekPreviewTargetMs by remember { mutableLongStateOf(0L) }
    var dismissJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    fun showIndicator(type: IndicatorType, value: Float = 0f) {
        activeIndicator = type
        indicatorValue = value
        dismissJob?.cancel()
        dismissJob = scope.launch {
            delay(1000L)
            activeIndicator = IndicatorType.NONE
        }
    }

    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    val activity = context as? Activity
    val brightnessAdjusted = remember { mutableStateOf(false) }

    // A swipe only overrides this Activity window. Once the player leaves composition,
    // hand brightness back to the system so other screens are not stuck at the video level.
    DisposableEffect(Unit) {
        onDispose {
            if (brightnessAdjusted.value) {
                val window = (context as? Activity)?.window
                if (window != null) {
                    val lp = window.attributes
                    if (lp.screenBrightness != WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) {
                        lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                        window.attributes = lp
                    }
                }
            }
        }
    }

    val pixelsPerPercent = with(density) { 4.5f.dp.toPx() }
    val pixelsPerSecond = with(density) { 15f.dp.toPx() }

    Box(modifier = modifier.fillMaxSize()) {
        // 1. Underlying content
        content()

        // 2. Gesture overlay layer (only active when controls are hidden)
        if (!isControlsVisible) {
            if (gesturesEnabled) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = { onSingleTap() },
                                onDoubleTap = { offset ->
                                    val width = size.width.toFloat()
                                    if (width > 0f) {
                                        val xFrac = offset.x / width
                                        when {
                                            xFrac < 0.35f -> {
                                                onSeekRelative(-10_000L)
                                                showIndicator(IndicatorType.REWIND)
                                            }
                                            xFrac > 0.65f -> {
                                                onSeekRelative(10_000L)
                                                showIndicator(IndicatorType.FAST_FORWARD)
                                            }
                                            else -> {
                                                onTogglePlayPause()
                                                showIndicator(IndicatorType.PLAY_PAUSE)
                                            }
                                        }
                                    }
                                }
                            )
                        }
                        .pointerInput(pixelsPerPercent) {
                            var isLeftSwipe = false
                            var isRightSwipe = false
                            var isPullDown = false
                            var startPercent = 50f
                            var accumulatedDragY = 0f
                            var maxVol = 15

                            detectVerticalDragGestures(
                                onDragStart = { offset ->
                                    val width = size.width.toFloat()
                                    if (width > 0f) {
                                        val xFrac = offset.x / width
                                        isPullDown = xFrac in 0.35f..0.65f
                                        isLeftSwipe = !isPullDown && xFrac < 0.40f
                                        isRightSwipe = !isPullDown && xFrac > 0.60f
                                        if (isRightSwipe) {
                                            maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                                            val currVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                                            startPercent = (currVol.toFloat() / maxVol.toFloat()) * 100f
                                            accumulatedDragY = 0f
                                        }
                                    }
                                },
                                onDragEnd = {
                                    isLeftSwipe = false
                                    isRightSwipe = false
                                    isPullDown = false
                                    accumulatedDragY = 0f
                                    onPullDownEnd?.invoke()
                                },
                                onDragCancel = {
                                    isLeftSwipe = false
                                    isRightSwipe = false
                                    isPullDown = false
                                    accumulatedDragY = 0f
                                    onPullDownEnd?.invoke()
                                },
                                onVerticalDrag = { change, dragAmount ->
                                    change.consume()
                                    val screenHeight = size.height.toFloat().coerceAtLeast(1f)

                                    if (isPullDown) {
                                        onPullDown?.invoke(dragAmount)
                                    } else if (isLeftSwipe) {
                                        val deltaFrac = -dragAmount / (screenHeight * 0.40f)
                                        val window = activity?.window
                                        if (window != null) {
                                            val lp = window.attributes
                                            val currentB = if (lp.screenBrightness < 0f) {
                                                try {
                                                    android.provider.Settings.System.getInt(
                                                        context.contentResolver,
                                                        android.provider.Settings.System.SCREEN_BRIGHTNESS
                                                    ) / 255f
                                                } catch (e: Exception) { 0.5f }
                                            } else {
                                                lp.screenBrightness
                                            }
                                            val newB = (currentB + deltaFrac).coerceIn(0.01f, 1.0f)
                                            lp.screenBrightness = newB
                                            window.attributes = lp
                                            brightnessAdjusted.value = true
                                            showIndicator(IndicatorType.BRIGHTNESS, newB)
                                        }
                                    } else if (isRightSwipe) {
                                        accumulatedDragY += dragAmount // dragging up is negative
                                        val percentDelta = -accumulatedDragY / pixelsPerPercent
                                        val targetPercent = (startPercent + percentDelta).coerceIn(0f, 100f)
                                        val fraction = targetPercent / 100f

                                        PlaybackSessionHolder.activeExoPlayer?.volume = fraction
                                        PlaybackSessionHolder.activeWebView?.evaluateJavascript(
                                            "var v=document.querySelector('video'); if(v) v.volume = $fraction;",
                                            null
                                        )
                                        val targetVol = (fraction * maxVol).roundToInt().coerceIn(0, maxVol)
                                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0)

                                        showIndicator(IndicatorType.VOLUME, fraction)
                                    }
                                }
                            )
                        }
                        .pointerInput(pixelsPerSecond) {
                            var basePositionMs = 0L
                            var accumulatedDragX = 0f

                            detectHorizontalDragGestures(
                                onDragStart = {
                                    basePositionMs = currentPositionMs
                                    accumulatedDragX = 0f
                                    activeIndicator = IndicatorType.SEEK_PREVIEW
                                },
                                onDragEnd = {
                                    activeIndicator = IndicatorType.NONE
                                    accumulatedDragX = 0f
                                },
                                onDragCancel = {
                                    activeIndicator = IndicatorType.NONE
                                    accumulatedDragX = 0f
                                },
                                onHorizontalDrag = { change, dragAmount ->
                                    change.consume()
                                    accumulatedDragX += dragAmount
                                    val screenWidth = size.width.toFloat().coerceAtLeast(1f)
                                    val dragFraction = (abs(accumulatedDragX) / screenWidth).coerceAtMost(1f)

                                    val speedMultiplier = when {
                                        dragFraction > 0.35f -> 7.0f
                                        dragFraction > 0.15f -> 3.0f
                                        else -> 1.0f
                                    }

                                    val effectiveDragX = accumulatedDragX * speedMultiplier
                                    val deltaSec = (effectiveDragX / pixelsPerSecond).toLong()
                                    val deltaMs = deltaSec * 1000L
                                    val targetMs = (basePositionMs + deltaMs).coerceIn(0L, durationMs.coerceAtLeast(1L))
                                    seekPreviewDiffMs = deltaMs
                                    seekPreviewTargetMs = targetMs
                                    onSeekToMs?.invoke(targetMs)
                                }
                            )
                        }
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures(onTap = { onSingleTap() })
                        }
                )
            }
        }

        AnimatedVisibility(
            visible = activeIndicator != IndicatorType.NONE,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            when (activeIndicator) {
                IndicatorType.BRIGHTNESS -> VerticalValueIndicator(
                    icon = Icons.Default.Brightness7,
                    label = "Brightness",
                    value = indicatorValue
                )
                IndicatorType.VOLUME -> VerticalValueIndicator(
                    icon = if (indicatorValue > 0f) Icons.Default.VolumeUp else Icons.Default.VolumeMute,
                    label = "Volume",
                    value = indicatorValue
                )
                IndicatorType.REWIND -> ActionBadge(
                    icon = Icons.Default.FastRewind,
                    text = "10s"
                )
                IndicatorType.FAST_FORWARD -> ActionBadge(
                    icon = Icons.Default.FastForward,
                    text = "10s"
                )
                IndicatorType.PLAY_PAUSE -> ActionBadge(
                    icon = if (isPlaying) Icons.Default.PlayArrow else Icons.Default.Pause,
                    text = if (isPlaying) "Play" else "Pause"
                )
                IndicatorType.SEEK_PREVIEW -> SeekPreviewIndicator(
                    diffMs = seekPreviewDiffMs,
                    targetMs = seekPreviewTargetMs,
                    durationMs = durationMs
                )
                IndicatorType.NONE -> {}
            }
        }
    }
}

@Composable
private fun SeekPreviewIndicator(
    diffMs: Long,
    targetMs: Long,
    durationMs: Long
) {
    Surface(
        color = Color.Black.copy(alpha = 0.85f),
        contentColor = Color.White,
        shape = RoundedCornerShape(16.dp),
        shadowElevation = 12.dp
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp)
        ) {
            Text(
                text = formatTimeDiff(diffMs),
                style = MaterialTheme.typography.headlineSmall,
                color = if (diffMs >= 0) Color(0xFF4CAF50) else Color(0xFFFF5252)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${formatAbsoluteTime(targetMs)} / ${formatAbsoluteTime(durationMs)}",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.8f)
            )
        }
    }
}

@Composable
private fun VerticalValueIndicator(
    icon: ImageVector,
    label: String,
    value: Float
) {
    Surface(
        color = Color.Black.copy(alpha = 0.8f),
        contentColor = Color.White,
        shape = RoundedCornerShape(16.dp),
        shadowElevation = 8.dp
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = Color.White,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            val percent = (value * 100).roundToInt()
            Text(
                text = "$percent%",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .width(80.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.3f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(value.coerceIn(0f, 1f))
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }
    }
}

@Composable
private fun ActionBadge(
    icon: ImageVector,
    text: String
) {
    Surface(
        color = Color.Black.copy(alpha = 0.8f),
        contentColor = Color.White,
        shape = CircleShape,
        shadowElevation = 8.dp
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White
            )
        }
    }
}
