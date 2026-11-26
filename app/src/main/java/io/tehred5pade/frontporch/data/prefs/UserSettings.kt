package io.tehred5pade.frontporch.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
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
    val mobileStreamTargetFps: Int = 0,
    val wifiStreamMaxHeight: Int = 1080,
    val wifiStreamTargetFps: Int = 0,
    val mobileDownloadMaxHeight: Int = 480,
    val mobileDownloadTargetFps: Int = 0,
    val wifiDownloadMaxHeight: Int = 1080,
    val wifiDownloadTargetFps: Int = 0,
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
    val allowHighResContent: Boolean = true,
    val playerGesturesEnabled: Boolean = true,
    val sbSkipCategories: Set<String> = setOf("sponsor", "selfpromo", "interaction", "intro", "outro"),
    // Short feedback is stored as "videoId|channelId|L|D" entries so a channel's dislike
    // count can be derived without a separate database migration.
    val likedShortIds: Set<String> = emptySet(),
    val likedShortChannelIds: Set<String> = emptySet(),
    val dislikedShortIds: Set<String> = emptySet(),
    val dislikedShortChannelCounts: Map<String, Int> = emptyMap(),
    val blockedShortChannelIds: Set<String> = emptySet()
)

private data class ParsedShortFeedback(
    val likedIds: Set<String>,
    val likedChannelIds: Set<String>,
    val dislikedIds: Set<String>,
    val dislikedChannelCounts: Map<String, Int>,
    val blockedChannelIds: Set<String>
)

private fun parseShortFeedback(raw: Set<String>): ParsedShortFeedback {
    val likedIds = HashSet<String>()
    val likedChannelIds = HashSet<String>()
    val dislikedIds = HashSet<String>()
    val dislikedCounts = HashMap<String, Int>()
    for (entry in raw) {
        val parts = entry.split("|")
        if (parts.size != 3) continue
        val (videoId, channelId, flag) = parts
        when (flag) {
            "L" -> {
                likedIds.add(videoId)
                if (channelId.isNotBlank()) likedChannelIds.add(channelId)
            }
            "D" -> {
                dislikedIds.add(videoId)
                if (channelId.isNotBlank()) {
                    dislikedCounts[channelId] = (dislikedCounts[channelId] ?: 0) + 1
                }
            }
        }
    }
    return ParsedShortFeedback(
        likedIds = likedIds,
        likedChannelIds = likedChannelIds,
        dislikedIds = dislikedIds,
        dislikedChannelCounts = dislikedCounts,
        blockedChannelIds = dislikedCounts.filterValues { it >= 2 }.keys.toSet()
    )
}

object UserSettingsDataStore {
    @Volatile
    private var INSTANCE: DataStore<Preferences>? = null

    fun getInstance(context: Context): DataStore<Preferences> {
        return INSTANCE ?: synchronized(this) {
            INSTANCE ?: PreferenceDataStoreFactory.create(
                produceFile = { context.applicationContext.preferencesDataStoreFile("user_settings") }
            ).also { INSTANCE = it }
        }
    }
}

class UserSettingsRepository(private val context: Context) {
    private object PreferencesKeys {
        val MOBILE_STREAM_HEIGHT = intPreferencesKey("mobile_stream_height")
        val MOBILE_STREAM_FPS = intPreferencesKey("mobile_stream_fps")
        val WIFI_STREAM_HEIGHT = intPreferencesKey("wifi_stream_height")
        val WIFI_STREAM_FPS = intPreferencesKey("wifi_stream_fps")
        val MOBILE_DOWNLOAD_HEIGHT = intPreferencesKey("mobile_download_height")
        val MOBILE_DOWNLOAD_FPS = intPreferencesKey("mobile_download_fps")
        val WIFI_DOWNLOAD_HEIGHT = intPreferencesKey("wifi_download_height")
        val WIFI_DOWNLOAD_FPS = intPreferencesKey("wifi_download_fps")
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
        val ALLOW_HIGH_RES_CONTENT = booleanPreferencesKey("allow_high_res_content")
        // Older builds stored this as a download-only key; keep reading it during migration.
        val ALLOW_HIGH_RES_DOWNLOADS_LEGACY = booleanPreferencesKey("allow_high_res_downloads")
        val PLAYER_GESTURES_ENABLED = booleanPreferencesKey("player_gestures_enabled")
        val SHORT_FEEDBACK = stringSetPreferencesKey("short_feedback")
    }

