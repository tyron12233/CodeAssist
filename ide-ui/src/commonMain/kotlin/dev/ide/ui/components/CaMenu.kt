package dev.ide.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import dev.ide.ui.theme.Ca

/**
 * The app's dropdown menu: a softly-rounded, hairline-bordered panel on the elevated surface with a gentle
 * shadow — replacing Material's squared default so every popup matches the bespoke design language. A drop-in
 * for [DropdownMenu] (the [containerColor] is clipped to the rounded [shape], so call sites no longer need the
 * old `Modifier.background(...)` hack). [offset] nudges the menu off its anchor.
 */
@Composable
fun CaDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 6.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        offset = offset,
        shape = RoundedCornerShape(Ca.radius.md),
        containerColor = Ca.colors.surface2,
        tonalElevation = 0.dp,
        shadowElevation = 14.dp,
        border = BorderStroke(1.dp, Ca.colors.glassEdge),
        content = content,
    )
}
