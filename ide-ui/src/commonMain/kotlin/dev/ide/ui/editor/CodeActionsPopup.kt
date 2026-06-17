package dev.ide.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.UiAction
import dev.ide.ui.backend.UiActionKind
import dev.ide.ui.backend.UiSeverity
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
private fun ActionRow(action: UiAction, selected: Boolean, onPick: () -> Unit, height: Dp = 38.dp) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(height)
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

/**
 * A bottom-docked sheet for a single diagnostic: the **full** (selectable, scrollable) message — so a long
 * error is readable on a phone where the inline chip is truncated — plus its quick-fixes as large touch
 * targets. Tapping the scrim or the × dismisses it. Pure UI; the host fetches the [actions] for the
 * diagnostic's range and applies the picked one over the [dev.ide.ui.backend.IdeBackend.applyAction] round-trip.
 */
@Composable
fun DiagnosticSheet(
    severity: UiSeverity,
    unused: Boolean,
    message: String,
    actions: List<UiAction>,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val color = when (severity) {
        UiSeverity.Error -> Ca.colors.error
        UiSeverity.Warning -> if (unused) Ca.colors.textTertiary else Ca.colors.warning
        UiSeverity.Info -> Ca.colors.info
        UiSeverity.Hint -> Ca.colors.textTertiary
    }
    val icon = when (severity) {
        UiSeverity.Error -> CaIcons.error
        UiSeverity.Warning -> CaIcons.warning
        UiSeverity.Info, UiSeverity.Hint -> CaIcons.info
    }
    val label = when (severity) {
        UiSeverity.Error -> "Error"
        UiSeverity.Warning -> if (unused) "Unused" else "Warning"
        UiSeverity.Info -> "Info"
        UiSeverity.Hint -> "Hint"
    }
    val sheetShape = RoundedCornerShape(topStart = Ca.radius.sheet, topEnd = Ca.radius.sheet)
    // scrim over the editor pane (tap to dismiss); panel docked at the bottom for thumb reach
    Box(
        Modifier
            .fillMaxSize()
            .background(Ca.colors.scrim)
            .pointerInput(Unit) { detectTapGestures { onDismiss() } },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(Ca.colors.glassThick, sheetShape)
                .border(1.dp, Ca.colors.separator, sheetShape)
                .pointerInput(Unit) { detectTapGestures { } } // swallow taps so the panel itself doesn't dismiss
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(icon, null, Modifier.size(18.dp), tint = color)
                Text(label, color = color, style = Ca.type.caption, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(0.dp).weight(1f))
                Box(
                    Modifier.size(30.dp).clip(CircleShape).clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) { Icon(CaIcons.close, "Dismiss", Modifier.size(16.dp), tint = Ca.colors.textSecondary) }
            }
            Spacer(Modifier.height(10.dp))
            SelectionContainer {
                Text(
                    message,
                    color = Ca.colors.textPrimary,
                    style = Ca.type.code,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp).verticalScroll(rememberScrollState()),
                )
            }
            if (actions.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(Ca.colors.separator))
                Spacer(Modifier.height(6.dp))
                Text("Quick fixes", color = Ca.colors.textTertiary, style = Ca.type.caption2, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                actions.forEachIndexed { i, a -> ActionRow(a, selected = false, onPick = { onPick(i) }, height = 48.dp) }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
