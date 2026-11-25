package io.tehred5pade.frontporch.ui.downloads

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import io.tehred5pade.frontporch.ui.common.frostedGlass

enum class DownloadQuality(val label: String, val maxHeight: Int, val targetFps: Int = 0, val audioOnly: Boolean = false) {
    BEST_AVAILABLE("Best Available (All Options / Max)", 4320, 0),
    Q2160P_60FPS("2160p 60fps (4K Ultra HD)", 2160, 60),
    Q2160P_30FPS("2160p 30fps (4K Ultra HD)", 2160, 30),
    Q1440P_60FPS("1440p 60fps (2K QHD)", 1440, 60),
    Q1440P_30FPS("1440p 30fps (2K QHD)", 1440, 30),
    BEST_AVAILABLE_HD("Best Available (Up to 1080p)", 1080, 0),
    Q1080P_60FPS("1080p 60fps (Full HD)", 1080, 60),
    Q1080P_30FPS("1080p 30fps (Full HD)", 1080, 30),
    Q720P_60FPS("720p 60fps (HD)", 720, 60),
    Q720P_30FPS("720p 30fps (HD)", 720, 30),
    Q480P("480p (SD)", 480, 0),
    Q360P("360p (Data Saver)", 360, 0),
    Q240P("240p (Low Data)", 240, 0),
    Q144P("144p (Lowest Data)", 144, 0),
    AUDIO_ONLY("Audio Only (M4A)", 0, 0, audioOnly = true);

    companion object {
        /** 1440p and 2160p are the options controlled by the settings toggle. */
        fun isHighRes(quality: DownloadQuality): Boolean =
            quality != BEST_AVAILABLE &&
                quality != BEST_AVAILABLE_HD &&
                !quality.audioOnly &&
                quality.maxHeight >= 1440

        private fun bestForSetting(allowHighRes: Boolean): DownloadQuality =
            if (allowHighRes) BEST_AVAILABLE else BEST_AVAILABLE_HD

        /**
         * Single source of truth for every download-quality list. The "best" entry swaps so a
         * disabled high-res toggle can never leave a max-quality option that silently pulls 4K.
         */
        fun allowedDownloadQualities(allowHighRes: Boolean): List<DownloadQuality> = entries.filter { quality ->
            when (quality) {
                BEST_AVAILABLE -> allowHighRes
                BEST_AVAILABLE_HD -> !allowHighRes
                else -> allowHighRes || !isHighRes(quality)
            }
        }

        /** Keeps enum order (highest first) while dropping options this video can't produce. */
        fun availableDownloadOptions(
            available: Set<DownloadQuality>,
            allowHighRes: Boolean
        ): List<DownloadQuality> {
            val allowed = allowedDownloadQualities(allowHighRes).toSet()
            return entries.filter { it in available && it in allowed }
        }

        /** Clamps a stored/default selection to what is both allowed and actually available. */
        fun resolveDownloadDefault(
            requested: DownloadQuality,
            available: Set<DownloadQuality>?,
            allowHighRes: Boolean
        ): DownloadQuality {
            val options = if (available == null) {
                allowedDownloadQualities(allowHighRes)
            } else {
                availableDownloadOptions(available, allowHighRes)
            }
            if (options.isEmpty()) return bestForSetting(allowHighRes)

            val normalized = when {
                !allowHighRes && (requested == BEST_AVAILABLE || isHighRes(requested)) -> bestForSetting(false)
                allowHighRes && requested == BEST_AVAILABLE_HD -> bestForSetting(true)
                else -> requested
            }
            if (normalized in options) return normalized

            if (normalized.audioOnly) {
                return options.firstOrNull { it.audioOnly } ?: fallbackBest(options, allowHighRes)
            }

            val candidates = options.filter {
                !it.audioOnly &&
                    it != BEST_AVAILABLE &&
                    it != BEST_AVAILABLE_HD &&
                    it.maxHeight <= normalized.maxHeight
            }
            val sameFps = candidates.filter { it.targetFps == normalized.targetFps }
            return (sameFps + candidates)
                .distinct()
                .maxByOrNull { it.maxHeight * 100 + it.targetFps }
                ?: fallbackBest(options, allowHighRes)
        }

        private fun fallbackBest(
            options: List<DownloadQuality>,
            allowHighRes: Boolean
        ): DownloadQuality = options.firstOrNull { it == bestForSetting(allowHighRes) }
            ?: options.first { !it.audioOnly }

        /**
         * Maps the Settings download dropdown (height + frame rate) to the picker's default.
         * A 60fps selection resolves to the 60fps variant when that resolution offers one.
         */
        fun fromSettings(height: Int, targetFps: Int): DownloadQuality = when {
            height >= 4320 -> BEST_AVAILABLE
            height >= 2160 -> if (targetFps >= 60) Q2160P_60FPS else Q2160P_30FPS
            height >= 1440 -> if (targetFps >= 60) Q1440P_60FPS else Q1440P_30FPS
            height >= 1080 -> if (targetFps >= 60) Q1080P_60FPS else BEST_AVAILABLE_HD
            height >= 720 -> if (targetFps >= 60) Q720P_60FPS else Q720P_30FPS
            height >= 480 -> Q480P
            height >= 360 -> Q360P
            height >= 240 -> Q240P
            height >= 144 -> Q144P
            else -> BEST_AVAILABLE
        }

        fun fromMaxHeight(height: Int): DownloadQuality = fromSettings(height, 0)
    }
}

