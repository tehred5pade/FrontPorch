package io.tehred5pade.frontporch.ui.player

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

@Composable
fun AnimatedWavyProgressBar(
    progress: Float,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    activeColor: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = Color.White.copy(alpha = 0.18f)
) {
    val infiniteTransition = rememberInfiniteTransition(label = "wavy_progress")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wave_phase"
    )

    // Smoothly flatten the wave into a straight line when paused.
    val amplitudeMultiplier by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0f,
        animationSpec = tween(durationMillis = 250),
        label = "wave_amplitude"
    )

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp)
    ) {
        val clamped = progress.coerceIn(0f, 1f)
        val centerY = size.height / 2f
        val activeWidth = size.width * clamped
        val strokePx = 2.2.dp.toPx()

        // Remaining straight track.
        if (activeWidth < size.width) {
            drawLine(
                color = trackColor,
                start = Offset(activeWidth, centerY),
                end = Offset(size.width, centerY),
                strokeWidth = strokePx,
                cap = StrokeCap.Round
            )
        }

        // Active animated sine-wave progress line.
        if (activeWidth > 0f) {
            val amplitude = (size.height * 0.34f) * amplitudeMultiplier
            val wavelengthPx = 22.dp.toPx()
            val path = Path()
            var x = 0f
            val step = 2.dp.toPx()

            while (x <= activeWidth) {
                val angle = ((x / wavelengthPx) * 2f * PI).toFloat() - phase
                val y = centerY + sin(angle.toDouble()).toFloat() * amplitude
                if (x == 0f) path.moveTo(x, y) else path.lineTo(x, y)
                x += step
            }
            val endAngle = ((activeWidth / wavelengthPx) * 2f * PI).toFloat() - phase
            path.lineTo(activeWidth, centerY + sin(endAngle.toDouble()).toFloat() * amplitude)

            drawPath(
                path = path,
                color = activeColor,
                style = Stroke(width = strokePx, cap = StrokeCap.Round)
            )
        }
    }
}
