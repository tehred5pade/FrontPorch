package io.tehred5pade.frontporch.ui.downloads

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

enum class DownloadQuality(val label: String, val maxHeight: Int, val targetFps: Int = 0, val audioOnly: Boolean = false) {
    BEST_AVAILABLE("Best Available (All Options / Max)", 4320, 0),
    Q2160P_60FPS("2160p 60fps (4K Ultra HD)", 2160, 60),
    Q2160P_30FPS("2160p 30fps (4K Ultra HD)", 2160, 30),
    Q1440P_60FPS("1440p 60fps (2K QHD)", 1440, 60),
    Q1440P_30FPS("1440p 30fps (2K QHD)", 1440, 30),
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
        /**
         * Maps a configured download height from the Settings tab to the closest matching
         * download quality. The 30fps variant is used for resolutions that offer both.
         */
        fun fromMaxHeight(height: Int): DownloadQuality = when {
            height >= 4320 -> BEST_AVAILABLE
            height >= 2160 -> Q2160P_30FPS
            height >= 1440 -> Q1440P_30FPS
            height >= 1080 -> Q1080P_30FPS
            height >= 720 -> Q720P_30FPS
            height >= 480 -> Q480P
            height >= 360 -> Q360P
            height >= 240 -> Q240P
            height >= 144 -> Q144P
            else -> BEST_AVAILABLE
        }
    }
}

@Composable
fun DownloadQualityDialog(
    videoCount: Int,
    defaultQualityName: String = DownloadQuality.BEST_AVAILABLE.name,
    onDismiss: () -> Unit,
    onConfirm: (DownloadQuality) -> Unit
) {
    var selectedQuality by remember {
        mutableStateOf(
            runCatching { DownloadQuality.valueOf(defaultQualityName) }.getOrDefault(DownloadQuality.BEST_AVAILABLE)
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (videoCount == 1) "Select Download Quality"
                else "Download $videoCount Videos"
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)
            ) {
                androidx.compose.foundation.lazy.LazyColumn(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(DownloadQuality.entries.size) { index ->
                        val quality = DownloadQuality.entries[index]
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
        },
        confirmButton = {
            Button(onClick = { onConfirm(selectedQuality) }) {
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
