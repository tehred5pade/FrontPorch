package io.tehred5pade.frontporch.ui.theme

import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint

enum class AppBackgroundTheme(
    val label: String,
    val subtitle: String,
    val isDark: Boolean
) {
    LIGHT("Light", "Clean white background with deep text", false),
    SOFT_IVORY("Soft Ivory", "Warm off-white background with deep text", false),
    DARK("Dark", "Deep navy background with light text", true),
    AMOLED("AMOLED", "Pure black background with light text", true)
}

enum class AppHighlightColor(val label: String, val color: Color) {
    RED("Red", Color(0xFFE53935)),
    DARK_RED("Dark Red", Color(0xFFB71C1C)),
    BLUE("Blue", Color(0xFF1E88E5)),
    DARK_BLUE("Dark Blue", Color(0xFF0D47A1)),
    CYAN("Cyan", Color(0xFF00BCD4)),
    GREEN("Green", Color(0xFF43A047)),
    FOREST_GREEN("Forest Green", Color(0xFF2E7D32)),
    SLATE("Slate", Color(0xFF546E7A)),
    GRAY("Gray", Color(0xFF757575)),
    CHOCOLATE_BROWN("Chocolate Brown", Color(0xFF5D4037)),
    PURPLE("Purple", Color(0xFF8E24AA)),
    PINK("Pink", Color(0xFFD81B60))
}

private data class BackgroundPalette(
    val background: Color,
    val onBackground: Color,
    val surface: Color,
    val onSurface: Color,
    val surfaceVariant: Color,
    val onSurfaceVariant: Color,
    val surfaceContainer: Color,
    val surfaceContainerHigh: Color,
    val outline: Color,
    val outlineVariant: Color,
    val isDark: Boolean
)

private val LightPalette = BackgroundPalette(
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF0F1422),
    surface = Color(0xFFF6F8FC),
    onSurface = Color(0xFF0F1422),
    surfaceVariant = Color(0xFFE9EEF7),
    onSurfaceVariant = Color(0xFF434C63),
    surfaceContainer = Color(0xFFF0F4FA),
    surfaceContainerHigh = Color(0xFFE5EBF5),
    outline = Color(0xFF0F1422).copy(alpha = 0.16f),
    outlineVariant = Color(0xFF0F1422).copy(alpha = 0.10f),
    isDark = false
)

private val SoftIvoryPalette = BackgroundPalette(
    background = Color(0xFFFFF8EE),
    onBackground = Color(0xFF2B241C),
    surface = Color(0xFFFFFCF6),
    onSurface = Color(0xFF2B241C),
    surfaceVariant = Color(0xFFF3E9D8),
    onSurfaceVariant = Color(0xFF5C5143),
    surfaceContainer = Color(0xFFFBF3E7),
    surfaceContainerHigh = Color(0xFFF2E6D4),
    outline = Color(0xFF2B241C).copy(alpha = 0.16f),
    outlineVariant = Color(0xFF2B241C).copy(alpha = 0.10f),
    isDark = false
)

private val DarkPalette = BackgroundPalette(
    background = Color(0xFF0B0E17),
    onBackground = Color.White,
    surface = Color(0xFF0F131E),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF171D2D),
    onSurfaceVariant = Color(0xFFB8C1D6),
    surfaceContainer = Color(0xFF121724),
    surfaceContainerHigh = Color(0xFF1A2133),
    outline = Color.White.copy(alpha = 0.16f),
    outlineVariant = Color.White.copy(alpha = 0.10f),
    isDark = true
)

private val AmoledPalette = BackgroundPalette(
    background = Color(0xFF000000),
    onBackground = Color.White,
    surface = Color(0xFF070709),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF121318),
    onSurfaceVariant = Color(0xFFB0B5C0),
    surfaceContainer = Color(0xFF0D0E12),
    surfaceContainerHigh = Color(0xFF161820),
    outline = Color.White.copy(alpha = 0.15f),
    outlineVariant = Color.White.copy(alpha = 0.09f),
    isDark = true
)

private fun paletteFor(theme: AppBackgroundTheme): BackgroundPalette = when (theme) {
    AppBackgroundTheme.LIGHT -> LightPalette
    AppBackgroundTheme.SOFT_IVORY -> SoftIvoryPalette
    AppBackgroundTheme.DARK -> DarkPalette
    AppBackgroundTheme.AMOLED -> AmoledPalette
}

private fun blend(from: Color, to: Color, amount: Float): Color {
    val inverse = 1f - amount
    return Color(
        alpha = 1f,
        red = from.red * inverse + to.red * amount,
        green = from.green * inverse + to.green * amount,
        blue = from.blue * inverse + to.blue * amount
    )
}

private fun onColorFor(accent: Color): Color {
    val luminance = accent.red * 0.299f + accent.green * 0.587f + accent.blue * 0.114f
    return if (luminance > 0.58f) Color(0xFF141414) else Color.White
}

