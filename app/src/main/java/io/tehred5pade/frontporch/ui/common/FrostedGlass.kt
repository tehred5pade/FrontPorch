package io.tehred5pade.frontporch.ui.common

import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.hazeEffect

/**
 * The app's single frosted-glass recipe. Used by the tab bar and by floating controls so
 * their transparency, blur, tint and shadow are guaranteed to match.
 */
@Composable
fun Modifier.frostedGlass(
    hazeState: HazeState?,
    glassStyle: HazeStyle?,
    shape: Shape,
    elevation: Dp = 10.dp
): Modifier {
    val container = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.62f)
    val frost = if (hazeState != null && glassStyle != null) {
        Modifier.hazeEffect(state = hazeState, style = glassStyle)
    } else {
        Modifier
    }
    return this
        .shadow(
            elevation = elevation,
            shape = shape,
            ambientColor = Color.Black.copy(alpha = 0.45f),
            spotColor = Color.Black.copy(alpha = 0.45f)
        )
        .clip(shape)
        .then(frost)
        .background(container)
}