@Composable
fun DownloadQualityDialog(
    videoCount: Int,
    defaultQualityName: String = DownloadQuality.BEST_AVAILABLE.name,
    availableQualities: Set<DownloadQuality>? = null,
    allowHighResDownloads: Boolean = true,
    hazeState: HazeState? = null,
    glassStyle: HazeStyle? = null,
    onDismiss: () -> Unit,
    onConfirm: (DownloadQuality) -> Unit
) {
    val requested = runCatching { DownloadQuality.valueOf(defaultQualityName) }
        .getOrDefault(DownloadQuality.BEST_AVAILABLE)
    val isCheckingFormats = availableQualities == null
    val options = availableQualities
        ?.let { DownloadQuality.availableDownloadOptions(it, allowHighResDownloads) }
        ?: DownloadQuality.allowedDownloadQualities(allowHighResDownloads)
    val hasNoOptions = !isCheckingFormats && options.isEmpty()
    val dialogShape = RoundedCornerShape(28.dp)

    var selectedQuality by remember {
        mutableStateOf(
            DownloadQuality.resolveDownloadDefault(requested, availableQualities, allowHighResDownloads)
        )
    }
    LaunchedEffect(requested, availableQualities, allowHighResDownloads) {
        selectedQuality = DownloadQuality.resolveDownloadDefault(
            requested,
            availableQualities,
            allowHighResDownloads
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.frostedGlass(
            hazeState = hazeState,
            glassStyle = glassStyle,
            shape = dialogShape
        ),
        shape = dialogShape,
        containerColor = Color.Transparent,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        title = {
            Text(
                text = if (videoCount == 1) "Select Download Quality"
                else "Download $videoCount Videos"
            )
        },
        text = {
            if (isCheckingFormats) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth().padding(24.dp)
                ) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Checking available formats…", style = MaterialTheme.typography.bodyMedium)
                }
            } else if (hasNoOptions) {
                Text(
                    text = "No downloadable formats are available for these videos with the current quality settings.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp)
                )
            } else {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)
                ) {
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        items(options, key = { it.name }) { quality ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedQuality = quality }
                                    .padding(vertical = 6.dp)
                            ) {
                                RadioButton(
                                    selected = selectedQuality == quality,
                                    onClick = { selectedQuality = quality }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = quality.label,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(selectedQuality) },
                enabled = !isCheckingFormats && !hasNoOptions
            ) {
                Text("Download")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
