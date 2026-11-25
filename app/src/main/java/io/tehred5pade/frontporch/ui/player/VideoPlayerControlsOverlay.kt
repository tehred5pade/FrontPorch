package io.tehred5pade.frontporch.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.tehred5pade.frontporch.data.sponsorblock.SponsorSegment

private fun formatDuration(totalSeconds: Long): String {
    if (totalSeconds <= 0L) return "0:00"
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    } else {
        "$minutes:${seconds.toString().padStart(2, '0')}"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoPlayerControlsOverlay(
    visible: Boolean,
    isPlaying: Boolean,
    currentSeconds: Long,
    durationSeconds: Long,
    hasPreviousVideo: Boolean,
    hasNextVideo: Boolean,
    sponsorSegments: List<SponsorSegment>,
    currentQuality: String = "Auto",
    availableQualities: List<String> = listOf("Auto", "1080p", "720p", "480p", "360p"),
    currentCaption: String = "Off",
    availableCaptions: List<String> = listOf("Off", "On", "Auto"),
    onQualitySelected: (String) -> Unit = {},
    onCaptionSelected: (String) -> Unit = {},
    onPreviousVideo: () -> Unit,
    onReplay10: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onForward10: () -> Unit,
    onNextVideo: () -> Unit,
    onSeekToSeconds: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubFraction by remember { mutableFloatStateOf(0f) }

    // PlayerGestureOverlay already owns tap-to-show/hide controls. Adding another
    // full-screen tap handler here intercepted taps on the CC/quality buttons and
    // immediately dismissed their dropdowns.
    Box(
        modifier = modifier.fillMaxSize()
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier.fillMaxSize()
            ) {
                // Top Right Quality Control Overlay
                var qualityMenuExpanded by remember { mutableStateOf(false) }
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                ) {
                    IconButton(
                        onClick = { qualityMenuExpanded = true },
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.45f))
                    ) {
                        Icon(
                            imageVector = Icons.Filled.HighQuality,
                            contentDescription = "Video Quality",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    DropdownMenu(
                        expanded = qualityMenuExpanded,
                        onDismissRequest = { qualityMenuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Quality: $currentQuality") },
                            onClick = {},
                            enabled = false
                        )
                        HorizontalDivider()
                        availableQualities.forEach { q ->
                            DropdownMenuItem(
                                text = { Text(q) },
                                onClick = {
                                    qualityMenuExpanded = false
                                    onQualitySelected(q)
                                }
                            )
                        }
                    }
                }

                // Bottom Right Closed Caption Overlay (above progress bar)
                var captionMenuExpanded by remember { mutableStateOf(false) }
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 12.dp, bottom = 48.dp)
                ) {
                    IconButton(
                        onClick = { captionMenuExpanded = true },
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.45f))
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ClosedCaption,
                            contentDescription = "Closed Captions",
                            tint = if (currentCaption != "Off") scheme.primary else Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    DropdownMenu(
                        expanded = captionMenuExpanded,
                        onDismissRequest = { captionMenuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Subtitles / CC") },
                            onClick = {},
                            enabled = false
                        )
                        HorizontalDivider()
                        availableCaptions.forEach { cap ->
                            DropdownMenuItem(
                                text = { Text(cap) },
                                onClick = {
                                    captionMenuExpanded = false
                                    onCaptionSelected(cap)
                                }
                            )
                        }
                    }
                }
                // Centered transport controls. Play/Pause is pinned to the exact
                // center; the rest occupy fixed slots so it never shifts when
                // Previous/Next are unavailable.
                val centerButton = 58.dp
                val sideButton = 44.dp
                val gap = 18.dp
                val nearSlot = centerButton / 2 + gap + sideButton / 2
                val farSlot = nearSlot + sideButton + gap

                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth()
                        .height(centerButton),
                    contentAlignment = Alignment.Center
                ) {
                    if (hasPreviousVideo) {
                        IconButton(
                            onClick = onPreviousVideo,
                            modifier = Modifier
                                .size(sideButton)
                                .offset(x = -farSlot)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.45f))
                        ) {
                            Icon(
                                imageVector = Icons.Filled.SkipPrevious,
                                contentDescription = "Previous video",
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }

                    IconButton(
                        onClick = onReplay10,
                        modifier = Modifier
                            .size(sideButton)
                            .offset(x = -nearSlot)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.45f))
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Replay10,
                            contentDescription = "Rewind 10 seconds",
                            tint = Color.White,
                            modifier = Modifier.size(26.dp)
                        )
                    }

                    IconButton(
                        onClick = onTogglePlayPause,
                        modifier = Modifier
                            .size(centerButton)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.45f))
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = Color.White,
                            modifier = Modifier.size(34.dp)
                        )
                    }

                    IconButton(
                        onClick = onForward10,
                        modifier = Modifier
                            .size(sideButton)
                            .offset(x = nearSlot)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.45f))
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Forward10,
                            contentDescription = "Forward 10 seconds",
                            tint = Color.White,
                            modifier = Modifier.size(26.dp)
                        )
                    }

                    if (hasNextVideo) {
                        IconButton(
                            onClick = onNextVideo,
                            modifier = Modifier
                                .size(sideButton)
                                .offset(x = farSlot)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.45f))
                        ) {
                            Icon(
                                imageVector = Icons.Filled.SkipNext,
                                contentDescription = "Next video",
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                }

                // Bottom scrubbable progress track with a small circular thumb.
                val displayedSeconds = if (isScrubbing && durationSeconds > 0L) {
                    (scrubFraction * durationSeconds).toLong()
                } else {
                    currentSeconds
                }
                val sliderValue = if (isScrubbing) {
                    scrubFraction
                } else if (durationSeconds > 0L) {
                    (currentSeconds.toFloat() / durationSeconds.toFloat()).coerceIn(0f, 1f)
                } else {
                    0f
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = formatDuration(displayedSeconds),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall
                    )

                    SegmentedDotScrubber(
                        value = sliderValue,
                        durationSeconds = durationSeconds,
                        sponsorSegments = sponsorSegments,
                        onValueChange = { frac ->
                            isScrubbing = true
                            scrubFraction = frac
                        },
                        onValueChangeFinished = {
                            val targetSec = (scrubFraction * durationSeconds).toLong()
                            isScrubbing = false
                            onSeekToSeconds(targetSec)
                        },
                        accentColor = scheme.primary,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 10.dp)
                    )

                    Text(
                        text = formatDuration(durationSeconds),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}
