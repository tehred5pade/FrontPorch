package io.tehred5pade.frontporch.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.tehred5pade.frontporch.data.db.SubscriptionEntity
import io.tehred5pade.frontporch.data.download.DownloadSettingsStore
import io.tehred5pade.frontporch.data.importexport.FrontPorchBackupManager
import io.tehred5pade.frontporch.data.importexport.SubscriptionExporter
import io.tehred5pade.frontporch.data.local.FrontPorchDatabase
import io.tehred5pade.frontporch.data.playlist.PlaylistStore
import io.tehred5pade.frontporch.data.prefs.UserSettings
import io.tehred5pade.frontporch.data.sponsorblock.SponsorBlockPreferences
import io.tehred5pade.frontporch.data.sponsorblock.SponsorCategory
import io.tehred5pade.frontporch.data.sponsorblock.SponsorSkipBehavior
import io.tehred5pade.frontporch.ui.common.InlineMenu
import io.tehred5pade.frontporch.ui.theme.AppBackgroundTheme
import io.tehred5pade.frontporch.ui.theme.AppHighlightColor
import io.tehred5pade.frontporch.update.UpdateSettingsSection
import kotlinx.coroutines.launch

private val SettingsTextOnDark = Color(0xFFC8CDD6)
private val SettingsTextOnLight = Color(0xFF3A414B)

private fun isLightColor(color: Color): Boolean {
    val luminance = color.red * 0.299f + color.green * 0.587f + color.blue * 0.114f
    return luminance > 0.55f
}

