package io.tehred5pade.frontporch.ui.common

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle

/**
 * A compact menu drawn in the normal composition instead of a platform popup window.
 * This keeps back swipes inside the app's back-priority chain.
 */
@Composable
fun InlineMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
    glassStyle: HazeStyle? = null,
    shape: Shape = RoundedCornerShape(12.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    if (!expanded) return

    BackHandler { onDismiss() }

    val container = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.78f)
    Surface(
        shape = shape,
        color = container,
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
        modifier = modifier
            .widthIn(min = 210.dp)
            .heightIn(max = 320.dp)
    ) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            content = content
        )
    }
}