private fun colorSchemeFor(
    background: AppBackgroundTheme,
    highlight: AppHighlightColor
): ColorScheme {
    val palette = paletteFor(background)
    val base = if (palette.isDark) darkColorScheme() else lightColorScheme()
    val accent = highlight.color
    val accentOn = onColorFor(accent)
    val accentContainer = blend(
        from = palette.surfaceContainerHigh,
        to = accent,
        amount = if (palette.isDark) 0.26f else 0.20f
    )
    val accentOnContainer = if (palette.isDark) Color.White else Color(0xFF141414)

    return base.copy(
        primary = accent,
        onPrimary = accentOn,
        primaryContainer = accentContainer,
        onPrimaryContainer = accentOnContainer,
        secondary = accent,
        onSecondary = accentOn,
        secondaryContainer = accentContainer,
        onSecondaryContainer = accentOnContainer,
        tertiary = accent,
        onTertiary = accentOn,
        tertiaryContainer = accentContainer,
        onTertiaryContainer = accentOnContainer,
        surfaceTint = accent,
        background = palette.background,
        onBackground = palette.onBackground,
        surface = palette.surface,
        onSurface = palette.onSurface,
        surfaceVariant = palette.surfaceVariant,
        onSurfaceVariant = palette.onSurfaceVariant,
        surfaceContainerLowest = palette.background,
        surfaceContainerLow = blend(palette.background, palette.surfaceContainer, 0.55f),
        surfaceContainer = palette.surfaceContainer,
        surfaceContainerHigh = palette.surfaceContainerHigh,
        surfaceContainerHighest = blend(palette.surfaceContainerHigh, palette.onSurface, 0.08f),
        outline = palette.outline,
        outlineVariant = palette.outlineVariant
    )
}

class ThemePreferences(context: Context) {
    private val prefs = context.getSharedPreferences("frontporch_theme_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_BACKGROUND = "background_theme"
        private const val KEY_HIGHLIGHT = "highlight_color"
        private const val LEGACY_THEME_MODE = "theme_mode"

        private val DEFAULT_BACKGROUND = AppBackgroundTheme.DARK
        private val DEFAULT_HIGHLIGHT = AppHighlightColor.RED
    }

    fun getBackgroundTheme(): AppBackgroundTheme {
        val raw = prefs.getString(KEY_BACKGROUND, null)
        if (raw != null) {
            return runCatching { AppBackgroundTheme.valueOf(raw) }
                .getOrDefault(DEFAULT_BACKGROUND)
        }
        return migrateLegacyBackground()
    }

    fun getHighlightColor(): AppHighlightColor {
        val raw = prefs.getString(KEY_HIGHLIGHT, null)
        if (raw != null) {
            return runCatching { AppHighlightColor.valueOf(raw) }
                .getOrDefault(DEFAULT_HIGHLIGHT)
        }
        return DEFAULT_HIGHLIGHT
    }

    fun setBackgroundTheme(theme: AppBackgroundTheme) {
        prefs.edit().putString(KEY_BACKGROUND, theme.name).apply()
    }

    fun setHighlightColor(color: AppHighlightColor) {
        prefs.edit().putString(KEY_HIGHLIGHT, color.name).apply()
    }

    private fun migrateLegacyBackground(): AppBackgroundTheme {
        val legacy = prefs.getString(LEGACY_THEME_MODE, null) ?: return DEFAULT_BACKGROUND
        val migrated = when (legacy) {
            "PORCH_LIGHT" -> AppBackgroundTheme.LIGHT
            "AMOLED_DARK" -> AppBackgroundTheme.AMOLED
            "MIDNIGHT_BLUE", "SYSTEM" -> AppBackgroundTheme.DARK
            else -> DEFAULT_BACKGROUND
        }
        prefs.edit()
            .putString(KEY_BACKGROUND, migrated.name)
            .putString(KEY_HIGHLIGHT, DEFAULT_HIGHLIGHT.name)
            .apply()
        return migrated
    }
}

/**
 * One shared frosted-glass recipe for the search bar, mini-player, and tab bar.
 */
@Composable
fun rememberMusicastGlassStyle(): HazeStyle {
    val scheme = MaterialTheme.colorScheme
    return remember(scheme) {
        HazeStyle(
            backgroundColor = scheme.surfaceContainer,
            tints = listOf(HazeTint(scheme.surfaceContainer.copy(alpha = 0.62f))),
            blurRadius = 26.dp,
            noiseFactor = 0.03f
        )
    }
}

@Composable
fun FrontPorchTheme(
    backgroundTheme: AppBackgroundTheme,
    highlightColor: AppHighlightColor,
    content: @Composable () -> Unit
) {
    val colorScheme = remember(backgroundTheme, highlightColor) {
        colorSchemeFor(backgroundTheme, highlightColor)
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
