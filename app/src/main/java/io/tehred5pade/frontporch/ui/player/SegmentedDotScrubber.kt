package io.tehred5pade.frontporch.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.tehred5pade.frontporch.data.sponsorblock.SponsorSegment

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SegmentedDotScrubber(
    value: Float,
    durationSeconds: Long,
    sponsorSegments: List<SponsorSegment>,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
    accentColor: Color = MaterialTheme.colorScheme.primary
) {
    Slider(
        value = value.coerceIn(0f, 1f),
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        thumb = {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(20.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(accentColor)
                )
            }
        },
        track = { sliderState ->
            Box(
                contentAlignment = Alignment.CenterStart,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(20.dp)
            ) {
                Box(
                    contentAlignment = Alignment.CenterStart,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.30f))
                    )

                    if (durationSeconds > 0L && sponsorSegments.isNotEmpty()) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val total = durationSeconds.toFloat()
                            sponsorSegments.forEach { seg ->
                                val startF = (seg.startSec / total).coerceIn(0f, 1f)
                                val endF = (seg.endSec / total).coerceIn(startF, 1f)
                                drawRect(
                                    color = seg.category.badgeColor,
                                    topLeft = Offset(startF * size.width, 0f),
                                    size = Size(
                                        ((endF - startF) * size.width).coerceAtLeast(3.dp.toPx()),
                                        size.height
                                    )
                                )
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth(sliderState.value.coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .clip(CircleShape)
                            .background(accentColor)
                    )
                }
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .height(20.dp)
    )
}
