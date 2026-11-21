package com.example.frontporch.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class MinimizeBehavior { NONE, BACKGROUND_PLAYER, POPUP_PLAYER }
enum class GestureAction { BRIGHTNESS, VOLUME, NONE }
enum class StoryboardQuality { LOW, HIGH }
enum class AutoplayMode { ALWAYS, NEVER }

enum class ShortRepeatCooldown(val label: String, val durationMs: Long) {
    ALLOW_ANYTIME("Allow Repeats", 0L),
    ONE_DAY("Once per Day", 24L * 60 * 60 * 1000),
    SEVEN_DAYS("Once per Week", 7L * 24 * 60 * 60 * 1000),
    THIRTY_DAYS("Once per Month", 30L * 24 * 60 * 60 * 1000),
    NEVER("Never Repeat", Long.MAX_VALUE)
}

data class UserSettings(
    val mobileStreamMaxHeight: Int = 480,
    val wifiStreamMaxHeight: Int = 1080,
    val mobileDownloadMaxHeight: Int = 480,
    val wifiDownloadMaxHeight: Int = 1080,
    val allowAv1: Boolean = true,
    val preferOriginalAudio: Boolean = true,
    val pipEnabled: Boolean = true,
    val backgroundPlayEnabled: Boolean = true,
    val minimizeBehavior: MinimizeBehavior = MinimizeBehavior.POPUP_PLAYER,
    val autoFullscreen: Boolean = false,
    val pinVideoWhileScrolling: Boolean = true,
    val autoplayMode: AutoplayMode = AutoplayMode.ALWAYS,
    val storyboardQuality: StoryboardQuality = StoryboardQuality.HIGH,
    val leftSwipeGesture: GestureAction = GestureAction.BRIGHTNESS,
    val rightSwipeGesture: GestureAction = GestureAction.VOLUME,
    val swipeToSeekEnabled: Boolean = true,
    val fastSeekDoubleTapEnabled: Boolean = true,
    val videoDownloadTreeUri: String? = null,
    val audioDownloadTreeUri: String? = null,
    val commentOnlyAuthCookie: String? = null,
    val watchHistoryEnabled: Boolean = true,
    val resumePlaybackEnabled: Boolean = true,
    val rememberListPositions: Boolean = true,
    val searchHistoryEnabled: Boolean = true,
    val showComments: Boolean = true,
    val sponsorBlockEnabled: Boolean = true,
    val selectedCategoryIds: Set<String> = emptySet(),
    val customCategories: Set<String> = emptySet(), // stored as "id::label::seedQuery"
    val repeatCooldown: ShortRepeatCooldown = ShortRepeatCooldown.SEVEN_DAYS,
    val seenShortsTimestamps: Map<String, Long> = emptyMap(),
    val preferredAudioLanguage: String = "en",
    val defaultDownloadQuality: String = "BEST_AVAILABLE",
    val playerGesturesEnabled: Boolean = true,
    val sbSkipCategories: Set<String> = setOf("sponsor", "selfpromo", "interaction", "intro", "outro")
)

private val Context.dataStore: androidx.datastore.core.DataStore<Preferences> by preferencesDataStore(name = "user_settings")

class UserSettingsRepository(private val context: Context) {
    private object PreferencesKeys {
        val MOBILE_STREAM_HEIGHT = intPreferencesKey("mobile_stream_height")
        val WIFI_STREAM_HEIGHT = intPreferencesKey("wifi_stream_height")
        val MOBILE_DOWNLOAD_HEIGHT = intPreferencesKey("mobile_download_height")
        val WIFI_DOWNLOAD_HEIGHT = intPreferencesKey("wifi_download_height")
        val ALLOW_AV1 = booleanPreferencesKey("allow_av1")
        val PREFER_ORIGINAL_AUDIO = booleanPreferencesKey("prefer_original_audio")
        val PIP_ENABLED = booleanPreferencesKey("pip_enabled")
        val BACKGROUND_PLAY = booleanPreferencesKey("background_play")
        val MINIMIZE_BEHAVIOR = stringPreferencesKey("minimize_behavior")
        val SPONSOR_BLOCK_ENABLED = booleanPreferencesKey("sponsor_block_enabled")
        val SELECTED_CATEGORIES = stringSetPreferencesKey("selected_category_ids")
        val CUSTOM_CATEGORIES = stringSetPreferencesKey("custom_categories")
        val REPEAT_COOLDOWN = stringPreferencesKey("shorts_repeat_cooldown")
        val SEEN_SHORTS = stringSetPreferencesKey("seen_shorts_timestamps")
        val VIDEO_DOWNLOAD_URI = stringPreferencesKey("video_download_uri")
        val AUDIO_DOWNLOAD_URI = stringPreferencesKey("audio_download_uri")
        val WATCH_HISTORY = booleanPreferencesKey("watch_history")
        val PREFERRED_AUDIO_LANGUAGE = stringPreferencesKey("preferred_audio_language")
        val DEFAULT_DOWNLOAD_QUALITY = stringPreferencesKey("default_download_quality")
        val PLAYER_GESTURES_ENABLED = booleanPreferencesKey("player_gestures_enabled")
    }