    val settingsFlow: Flow<UserSettings> = UserSettingsDataStore.getInstance(context).data.map { prefs ->
        val cooldownName = prefs[PreferencesKeys.REPEAT_COOLDOWN] ?: ShortRepeatCooldown.SEVEN_DAYS.name
        val cooldown = runCatching { ShortRepeatCooldown.valueOf(cooldownName) }.getOrDefault(ShortRepeatCooldown.SEVEN_DAYS)
        val seenMap = (prefs[PreferencesKeys.SEEN_SHORTS] ?: emptySet()).mapNotNull { entry ->
            val parts = entry.split(":")
            if (parts.size == 2) parts[0] to (parts[1].toLongOrNull() ?: 0L) else null
        }.toMap()
        val feedback = parseShortFeedback(prefs[PreferencesKeys.SHORT_FEEDBACK] ?: emptySet())

        UserSettings(
            mobileStreamMaxHeight = prefs[PreferencesKeys.MOBILE_STREAM_HEIGHT] ?: 480,
            mobileStreamTargetFps = prefs[PreferencesKeys.MOBILE_STREAM_FPS] ?: 0,
            wifiStreamMaxHeight = prefs[PreferencesKeys.WIFI_STREAM_HEIGHT] ?: 1080,
            wifiStreamTargetFps = prefs[PreferencesKeys.WIFI_STREAM_FPS] ?: 0,
            mobileDownloadMaxHeight = prefs[PreferencesKeys.MOBILE_DOWNLOAD_HEIGHT] ?: 480,
            mobileDownloadTargetFps = prefs[PreferencesKeys.MOBILE_DOWNLOAD_FPS] ?: 0,
            wifiDownloadMaxHeight = prefs[PreferencesKeys.WIFI_DOWNLOAD_HEIGHT] ?: 1080,
            wifiDownloadTargetFps = prefs[PreferencesKeys.WIFI_DOWNLOAD_FPS] ?: 0,
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
            allowHighResContent = prefs[PreferencesKeys.ALLOW_HIGH_RES_CONTENT]
                ?: prefs[PreferencesKeys.ALLOW_HIGH_RES_DOWNLOADS_LEGACY]
                ?: true,
            playerGesturesEnabled = prefs[PreferencesKeys.PLAYER_GESTURES_ENABLED] ?: true,
            likedShortIds = feedback.likedIds,
            likedShortChannelIds = feedback.likedChannelIds,
            dislikedShortIds = feedback.dislikedIds,
            dislikedShortChannelCounts = feedback.dislikedChannelCounts,
            blockedShortChannelIds = feedback.blockedChannelIds
        )
    }

    suspend fun updatePlayerGesturesEnabled(enabled: Boolean) {
        UserSettingsDataStore.getInstance(context).edit { it[PreferencesKeys.PLAYER_GESTURES_ENABLED] = enabled }
    }

    suspend fun updatePreferredAudioLanguage(languageCode: String) {
        UserSettingsDataStore.getInstance(context).edit { it[PreferencesKeys.PREFERRED_AUDIO_LANGUAGE] = languageCode }
    }

    /**
     * Turns 2K/4K off for both streaming and downloads. Stored caps above 1080p are clamped so
     * an older preference cannot keep selecting high-res content after the toggle is disabled.
     */
    suspend fun updateAllowHighResContent(enabled: Boolean) {
        UserSettingsDataStore.getInstance(context).edit { prefs ->
            prefs[PreferencesKeys.ALLOW_HIGH_RES_CONTENT] = enabled
            prefs[PreferencesKeys.ALLOW_HIGH_RES_DOWNLOADS_LEGACY] = enabled
            if (!enabled) {
                listOf(
                    PreferencesKeys.WIFI_STREAM_HEIGHT,
                    PreferencesKeys.MOBILE_STREAM_HEIGHT,
                    PreferencesKeys.WIFI_DOWNLOAD_HEIGHT,
                    PreferencesKeys.MOBILE_DOWNLOAD_HEIGHT
                ).forEach { key ->
                    if ((prefs[key] ?: 0) > 1080) prefs[key] = 1080
                }
            }
        }
    }

