package com.example.frontporch.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.frontporch.data.download.DownloadSettingsStore
import com.example.frontporch.data.model.ShortsCategoryItem
import com.example.frontporch.data.prefs.ShortRepeatCooldown
import com.example.frontporch.data.prefs.UserSettings
import com.example.frontporch.data.sponsorblock.SponsorBlockPreferences
import com.example.frontporch.data.sponsorblock.SponsorCategory
import com.example.frontporch.data.sponsorblock.SponsorSkipBehavior
import com.example.frontporch.ui.common.InlineMenu
import com.example.frontporch.ui.downloads.DownloadQuality
import com.example.frontporch.ui.theme.AppBackgroundTheme
import com.example.frontporch.ui.theme.AppHighlightColor
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
    overflow: TextOverflow = TextOverflow.Clip
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
        overflow = overflow
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    // Bumped when the Settings tab is tapped again: return to the top.
    reloadTick: Int = 0,
    userSettings: UserSettings,
    subscribedCount: Int = 0,
    backgroundTheme: AppBackgroundTheme,
    highlightColor: AppHighlightColor,
    onBackgroundThemeSelected: (AppBackgroundTheme) -> Unit,
    onHighlightColorSelected: (AppHighlightColor) -> Unit,
    onSponsorBlockChanged: (Boolean) -> Unit,
    onCategoriesChanged: (Set<String>) -> Unit,
    onCustomCategoriesChanged: (Set<String>) -> Unit,
    onRepeatCooldownChanged: (ShortRepeatCooldown) -> Unit = {},
    onAudioLanguageChanged: (String) -> Unit = {},
    onWifiQualityChanged: (Int) -> Unit = {},
    onMobileQualityChanged: (Int) -> Unit = {},
    onWifiDownloadQualityChanged: (Int) -> Unit = {},
    onMobileDownloadQualityChanged: (Int) -> Unit = {},
    onPlayerGesturesChanged: (Boolean) -> Unit = {},
    onNavigateToChannels: () -> Unit = {},
    onImportUri: suspend (Uri) -> Result<Int> = { Result.success(0) },
    topContentPadding: Dp = 0.dp,
    bottomContentPadding: Dp = 0.dp,
    onBackOverlayChanged: (String, Boolean) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val settingsStore = remember { DownloadSettingsStore(context) }
    var folderLabel by remember { mutableStateOf(settingsStore.getDisplayFolderPath()) }
    val listState = rememberLazyListState()

    LaunchedEffect(reloadTick) {
        if (reloadTick > 0) listState.scrollToItem(0)
    }

    var showCategoriesSubMenu by remember { mutableStateOf(false) }
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
            end = 16.dp,
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
                mobileQualityHeight = userSettings.mobileStreamMaxHeight,
                wifiDownloadHeight = userSettings.wifiDownloadMaxHeight,
                mobileDownloadHeight = userSettings.mobileDownloadMaxHeight,
                onWifiQualitySelected = onWifiQualityChanged,
                onMobileQualitySelected = onMobileQualityChanged,
                onWifiDownloadSelected = onWifiDownloadQualityChanged,
                onMobileDownloadSelected = onMobileDownloadQualityChanged
            )
        }

        // 3. Import Subscriptions Section
        item {
            NewPipeImportSettingsSection(
                subscribedCount = subscribedCount,
                onImportUri = onImportUri,
                onNavigateToChannels = onNavigateToChannels
            )
        }

        // 4. Categories Section (Button opening Sub Menu)
        item {
            CategoriesButtonCard(
                selectedCount = userSettings.selectedCategoryIds.size,
                onClickConfigure = { showCategoriesSubMenu = true }
            )
        }

        // 5. SponsorBlock Section (Toggle + Make Adjustments Button)
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
    }

    // Sub Menu: Categories & Shorts Feed Configuration
    if (showCategoriesSubMenu) {
        CategoriesSubMenuDialog(
            userSettings = userSettings,
            onCategoriesChanged = onCategoriesChanged,
            onCustomCategoriesChanged = onCustomCategoriesChanged,
            onRepeatCooldownChanged = onRepeatCooldownChanged,
            onDismiss = { showCategoriesSubMenu = false }
        )
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

@Composable
fun QualitySettingsCard(
    wifiQualityHeight: Int,
    mobileQualityHeight: Int,
    wifiDownloadHeight: Int,
    mobileDownloadHeight: Int,
    onWifiQualitySelected: (Int) -> Unit,
    onMobileQualitySelected: (Int) -> Unit,
    onWifiDownloadSelected: (Int) -> Unit,
    onMobileDownloadSelected: (Int) -> Unit
) {
    val qualityOptions = listOf(
        1080 to "1080p",
        720 to "720p",
        480 to "480p",
        360 to "360p",
        240 to "240p"
    )

    var wifiStreamExpanded by remember { mutableStateOf(false) }
    var wifiDownloadExpanded by remember { mutableStateOf(false) }
    var mobileStreamExpanded by remember { mutableStateOf(false) }
    var mobileDownloadExpanded by remember { mutableStateOf(false) }

    val wifiStreamLabel = qualityOptions.firstOrNull { it.first == wifiQualityHeight }?.second ?: "${wifiQualityHeight}p"
    val wifiDownloadLabel = qualityOptions.firstOrNull { it.first == wifiDownloadHeight }?.second ?: "${wifiDownloadHeight}p"
    val mobileStreamLabel = qualityOptions.firstOrNull { it.first == mobileQualityHeight }?.second ?: "${mobileQualityHeight}p"
    val mobileDownloadLabel = qualityOptions.firstOrNull { it.first == mobileDownloadHeight }?.second ?: "${mobileDownloadHeight}p"

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
                text = "Configure max resolution for streaming and downloads across Wi-Fi and Mobile Data.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

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
                                    qualityOptions.forEach { (height, label) ->
                                        DropdownMenuItem(
                                            text = { SettingsText(label, style = MaterialTheme.typography.bodySmall) },
                                            onClick = { onWifiQualitySelected(height); wifiStreamExpanded = false },
                                            trailingIcon = { if (height == wifiQualityHeight) Icon(Icons.Default.Check, contentDescription = null) }
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
                                    qualityOptions.forEach { (height, label) ->
                                        DropdownMenuItem(
                                            text = { SettingsText(label, style = MaterialTheme.typography.bodySmall) },
                                            onClick = { onWifiDownloadSelected(height); wifiDownloadExpanded = false },
                                            trailingIcon = { if (height == wifiDownloadHeight) Icon(Icons.Default.Check, contentDescription = null) }
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
                                    qualityOptions.forEach { (height, label) ->
                                        DropdownMenuItem(
                                            text = { SettingsText(label, style = MaterialTheme.typography.bodySmall) },
                                            onClick = { onMobileQualitySelected(height); mobileStreamExpanded = false },
                                            trailingIcon = { if (height == mobileQualityHeight) Icon(Icons.Default.Check, contentDescription = null) }
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
                                    qualityOptions.forEach { (height, label) ->
                                        DropdownMenuItem(
                                            text = { SettingsText(label, style = MaterialTheme.typography.bodySmall) },
                                            onClick = { onMobileDownloadSelected(height); mobileDownloadExpanded = false },
                                            trailingIcon = { if (height == mobileDownloadHeight) Icon(Icons.Default.Check, contentDescription = null) }
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
fun CategoriesButtonCard(
    selectedCount: Int,
    onClickConfigure: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            SettingsText(
                text = "Categories & Shorts Feeds",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(4.dp))
            SettingsText(
                text = "If nothing is selected, your subscribed channels are prioritized to base what type of Shorts to play. Selecting categories adds those topics to your feed while avoiding clickbait.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onClickConfigure,
                modifier = Modifier.fillMaxWidth()
            ) {
                SettingsText(
                    text = if (selectedCount > 0) "Configure Categories ($selectedCount selected)" else "Configure Categories"
                )
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CategoriesSubMenuDialog(
    userSettings: UserSettings,
    onCategoriesChanged: (Set<String>) -> Unit,
    onCustomCategoriesChanged: (Set<String>) -> Unit,
    onRepeatCooldownChanged: (ShortRepeatCooldown) -> Unit,
    onDismiss: () -> Unit
) {
    var newCategoryInput by remember { mutableStateOf("") }

    val customCategories = userSettings.customCategories.mapNotNull { serialized ->
        val parts = serialized.split("::")
        if (parts.size >= 3) {
            ShortsCategoryItem(id = parts[0], label = parts[1], seedQuery = parts[2], isCustom = true)
        } else null
    }

    val allCategories = ShortsCategoryItem.presets + customCategories

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { SettingsText("Categories & Shorts Feed") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                SettingsText(
                    text = "If no categories are selected, your subscribed channels are prioritized to base what type of Shorts to play. Selecting categories adds those topics to your feed while avoiding clickbait Shorts.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))

                // Custom Category Input
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = newCategoryInput,
                        onValueChange = { newCategoryInput = it },
                        placeholder = { SettingsText("Custom category (e.g. DIY)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = {
                            val trimmed = newCategoryInput.trim()
                            if (trimmed.isNotEmpty()) {
                                val customId = "custom_${System.currentTimeMillis()}"
                                val serialized = "$customId::$trimmed::$trimmed #shorts"
                                val updatedCustoms = userSettings.customCategories + serialized
                                val updatedSelected = userSettings.selectedCategoryIds + customId

                                onCustomCategoriesChanged(updatedCustoms)
                                onCategoriesChanged(updatedSelected)
                                newCategoryInput = ""
                            }
                        }
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Add")
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    allCategories.forEach { category ->
                        val isSelected = category.id in userSettings.selectedCategoryIds
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                val updated = if (isSelected) {
                                    userSettings.selectedCategoryIds - category.id
                                } else {
                                    userSettings.selectedCategoryIds + category.id
                                }
                                onCategoriesChanged(updated)
                            },
                            label = { SettingsText(category.label) },
                            trailingIcon = if (category.isCustom) {
                                {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Delete custom",
                                        modifier = Modifier.size(16.dp).clickable {
                                            val serializedToRemove = userSettings.customCategories.find { it.startsWith(category.id) }
                                            if (serializedToRemove != null) {
                                                onCustomCategoriesChanged(userSettings.customCategories - serializedToRemove)
                                            }
                                            onCategoriesChanged(userSettings.selectedCategoryIds - category.id)
                                        }
                                    )
                                }
                            } else null
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                SettingsText(text = "Repeat Video Filter", style = MaterialTheme.typography.titleSmall)
                SettingsText(
                    text = "Control how often a Short you have already watched can appear again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    ShortRepeatCooldown.entries.forEach { option ->
                        FilterChip(
                            selected = userSettings.repeatCooldown == option,
                            onClick = { onRepeatCooldownChanged(option) },
                            label = { SettingsText(option.label) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { SettingsText("Done") }
        }
    )
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
    subscribedCount: Int,
    onImportUri: suspend (Uri) -> Result<Int>,
    onNavigateToChannels: () -> Unit
) {
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
                text = "$subscribedCount channels imported",
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
                    SettingsText("Import Subscriptions", maxLines = 1)
                }

                OutlinedButton(
                    onClick = onNavigateToChannels,
                    modifier = Modifier.weight(1f)
                ) {
                    SettingsText("Manage Channels", maxLines = 1)
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
