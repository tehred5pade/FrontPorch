package io.tehred5pade.frontporch.data.sponsorblock

import android.content.Context
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SponsorSkipBehavior(val label: String) {
    AUTO_SKIP("Auto-Skip"),
    NOTHING("Disabled")
}

enum class SponsorCategory(
    val apiKey: String,
    val title: String,
    val description: String,
    val badgeColor: Color,
    val defaultBehavior: SponsorSkipBehavior
) {
    SPONSOR(
        apiKey = "sponsor",
        title = "Sponsor",
        description = "Paid promotions, referrals, and direct ad reads",
        badgeColor = Color(0xFF00D400),
        defaultBehavior = SponsorSkipBehavior.AUTO_SKIP
    ),
    SELF_PROMO(
        apiKey = "selfpromo",
        title = "Unpaid / Self Promotion",
        description = "Merch, Patreon, or creator's own channel plugs",
        badgeColor = Color(0xFFFFFF00),
        defaultBehavior = SponsorSkipBehavior.AUTO_SKIP
    ),
    INTERACTION(
        apiKey = "interaction",
        title = "Interaction Reminder",
        description = "Reminders to like, subscribe, or follow",
        badgeColor = Color(0xFFCC00FF),
        defaultBehavior = SponsorSkipBehavior.AUTO_SKIP
    ),
    INTRO(
        apiKey = "intro",
        title = "Intermission / Intro",
        description = "Opening animations, title sequences, and pauses",
        badgeColor = Color(0xFF00FFFF),
        defaultBehavior = SponsorSkipBehavior.AUTO_SKIP
    ),
    OUTRO(
        apiKey = "outro",
        title = "Endcards / Credits",
        description = "Closing credits and YouTube end-screen cards",
        badgeColor = Color(0xFF0264ED),
        defaultBehavior = SponsorSkipBehavior.AUTO_SKIP
    ),
    PREVIEW(
        apiKey = "preview",
        title = "Preview / Recap",
        description = "Quick recaps of past videos or upcoming clips",
        badgeColor = Color(0xFF008FD6),
        defaultBehavior = SponsorSkipBehavior.AUTO_SKIP
    ),
    MUSIC_OFFTOPIC(
        apiKey = "music_offtopic",
        title = "Music: Non-Music Section",
        description = "Non-music intros, skits, or talking in music videos",
        badgeColor = Color(0xFFFF9900),
        defaultBehavior = SponsorSkipBehavior.AUTO_SKIP
    ),
    FILLER(
        apiKey = "filler",
        title = "Filler Tangent / Jokes",
        description = "Tangential jokes or scenes added only for filler",
        badgeColor = Color(0xFF7300FF),
        defaultBehavior = SponsorSkipBehavior.AUTO_SKIP
    ),
    POI_HIGHLIGHT(
        apiKey = "poi_highlight",
        title = "Highlight",
        description = "Jump directly to the main point of interest in the video",
        badgeColor = Color(0xFFFF1684),
        defaultBehavior = SponsorSkipBehavior.AUTO_SKIP
    ),
    EXCLUSIVE_ACCESS(
        apiKey = "exclusive_access",
        title = "Exclusive Access",
        description = "Videos showcasing free or early-access products/services",
        badgeColor = Color(0xFF008A5C),
        defaultBehavior = SponsorSkipBehavior.AUTO_SKIP
    );

    companion object {
        fun fromApiKey(key: String): SponsorCategory? = entries.find { it.apiKey == key }
    }
}

object SponsorBlockPreferences {
    private const val PREFS_NAME = "frontporch_sponsorblock_prefs"
    private const val KEY_ENABLED = "sponsorblock_enabled"
    private const val KEY_CAT_PREFIX = "sb_cat_"

    private val _isEnabled = MutableStateFlow(true)
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    private val _categoryBehaviors = MutableStateFlow<Map<SponsorCategory, SponsorSkipBehavior>>(
        SponsorCategory.entries.associateWith { it.defaultBehavior }
    )
    val categoryBehaviors: StateFlow<Map<SponsorCategory, SponsorSkipBehavior>> =
        _categoryBehaviors.asStateFlow()

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _isEnabled.value = prefs.getBoolean(KEY_ENABLED, true)

        val loaded = SponsorCategory.entries.associateWith { cat ->
            val saved = prefs.getString("${KEY_CAT_PREFIX}${cat.apiKey}", null)
            runCatching {
                if (saved != null) SponsorSkipBehavior.valueOf(saved) else cat.defaultBehavior
            }.getOrDefault(cat.defaultBehavior)
        }
        _categoryBehaviors.value = loaded
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
        _isEnabled.value = enabled
    }

    fun setCategoryBehavior(
        context: Context,
        category: SponsorCategory,
        behavior: SponsorSkipBehavior
    ) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("${KEY_CAT_PREFIX}${category.apiKey}", behavior.name)
            .apply()

        _categoryBehaviors.value = _categoryBehaviors.value.toMutableMap().apply {
            this[category] = behavior
        }
    }

    fun setAllCategories(context: Context, behavior: SponsorSkipBehavior) {
        val editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        val updated = mutableMapOf<SponsorCategory, SponsorSkipBehavior>()
        for (cat in SponsorCategory.entries) {
            editor.putString("${KEY_CAT_PREFIX}${cat.apiKey}", behavior.name)
            updated[cat] = behavior
        }
        editor.apply()
        _categoryBehaviors.value = updated
    }
}
