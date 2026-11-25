package io.tehred5pade.frontporch.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun UpdateSettingsSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var autoCheckEnabled by remember { mutableStateOf(AppUpdater.isAutoCheckEnabled(context)) }
    var isChecking by remember { mutableStateOf(false) }
    var statusText by remember {
        val last = AppUpdater.getLastCheckedTimestamp(context)
        val installed = AppUpdater.getInstalledVersionName(context)
        mutableStateOf(
            if (last > 0L) {
                val formatted = SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(last))
                "Installed: v$installed • Last checked $formatted"
            } else {
                "Installed: v$installed"
            }
        )
    }

    var availableRelease by remember { mutableStateOf<ReleaseInfo?>(null) }
    var upToDateVersion by remember { mutableStateOf<String?>(null) }
    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    var installError by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        HorizontalDivider()

        Text(
            text = "App Updates",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text(
                    text = "Check for updates on launch",
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = "Compares the installed version with the latest GitHub release",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = autoCheckEnabled,
                onCheckedChange = { enabled ->
                    autoCheckEnabled = enabled
                    AppUpdater.setAutoCheckEnabled(context, enabled)
                }
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).padding(end = 12.dp)
            )

            OutlinedButton(
                enabled = !isChecking,
                onClick = {
                    scope.launch {
                        isChecking = true
                        statusText = "Checking GitHub Releases..."
                        when (val result = AppUpdater.checkForUpdates(context)) {
                            is UpdateCheckResult.UpdateAvailable -> {
                                availableRelease = result.release
                                statusText = "Update available: ${result.release.tagName}"
                            }
                            is UpdateCheckResult.UpToDate -> {
                                statusText = "Up to date (v${result.currentVersion})"
                                upToDateVersion = result.currentVersion
                            }
                            is UpdateCheckResult.Error -> {
                                statusText = "Check failed: ${result.message}"
                            }
                        }
                        isChecking = false
                    }
                }
            ) {
                if (isChecking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text("Check for Updates")
            }
        }
    }

    // Update Available Dialog
    availableRelease?.let { release ->
        AlertDialog(
            onDismissRequest = { if (!isDownloading) availableRelease = null },
            title = { Text("Update Available (${release.tagName})") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Installed: v${AppUpdater.getInstalledVersionName(context)} → Latest: ${release.tagName}",
                        style = MaterialTheme.typography.labelMedium
                    )
                    Text(
                        text = release.releaseNotes,
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (isDownloading) {
                        LinearProgressIndicator(
                            progress = { downloadProgress },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    installError?.let { err ->
                        Text(
                            text = err,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !isDownloading,
                    onClick = {
                        scope.launch {
                            isDownloading = true
                            installError = null
                            downloadProgress = 0f
                            val res = AppUpdater.downloadAndInstallApk(
                                context = context,
                                apkUrl = release.apkDownloadUrl,
                                onProgress = { downloadProgress = it }
                            )
                            isDownloading = false
                            res.onFailure { installError = it.message }
                            res.onSuccess { availableRelease = null }
                        }
                    }
                ) {
                    Text(if (isDownloading) "Downloading..." else "Download & Install")
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !isDownloading,
                    onClick = { availableRelease = null }
                ) {
                    Text("Later")
                }
            }
        )
    }

    upToDateVersion?.let { version ->
        AlertDialog(
            onDismissRequest = { upToDateVersion = null },
            title = { Text("Up to Date") },
            text = {
                Text(
                    text = "You're running the latest version (v$version). " +
                        "GitHub doesn't have a newer release."
                )
            },
            confirmButton = {
                TextButton(onClick = { upToDateVersion = null }) { Text("OK") }
            }
        )
    }
}