    val settingsFlow: Flow<UserSettings> = context.dataStore.data.map { prefs ->
        val cooldownName = prefs[PreferencesKeys.REPEAT_COOLDOWN] ?: ShortRepeatCooldown.SEVEN_DAYS.name
        val cooldown = runCatching { ShortRepeatCooldown.valueOf(cooldownName) }.getOrDefault(ShortRepeatCooldown.SEVEN_DAYS)
        val seenMap = (prefs[PreferencesKeys.SEEN_SHORTS] ?: emptySet()).mapNotNull { entry ->
            val parts = entry.split(":")
            if (parts.size == 2) parts[0] to (parts[1].toLongOrNull() ?: 0L) else null
        }.toMap()

        UserSettings(
            mobileStreamMaxHeight = prefs[PreferencesKeys.MOBILE_STREAM_HEIGHT] ?: 480,
            wifiStreamMaxHeight = prefs[PreferencesKeys.WIFI_STREAM_HEIGHT] ?: 1080,
            mobileDownloadMaxHeight = prefs[PreferencesKeys.MOBILE_DOWNLOAD_HEIGHT] ?: 480,
            wifiDownloadMaxHeight = prefs[PreferencesKeys.WIFI_DOWNLOAD_HEIGHT] ?: 1080,
            allowAv1 = prefs[PreferencesKeys.ALLOW_AV1] ?: true,
            preferOriginalAudio = prefs[PreferencesKeys.PREFER_ORIGINAL_AUDIO] ?: true,
            pipEnabled = prefs[PreferencesKeys.PIP_ENABLED] ?: true,
            backgroundPlayEnabled = prefs[PreferencesKeys.BACKGROUND_PLAY] ?: true,
            minimizeBehavior = prefs[PreferencesKeys.MINIMIZE_BEHAVIOR]?.let { runCatching { MinimizeBehavior.valueOf(it) }.getOrNull() } ?: MinimizeBehavior.POPUP_PLAYER,
            sponsorBlockEnabled = prefs[PreferencesKeys.SPONSOR_BLOCK_ENABLED] ?: true,
            selectedCategoryIds = prefs[PreferencesKeys.SELECTED_CATEGORIES] ?: emptySet(),
            customCategories = prefs[PreferencesKeys.CUSTOM_CATEGORIES] ?: emptySet(),
            repeatCooldown = cooldown,
            seenShortsTimestamps = seenMap,
            videoDownloadTreeUri = prefs[PreferencesKeys.VIDEO_DOWNLOAD_URI],
            audioDownloadTreeUri = prefs[PreferencesKeys.AUDIO_DOWNLOAD_URI],
            watchHistoryEnabled = prefs[PreferencesKeys.WATCH_HISTORY] ?: true,
            preferredAudioLanguage = prefs[PreferencesKeys.PREFERRED_AUDIO_LANGUAGE] ?: "en",
            defaultDownloadQuality = prefs[PreferencesKeys.DEFAULT_DOWNLOAD_QUALITY] ?: "BEST_AVAILABLE",
            playerGesturesEnabled = prefs[PreferencesKeys.PLAYER_GESTURES_ENABLED] ?: true
        )
    }

    suspend fun updatePlayerGesturesEnabled(enabled: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.PLAYER_GESTURES_ENABLED] = enabled }
    }

    suspend fun updatePreferredAudioLanguage(languageCode: String) {
        context.dataStore.edit { it[PreferencesKeys.PREFERRED_AUDIO_LANGUAGE] = languageCode }
    }

    suspend fun updateDefaultDownloadQuality(qualityName: String) {
        context.dataStore.edit { it[PreferencesKeys.DEFAULT_DOWNLOAD_QUALITY] = qualityName }
    }

    suspend fun updateWifiStreamHeight(height: Int) {
        context.dataStore.edit { it[PreferencesKeys.WIFI_STREAM_HEIGHT] = height }
    }

    suspend fun updateMobileStreamHeight(height: Int) {
        context.dataStore.edit { it[PreferencesKeys.MOBILE_STREAM_HEIGHT] = height }
    }

    suspend fun updateWifiDownloadHeight(height: Int) {
        context.dataStore.edit { it[PreferencesKeys.WIFI_DOWNLOAD_HEIGHT] = height }
    }

    suspend fun updateMobileDownloadHeight(height: Int) {
        context.dataStore.edit { it[PreferencesKeys.MOBILE_DOWNLOAD_HEIGHT] = height }
    }

    suspend fun updateSponsorBlock(enabled: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.SPONSOR_BLOCK_ENABLED] = enabled }
    }

    suspend fun updateSelectedCategories(categoryIds: Set<String>) {
        context.dataStore.edit { it[PreferencesKeys.SELECTED_CATEGORIES] = categoryIds }
    }

    suspend fun updateCustomCategories(customs: Set<String>) {
        context.dataStore.edit { it[PreferencesKeys.CUSTOM_CATEGORIES] = customs }
    }

    suspend fun updateRepeatCooldown(cooldown: ShortRepeatCooldown) {
        context.dataStore.edit { it[PreferencesKeys.REPEAT_COOLDOWN] = cooldown.name }
    }

    suspend fun recordShortViewed(videoId: String) {
        val now = System.currentTimeMillis()
        val cutoff = now - (60L * 24 * 60 * 60 * 1000) // Keep 60 days of history
        context.dataStore.edit { prefs ->
            val existing = (prefs[PreferencesKeys.SEEN_SHORTS] ?: emptySet())
                .filter { entry ->
                    val ts = entry.substringAfter(":", "0").toLongOrNull() ?: 0L
                    val id = entry.substringBefore(":")
                    id != videoId && ts > cutoff
                }
                .toMutableSet()
            existing.add("$videoId:$now")
            prefs[PreferencesKeys.SEEN_SHORTS] = existing
        }
    }

    suspend fun updateDownloadUris(videoUri: String?, audioUri: String?) {
        context.dataStore.edit { prefs ->
            if (videoUri != null) prefs[PreferencesKeys.VIDEO_DOWNLOAD_URI] = videoUri
            if (audioUri != null) prefs[PreferencesKeys.AUDIO_DOWNLOAD_URI] = audioUri
        }
    }
}
