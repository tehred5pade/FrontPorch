package io.tehred5pade.frontporch.ui.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.tehred5pade.frontporch.data.innertube.formatDurationSeconds

/**
 * Bottom-right length pill shown over a video thumbnail, e.g. "12:45". Videos whose length
 * is unknown (and Shorts, which should never render a time) simply show nothing.
 */
@Composable
fun DurationBadge(
    durationSeconds: Int,
    modifier: Modifier = Modifier,
    isShort: Boolean = false
) {
    if (isShort || durationSeconds <= 0) return
    val label = formatDurationSeconds(durationSeconds)
    if (label.isEmpty()) return
    Surface(
        color = Color.Black.copy(alpha = 0.8f),
        contentColor = Color.White,
        shape = RoundedCornerShape(4.dp),
        modifier = modifier.padding(6.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
        )
    }
}

/** Thin watched-progress bar pinned to the bottom edge of a thumbnail. */
@Composable
fun WatchProgressBar(
    fraction: Float,
    modifier: Modifier = Modifier
) {
    if (fraction <= 0f) return
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(3.dp)
            .background(Color.White.copy(alpha = 0.35f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .background(Color(0xFFE53935))
        )
    }
}