@Composable
private fun SettingsText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    textAlign: TextAlign? = null
) {
    val textColor = if (isLightColor(MaterialTheme.colorScheme.background)) {
        SettingsTextOnLight
    } else {
        SettingsTextOnDark
    }
    Text(
        text = text,
        modifier = modifier,
        style = style,
        color = textColor,
        maxLines = maxLines,
        overflow = overflow,
        textAlign = textAlign
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    // Bumped when the Settings tab is tapped again: return to the top.
    reloadTick: Int = 0,
    userSettings: UserSettings,
    subscriptions: List<SubscriptionEntity> = emptyList(),
    backgroundTheme: AppBackgroundTheme,
    highlightColor: AppHighlightColor,
    onBackgroundThemeSelected: (AppBackgroundTheme) -> Unit,
    onHighlightColorSelected: (AppHighlightColor) -> Unit,
    onSponsorBlockChanged: (Boolean) -> Unit,
    onAudioLanguageChanged: (String) -> Unit = {},
    onWifiQualityChanged: (Int, Int) -> Unit = { _, _ -> },
    onMobileQualityChanged: (Int, Int) -> Unit = { _, _ -> },
    onWifiDownloadQualityChanged: (Int, Int) -> Unit = { _, _ -> },
    onMobileDownloadQualityChanged: (Int, Int) -> Unit = { _, _ -> },
    allowHighResContent: Boolean = true,
    onAllowHighResContentChanged: (Boolean) -> Unit = {},
    onPlayerGesturesChanged: (Boolean) -> Unit = {},
    onNavigateToChannels: () -> Unit = {},
    onImportUri: suspend (Uri) -> Result<Int> = { Result.success(0) },
    topContentPadding: Dp = 0.dp,
    bottomContentPadding: Dp = 0.dp,
    endContentPadding: Dp = 0.dp,
    onBackOverlayChanged: (String, Boolean) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val settingsStore = remember { DownloadSettingsStore(context) }
    var folderLabel by remember { mutableStateOf(settingsStore.getDisplayFolderPath()) }
    val listState = rememberLazyListState()

    LaunchedEffect(reloadTick) {
        if (reloadTick > 0) listState.scrollToItem(0)
    }

    var showSponsorBlockSubMenu by remember { mutableStateOf(false) }

    val storagePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* Permission result handled */ }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
            settingsStore.setCustomFolderUri(uri)
            folderLabel = settingsStore.getDisplayFolderPath()
        }
    }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp + endContentPadding,
            top = topContentPadding,
            bottom = bottomContentPadding
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        // 1. Audio Language Section (Top of the list)
        item {
            AudioLanguageCard(
                selectedLanguage = userSettings.preferredAudioLanguage,
                onLanguageSelected = onAudioLanguageChanged,
                onExpandedChanged = { onBackOverlayChanged("settings-audio-menu", it) }
            )
        }

        // 2. Stream & Download Quality Section (2 columns: Wi-Fi vs Mobile)
        item {
            QualitySettingsCard(
                wifiQualityHeight = userSettings.wifiStreamMaxHeight,
                wifiStreamTargetFps = userSettings.wifiStreamTargetFps,
                mobileQualityHeight = userSettings.mobileStreamMaxHeight,
                mobileStreamTargetFps = userSettings.mobileStreamTargetFps,
                wifiDownloadHeight = userSettings.wifiDownloadMaxHeight,
                wifiDownloadTargetFps = userSettings.wifiDownloadTargetFps,
                mobileDownloadHeight = userSettings.mobileDownloadMaxHeight,
                mobileDownloadTargetFps = userSettings.mobileDownloadTargetFps,
                allowHighResContent = allowHighResContent,
                onWifiQualitySelected = onWifiQualityChanged,
                onMobileQualitySelected = onMobileQualityChanged,
                onWifiDownloadSelected = onWifiDownloadQualityChanged,
                onMobileDownloadSelected = onMobileDownloadQualityChanged,
                onAllowHighResContentChanged = onAllowHighResContentChanged
            )
        }

        // 3. Import Subscriptions Section
        item {
            NewPipeImportSettingsSection(
                subscriptions = subscriptions,
                onImportUri = onImportUri,
                onNavigateToChannels = onNavigateToChannels
            )
        }

        // 3.5. Full Backup & Restore Section
        item {
            FullBackupSettingsSection()
        }

        // 4. SponsorBlock Section (Toggle + Make Adjustments Button)
        item {
            SponsorBlockMainCard(
                onSponsorBlockChanged = onSponsorBlockChanged,
                onClickMakeAdjustments = { showSponsorBlockSubMenu = true }
            )
        }

        // 6. Appearance & Themes Section
        item {
            ThemeSelectorCard(
                selectedBackground = backgroundTheme,
                selectedHighlight = highlightColor,
                onBackgroundSelected = onBackgroundThemeSelected,
                onHighlightSelected = onHighlightColorSelected
            )
        }

        // 7. Player Gesture Controls Section
        item {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SettingsText(
                        text = "Player & Gesture Controls",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    SettingsText(
                        text = "Enable touch gestures during playback (brightness, 1% volume steps, double-tap seek & play/pause, progressive swipe seeking).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        SettingsText(
                            text = "Enable Player Gestures",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = userSettings.playerGesturesEnabled,
                            onCheckedChange = onPlayerGesturesChanged
                        )
                    }
                }
            }
        }

        // 8. Download Location & Storage Section
        item {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SettingsText(
                        text = "Download Location & Storage",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    SettingsText(
                        text = folderLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { folderPickerLauncher.launch(settingsStore.getCustomFolderUri()) }) {
                            SettingsText("Choose Folder")
                        }
                        OutlinedButton(
                            onClick = {
                                val perms = if (android.os.Build.VERSION.SDK_INT >= 33) {
                                    arrayOf(android.Manifest.permission.READ_MEDIA_VIDEO)
                                } else {
                                    arrayOf(
                                        android.Manifest.permission.READ_EXTERNAL_STORAGE,
                                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                                    )
                                }
                                storagePermissionLauncher.launch(perms)
                            }
                        ) {
                            SettingsText("Grant Storage Permission")
                        }
                    }
                }
            }
        }

        // 9. App Updates (GitHub Releases / APK installer)
        item {
            UpdateSettingsSection()
        }
    }

    // Sub Menu: SponsorBlock Adjustments
    if (showSponsorBlockSubMenu) {
        SponsorBlockSubMenuDialog(
            onDismiss = { showSponsorBlockSubMenu = false }
        )
    }
}