    suspend fun updateWifiStreamHeight(height: Int) {
        UserSettingsDataStore.getInstance(context).edit { it[PreferencesKeys.WIFI_STREAM_HEIGHT] = height }
    }

    suspend fun updateMobileStreamHeight(height: Int) {
        UserSettingsDataStore.getInstance(context).edit { it[PreferencesKeys.MOBILE_STREAM_HEIGHT] = height }
    }

    suspend fun updateWifiStreamTargetFps(fps: Int) {
        UserSettingsDataStore.getInstance(context).edit { it[PreferencesKeys.WIFI_STREAM_FPS] = fps }
    }

    suspend fun updateMobileStreamTargetFps(fps: Int) {
        UserSettingsDataStore.getInstance(context).edit { it[PreferencesKeys.MOBILE_STREAM_FPS] = fps }
    }

    suspend fun updateWifiDownloadHeight(height: Int) {
        UserSettingsDataStore.getInstance(context).edit { it[PreferencesKeys.WIFI_DOWNLOAD_HEIGHT] = height }
    }

    suspend fun updateWifiDownloadTargetFps(fps: Int) {
        UserSettingsDataStore.getInstance(context).edit { it[PreferencesKeys.WIFI_DOWNLOAD_FPS] = fps }
    }

    suspend fun updateMobileDownloadHeight(height: Int) {
        UserSettingsDataStore.getInstance(context).edit { it[PreferencesKeys.MOBILE_DOWNLOAD_HEIGHT] = height }
    }

    suspend fun updateMobileDownloadTargetFps(fps: Int) {
        UserSettingsDataStore.getInstance(context).edit { it[PreferencesKeys.MOBILE_DOWNLOAD_FPS] = fps }
    }

    suspend fun updateSponsorBlock(enabled: Boolean) {
        UserSettingsDataStore.getInstance(context).edit { it[PreferencesKeys.SPONSOR_BLOCK_ENABLED] = enabled }
    }

    suspend fun updateSelectedCategories(categoryIds: Set<String>) {
        UserSettingsDataStore.getInstance(context).edit { it[PreferencesKeys.SELECTED_CATEGORIES] = categoryIds }
    }

    suspend fun updateCustomCategories(customs: Set<String>) {
        UserSettingsDataStore.getInstance(context).edit { it[PreferencesKeys.CUSTOM_CATEGORIES] = customs }
    }

    suspend fun updateRepeatCooldown(cooldown: ShortRepeatCooldown) {
        UserSettingsDataStore.getInstance(context).edit { it[PreferencesKeys.REPEAT_COOLDOWN] = cooldown.name }
    }

    suspend fun recordShortViewed(videoId: String) {
        val now = System.currentTimeMillis()
        val cutoff = now - (60L * 24 * 60 * 60 * 1000) // Keep 60 days of history
        UserSettingsDataStore.getInstance(context).edit { prefs ->
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

    /** Records a like/dislike, replacing any previous feedback for the same Short. */
    suspend fun setShortFeedback(videoId: String, channelId: String, liked: Boolean) {
        UserSettingsDataStore.getInstance(context).edit { prefs ->
            val existing = (prefs[PreferencesKeys.SHORT_FEEDBACK] ?: emptySet()).toMutableSet()
            existing.removeAll { it.startsWith("$videoId|") }
            existing.add("$videoId|$channelId|${if (liked) "L" else "D"}")
            prefs[PreferencesKeys.SHORT_FEEDBACK] = existing
        }
    }

    suspend fun clearShortFeedback(videoId: String) {
        UserSettingsDataStore.getInstance(context).edit { prefs ->
            prefs[PreferencesKeys.SHORT_FEEDBACK] = (prefs[PreferencesKeys.SHORT_FEEDBACK] ?: emptySet())
                .filterNot { it.startsWith("$videoId|") }.toSet()
        }
    }

    suspend fun updateDownloadUris(videoUri: String?, audioUri: String?) {
        UserSettingsDataStore.getInstance(context).edit { prefs ->
            if (videoUri != null) prefs[PreferencesKeys.VIDEO_DOWNLOAD_URI] = videoUri
            if (audioUri != null) prefs[PreferencesKeys.AUDIO_DOWNLOAD_URI] = audioUri
        }
    }
}
