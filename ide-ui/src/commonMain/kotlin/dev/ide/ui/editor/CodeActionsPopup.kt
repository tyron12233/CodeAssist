package dev.ide.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.UiAction
import dev.ide.ui.backend.UiActionKind
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca

/**
 * The editor code-action affordances (intentions + quick-fixes), styled like the completion popup:
 *  - [ActionLightbulb] — the gutter glyph shown on the caret line when actions exist; click to open.
 *  - [CodeActionsMenu] — the popup list. Operable by click and by keyboard (the editor drives ↑↓/⏎/Esc).
 *
 * Both are pure UI over the neutral [UiAction] DTO; all state + the `actionsAt`/`applyAction` round-trip
 * lives in [CodeEditor].
 */
@Composable
fun ActionLightbulb(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(18.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(CaIcons.lightbulb, contentDescription = "Show context actions", tint = Ca.colors.warning, modifier = Modifier.size(15.dp))
    }
}

@Composable
fun CodeActionsMenu(
    actions: List<UiAction>,
    selectedIndex: Int,
    width: Dp,
    onPick: (Int) -> Unit,
    maxListHeight: Dp = 280.dp,
) {
    Column(
        Modifier
            .width(width)
            .background(Ca.colors.glassThick, RoundedCornerShape(Ca.radius.md))
            .border(1.dp, Ca.colors.separator, RoundedCornerShape(Ca.radius.md)),
    ) {
        LazyColumn(modifier = Modifier.heightIn(max = maxListHeight)) {
            itemsIndexed(actions) { index, action ->
                ActionRow(action, index == selectedIndex, onPick = { onPick(index) })
            }
        }
    }
}

@Composable
private fun ActionRow(action: UiAction, selected: Boolean, onPick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(38.dp)
            .background(if (selected) Ca.colors.accentSoft else Color.Transparent)
            .clickable(onClick = onPick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val isFix = action.kind == UiActionKind.QUICK_FIX
        Icon(
            if (isFix) CaIcons.gear else CaIcons.lightbulb,
            contentDescription = null,
            tint = if (isFix) Ca.colors.accent else Ca.colors.warning,
            modifier = Modifier.size(15.dp),
        )
        Text(
            action.title,
            style = Ca.type.code,
            color = Ca.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