@Composable
fun AudioLanguageCard(
    selectedLanguage: String,
    onLanguageSelected: (String) -> Unit,
    onExpandedChanged: (Boolean) -> Unit = {}
) {
    val languages = listOf(
        "en" to "English",
        "es" to "Spanish",
        "fr" to "French",
        "de" to "German",
        "ja" to "Japanese",
        "pt" to "Portuguese",
        "it" to "Italian",
        "ru" to "Russian",
        "ko" to "Korean",
        "hi" to "Hindi",
        "zh" to "Chinese",
        "auto" to "Default / Original"
    )
    var expanded by remember { mutableStateOf(false) }
    DisposableEffect(expanded) {
        onExpandedChanged(expanded)
        onDispose { onExpandedChanged(false) }
    }
    val currentLabel = languages.firstOrNull { it.first == selectedLanguage }?.second ?: selectedLanguage

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            SettingsText(
                text = "Audio Language",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(4.dp))
            SettingsText(
                text = "Forced in all streams, shorts, and downloads.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))

            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { expanded = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SettingsText(text = currentLabel, style = MaterialTheme.typography.bodyMedium)
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                    }
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 48.dp)
                        .zIndex(2f)
                ) {
                    InlineMenu(
                        expanded = expanded,
                        onDismiss = { expanded = false }
                    ) {
                        languages.forEach { (code, name) ->
                            DropdownMenuItem(
                                text = { SettingsText(name) },
                                onClick = {
                                    onLanguageSelected(code)
                                    expanded = false
                                },
                                trailingIcon = {
                                    if (code == selectedLanguage) {
                                        Icon(Icons.Default.Check, contentDescription = "Selected")
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

private data class QualityOption(val height: Int, val fps: Int, val label: String)

/** Auto is stream-only; height 0 tells the resolver to adapt to the current link speed. */
private fun streamQualityOptions(allowHighRes: Boolean): List<QualityOption> = buildList {
    add(QualityOption(0, 0, "Auto"))
    if (allowHighRes) {
        add(QualityOption(4320, 0, "Best Available"))
        add(QualityOption(2160, 0, "2160p (4K)"))
        add(QualityOption(1440, 0, "1440p (2K)"))
    }
    add(QualityOption(1080, 60, "1080p 60fps"))
    add(QualityOption(1080, 0, "1080p"))
    add(QualityOption(720, 60, "720p 60fps"))
    add(QualityOption(720, 0, "720p"))
    add(QualityOption(480, 0, "480p"))
    add(QualityOption(360, 0, "360p"))
    add(QualityOption(240, 0, "240p"))
    add(QualityOption(144, 0, "144p"))
}

/** Downloads keep the same 60fps choices but never expose Auto. */
private fun downloadQualityOptions(allowHighRes: Boolean): List<QualityOption> = buildList {
    if (allowHighRes) {
        add(QualityOption(4320, 0, "Best Available"))
        add(QualityOption(2160, 0, "2160p (4K)"))
        add(QualityOption(1440, 0, "1440p (2K)"))
    }
    add(QualityOption(1080, 60, "1080p 60fps"))
    add(QualityOption(1080, 0, "1080p"))
    add(QualityOption(720, 60, "720p 60fps"))
    add(QualityOption(720, 0, "720p"))
    add(QualityOption(480, 0, "480p"))
    add(QualityOption(360, 0, "360p"))
    add(QualityOption(240, 0, "240p"))
    add(QualityOption(144, 0, "144p"))
}

@Composable
fun QualitySettingsCard(
    wifiQualityHeight: Int,
    wifiStreamTargetFps: Int,
    mobileQualityHeight: Int,
    mobileStreamTargetFps: Int,
    wifiDownloadHeight: Int,
    wifiDownloadTargetFps: Int,
    mobileDownloadHeight: Int,
    mobileDownloadTargetFps: Int,
    allowHighResContent: Boolean,
    onWifiQualitySelected: (Int, Int) -> Unit,
    onMobileQualitySelected: (Int, Int) -> Unit,
    onWifiDownloadSelected: (Int, Int) -> Unit,
    onMobileDownloadSelected: (Int, Int) -> Unit,
    onAllowHighResContentChanged: (Boolean) -> Unit
) {
    val streamOptions = streamQualityOptions(allowHighResContent)
    val downloadOptions = downloadQualityOptions(allowHighResContent)

    var wifiStreamExpanded by remember { mutableStateOf(false) }
    var wifiDownloadExpanded by remember { mutableStateOf(false) }
    var mobileStreamExpanded by remember { mutableStateOf(false) }
    var mobileDownloadExpanded by remember { mutableStateOf(false) }

    fun labelFor(options: List<QualityOption>, height: Int, fps: Int): String {
        val normalizedHeight = if (!allowHighResContent && height > 1080) 1080 else height
        return options.firstOrNull { it.height == normalizedHeight && it.fps == fps }?.label
            ?: when {
                normalizedHeight <= 0 -> "Auto"
                else -> "${normalizedHeight}p" + if (fps >= 60) " 60fps" else ""
            }
    }

    val wifiStreamLabel = labelFor(streamOptions, wifiQualityHeight, wifiStreamTargetFps)
    val wifiDownloadLabel = labelFor(downloadOptions, wifiDownloadHeight, wifiDownloadTargetFps)
    val mobileStreamLabel = labelFor(streamOptions, mobileQualityHeight, mobileStreamTargetFps)
    val mobileDownloadLabel = labelFor(downloadOptions, mobileDownloadHeight, mobileDownloadTargetFps)

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(16.dp)
        ) {
            SettingsText(
                text = "Stream & Download Quality",
                style = MaterialTheme.typography.titleMedium
            )
            SettingsText(
                text = "Stream supports Auto based on connection speed. Downloads use the exact resolution and frame rate selected.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    SettingsText(
                        text = "Allow 2K / 4K",
                        style = MaterialTheme.typography.titleSmall
                    )
                    SettingsText(
                        text = "Offer 1440p and 2160p options for streams and downloads",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = allowHighResContent,
                    onCheckedChange = onAllowHighResContentChanged
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                // Column 1: Wi-Fi
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    SettingsText(
                        text = "Wi-Fi",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )

                    // Wi-Fi Stream
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        SettingsText(text = "Stream", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Box(modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(onClick = { wifiStreamExpanded = true }, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    SettingsText(text = wifiStreamLabel, style = MaterialTheme.typography.bodySmall)
                                    Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(16.dp))
                                }
                            }
                            Box(modifier = Modifier.align(Alignment.TopEnd).padding(top = 40.dp).zIndex(2f)) {
                                InlineMenu(expanded = wifiStreamExpanded, onDismiss = { wifiStreamExpanded = false }) {
                                    streamOptions.forEach { option ->
                                        DropdownMenuItem(
                                            text = { SettingsText(option.label, style = MaterialTheme.typography.bodySmall) },
                                            onClick = {
                                                onWifiQualitySelected(option.height, option.fps)
                                                wifiStreamExpanded = false
                                            },
                                            trailingIcon = {
                                                if (option.height == wifiQualityHeight && option.fps == wifiStreamTargetFps) {
                                                    Icon(Icons.Default.Check, contentDescription = null)
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Wi-Fi Download
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        SettingsText(text = "Download", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Box(modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(onClick = { wifiDownloadExpanded = true }, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    SettingsText(text = wifiDownloadLabel, style = MaterialTheme.typography.bodySmall)
                                    Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(16.dp))
                                }
                            }
                            Box(modifier = Modifier.align(Alignment.TopEnd).padding(top = 40.dp).zIndex(2f)) {
                                InlineMenu(expanded = wifiDownloadExpanded, onDismiss = { wifiDownloadExpanded = false }) {
                                    downloadOptions.forEach { option ->
                                        DropdownMenuItem(
                                            text = { SettingsText(option.label, style = MaterialTheme.typography.bodySmall) },
                                            onClick = {
                                                onWifiDownloadSelected(option.height, option.fps)
                                                wifiDownloadExpanded = false
                                            },
                                            trailingIcon = {
                                                if (option.height == wifiDownloadHeight && option.fps == wifiDownloadTargetFps) {
                                                    Icon(Icons.Default.Check, contentDescription = null)
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Column 2: Mobile Data
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    SettingsText(
                        text = "Mobile Data",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )

                    // Mobile Stream
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        SettingsText(text = "Stream", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Box(modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(onClick = { mobileStreamExpanded = true }, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    SettingsText(text = mobileStreamLabel, style = MaterialTheme.typography.bodySmall)
                                    Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(16.dp))
                                }
                            }
                            Box(modifier = Modifier.align(Alignment.TopEnd).padding(top = 40.dp).zIndex(2f)) {
                                InlineMenu(expanded = mobileStreamExpanded, onDismiss = { mobileStreamExpanded = false }) {
                                    streamOptions.forEach { option ->
                                        DropdownMenuItem(
                                            text = { SettingsText(option.label, style = MaterialTheme.typography.bodySmall) },
                                            onClick = {
                                                onMobileQualitySelected(option.height, option.fps)
                                                mobileStreamExpanded = false
                                            },
                                            trailingIcon = {
                                                if (option.height == mobileQualityHeight && option.fps == mobileStreamTargetFps) {
                                                    Icon(Icons.Default.Check, contentDescription = null)
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Mobile Download
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        SettingsText(text = "Download", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Box(modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(onClick = { mobileDownloadExpanded = true }, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    SettingsText(text = mobileDownloadLabel, style = MaterialTheme.typography.bodySmall)
                                    Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(16.dp))
                                }
                            }
                            Box(modifier = Modifier.align(Alignment.TopEnd).padding(top = 40.dp).zIndex(2f)) {
                                InlineMenu(expanded = mobileDownloadExpanded, onDismiss = { mobileDownloadExpanded = false }) {
                                    downloadOptions.forEach { option ->
                                        DropdownMenuItem(
                                            text = { SettingsText(option.label, style = MaterialTheme.typography.bodySmall) },
                                            onClick = {
                                                onMobileDownloadSelected(option.height, option.fps)
                                                mobileDownloadExpanded = false
                                            },
                                            trailingIcon = {
                                                if (option.height == mobileDownloadHeight && option.fps == mobileDownloadTargetFps) {
                                                    Icon(Icons.Default.Check, contentDescription = null)
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SponsorBlockMainCard(
    onSponsorBlockChanged: (Boolean) -> Unit,
    onClickMakeAdjustments: () -> Unit
) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val sbEnabled by SponsorBlockPreferences.isEnabled.collectAsState()

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    SettingsText(
                        text = "SponsorBlock",
                        style = MaterialTheme.typography.titleMedium,
                        color = scheme.onSurface
                    )
                    SettingsText(
                        text = "Skip or prompt on crowdsourced video segments.",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = sbEnabled,
                    onCheckedChange = { enabled ->
                        SponsorBlockPreferences.setEnabled(context, enabled)
                        onSponsorBlockChanged(enabled)
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = scheme.onPrimary,
                        checkedTrackColor = scheme.primary
                    )
                )
            }

            if (sbEnabled) {
                OutlinedButton(
                    onClick = onClickMakeAdjustments,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    SettingsText("Make Adjustments")
                }
            }
        }
    }
}

@Composable
fun SponsorBlockSubMenuDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val behaviors by SponsorBlockPreferences.categoryBehaviors.collectAsState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { SettingsText("SponsorBlock Adjustments") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                // Quick-Set Preset Buttons
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    SponsorSkipBehavior.entries.forEach { preset ->
                        OutlinedButton(
                            onClick = { SponsorBlockPreferences.setAllCategories(context, preset) },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            SettingsText(
                                text = "All: ${preset.label}",
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1
                            )
                        }
                    }
                }

                HorizontalDivider(color = scheme.outlineVariant)

                // Every category with an Auto-Skip / Disabled selector
                SponsorCategory.entries.forEach { category ->
                    val currentBehavior = behaviors[category] ?: category.defaultBehavior

                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(category.badgeColor)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            SettingsText(
                                text = category.title,
                                style = MaterialTheme.typography.bodyMedium,
                                color = scheme.onSurface
                            )
                        }

                        SettingsText(
                            text = category.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant
                        )

                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            SponsorSkipBehavior.entries.forEachIndexed { index, option ->
                                val selected = currentBehavior == option
                                SegmentedButton(
                                    selected = selected,
                                    onClick = {
                                        SponsorBlockPreferences.setCategoryBehavior(
                                            context = context,
                                            category = category,
                                            behavior = option
                                        )
                                    },
                                    shape = SegmentedButtonDefaults.itemShape(
                                        index = index,
                                        count = SponsorSkipBehavior.entries.size
                                    ),
                                    colors = SegmentedButtonDefaults.colors(
                                        activeContainerColor = scheme.primary,
                                        activeContentColor = scheme.onPrimary,
                                        inactiveContainerColor = scheme.surfaceContainer,
                                        inactiveContentColor = scheme.onSurfaceVariant
                                    ),
                                    icon = {}
                                ) {
                                    SettingsText(
                                        text = option.label,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { SettingsText("Done") }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ThemeSelectorCard(
    selectedBackground: AppBackgroundTheme,
    selectedHighlight: AppHighlightColor,
    onBackgroundSelected: (AppBackgroundTheme) -> Unit,
    onHighlightSelected: (AppHighlightColor) -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        color = scheme.surfaceVariant,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(14.dp)
        ) {
            SettingsText(
                text = "Appearance & Theme",
                style = MaterialTheme.typography.titleMedium
            )

            SettingsText(
                text = "Background",
                style = MaterialTheme.typography.labelMedium
            )

            AppBackgroundTheme.entries.forEach { theme ->
                val isSelected = theme == selectedBackground
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onBackgroundSelected(theme) }
                        .padding(vertical = 6.dp, horizontal = 4.dp)
                ) {
                    RadioButton(
                        selected = isSelected,
                        onClick = { onBackgroundSelected(theme) },
                        colors = RadioButtonDefaults.colors(
                            selectedColor = selectedHighlight.color,
                            unselectedColor = scheme.onSurfaceVariant
                        )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        SettingsText(
                            text = theme.label,
                            style = MaterialTheme.typography.bodyLarge
                        )
                        SettingsText(
                            text = theme.subtitle,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            HorizontalDivider(color = scheme.outlineVariant)

            SettingsText(
                text = "Highlight color",
                style = MaterialTheme.typography.labelMedium
            )
            SettingsText(
                text = "Used for buttons, toggles, and selected controls",
                style = MaterialTheme.typography.bodySmall
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                AppHighlightColor.entries.forEach { highlight ->
                    val isSelected = highlight == selectedHighlight
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .width(76.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onHighlightSelected(highlight) }
                            .padding(vertical = 6.dp, horizontal = 2.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(if (isSelected) scheme.onSurface else Color.Transparent)
                                .padding(3.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(CircleShape)
                                    .background(highlight.color)
                            )
                        }
                        SettingsText(
                            text = highlight.label,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun NewPipeImportSettingsSection(
    subscriptions: List<SubscriptionEntity>,
    onImportUri: suspend (Uri) -> Result<Int>,
    onNavigateToChannels: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var statusMessage by remember { mutableStateOf<String?>(null) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                onImportUri(uri)
                    .onSuccess { count ->
                        statusMessage = "Imported $count YouTube channels."
                    }
                    .onFailure { err ->
                        statusMessage = "Import failed: ${err.message}"
                    }
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    val json = SubscriptionExporter.toNewPipeJson(subscriptions)
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        output.write(json.toByteArray(Charsets.UTF_8))
                    } ?: error("Unable to open file for writing")
                }.onSuccess {
                    statusMessage = "Exported ${subscriptions.size} channels."
                }.onFailure { err ->
                    statusMessage = "Export failed: ${err.message}"
                }
            }
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            SettingsText(
                text = "Subscriptions",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(4.dp))
            SettingsText(
                text = "${subscriptions.size} channels imported",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = {
                        filePickerLauncher.launch(
                            arrayOf(
                                "application/json",
                                "text/csv",
                                "text/comma-separated-values",
                                "text/plain",
                                "application/zip",
                                "*/*"
                            )
                        )
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    SettingsText(
                        text = "Import Subscriptions",
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                }

                OutlinedButton(
                    onClick = { exportLauncher.launch("frontporch_subscriptions.json") },
                    modifier = Modifier.weight(1f)
                ) {
                    SettingsText(
                        text = "Export Subscriptions",
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = onNavigateToChannels,
                modifier = Modifier.fillMaxWidth()
            ) {
                SettingsText("Manage Channels", maxLines = 1)
            }

            if (statusMessage != null) {
                Spacer(modifier = Modifier.height(8.dp))
                SettingsText(
                    text = statusMessage!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
fun FullBackupSettingsSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var statusMessage by remember { mutableStateOf<String?>(null) }
    val db = FrontPorchDatabase.getDatabase(context)
    val subscriptionDao = db.subscriptionsDao()
    val playlistStore = remember(context) { PlaylistStore(context) }

    val exportFullLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    val json = FrontPorchBackupManager.exportBackup(context, db, subscriptionDao, playlistStore)
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        output.write(json.toByteArray(Charsets.UTF_8))
                    } ?: error("Unable to open backup file for writing")
                }.onSuccess {
                    statusMessage = "Full backup exported successfully."
                }.onFailure { err ->
                    statusMessage = "Backup export failed: ${err.message}"
                }
            }
        }
    }

    val importFullLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    val json = context.contentResolver.openInputStream(uri)?.use { input ->
                        input.bufferedReader().readText()
                    } ?: error("Unable to read backup file")
                    FrontPorchBackupManager.importBackup(context, db, subscriptionDao, playlistStore, json).getOrThrow()
                }.onSuccess { summary ->
                    statusMessage = "Restored: ${summary.channelsImported} channels, ${summary.playlistsImported} playlists, ${summary.algorithmEntriesImported} shorts algorithm entries."
                }.onFailure { err ->
                    statusMessage = "Backup import failed: ${err.message}"
                }
            }
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            SettingsText(
                text = "Full App Backup & Restore",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(4.dp))
            SettingsText(
                text = "Backup or restore system settings, playlists, subscribed channels, and your YouTube Shorts recommendation profile.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = { importFullLauncher.launch(arrayOf("application/json", "*/*")) },
                    modifier = Modifier.weight(1f)
                ) {
                    SettingsText(
                        text = "Import Backup",
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                }

                OutlinedButton(
                    onClick = { exportFullLauncher.launch("frontporch_full_backup.json") },
                    modifier = Modifier.weight(1f)
                ) {
                    SettingsText(
                        text = "Export Backup",
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                }
            }

            if (statusMessage != null) {
                Spacer(modifier = Modifier.height(8.dp))
                SettingsText(
                    text = statusMessage!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
