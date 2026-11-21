package com.example.frontporch.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.example.frontporch.data.sponsorblock.SponsorSegment

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
    onToggleControls: () -> Unit,
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

    Box(
        modifier = modifier
            .fillMaxSize()
            // Keep the tap handler mounted even while controls are hidden so a
            // single tap on the video always brings the on-screen controls back.
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onToggleControls() })
            }
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
