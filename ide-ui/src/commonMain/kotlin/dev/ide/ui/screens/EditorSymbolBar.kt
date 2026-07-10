package dev.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.symbolbar_duplicate_line
import dev.ide.ui.generated.resources.symbolbar_move_line_down
import dev.ide.ui.generated.resources.symbolbar_move_line_up
import dev.ide.ui.generated.resources.symbolbar_next_problem
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca
import org.jetbrains.compose.resources.stringResource

/**
 * A keyboard accessory bar of common coding symbols, shown above the soft keyboard while typing on touch
 * (the caller gates it on the IME being visible). A fixed **Tab** key on the left indents; the rest are
 * horizontally-scrollable language symbols that insert at the caret. Single-char inserts go through the
 * editor's smart-insert, so `{`/`(`/`"` auto-close exactly like typing them.
 *
 * Keys use a raw pointer tap (NOT `clickable`, which would take focus): the editor must keep focus so the
 * keyboard — and its input connection — stays open while you punch in symbols.
 */
@Composable
internal fun EditorSymbolBar(
    onTab: () -> Unit,
    onSymbol: (String) -> Unit,
    modifier: Modifier = Modifier,
    onComment: () -> Unit = {},
    onMoveLineUp: () -> Unit = {},
    onMoveLineDown: () -> Unit = {},
    onDuplicateLine: () -> Unit = {},
    showDiagnosticJump: Boolean = false,
    onNextDiagnostic: () -> Unit = {},
) {
    val separator = Ca.colors.separator // captured for the draw lambda (can't read the theme inside drawBehind)
    Row(
        modifier
            .fillMaxWidth()
            .height(38.dp)
            .background(Ca.colors.surface2)
            .drawBehind { // hairline separating the bar from the editor above
                drawLine(separator, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1f)
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SymbolKey("Tab", onClick = onTab, modifier = Modifier.width(48.dp), accent = true)
        Box(Modifier.width(1.dp).fillMaxHeight().background(Ca.colors.separator))
        // Fixed line-action group: comment toggle, move line up/down, duplicate — the editor ops that are
        // otherwise keyboard-only, surfaced for touch.
        SymbolKey("//", onClick = onComment)
        IconKey(CaIcons.chevronUp, stringResource(Res.string.symbolbar_move_line_up), onClick = onMoveLineUp)
        IconKey(CaIcons.chevronDown, stringResource(Res.string.symbolbar_move_line_down), onClick = onMoveLineDown)
        IconKey(CaIcons.copy, stringResource(Res.string.symbolbar_duplicate_line), onClick = onDuplicateLine)
        // Jump to the next diagnostic — shown only while the file has any (otherwise it's noise).
        if (showDiagnosticJump) IconKey(CaIcons.warning, stringResource(Res.string.symbolbar_next_problem), onClick = onNextDiagnostic)
        Box(Modifier.width(1.dp).fillMaxHeight().background(Ca.colors.separator))
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (s in SYMBOLS) SymbolKey(s, onClick = { onSymbol(s) })
        }
    }
}

// Commonly-used Java/Kotlin/XML symbols, ordered roughly by how often they're reached for.
private val SYMBOLS = listOf(
    "{", "}", "(", ")", ";", "=", ".", ",", "\"", "'", ":", "<", ">", "/", "*",
    "[", "]", "+", "-", "&", "|", "!", "?", "@", "#", "_", "%", "\\",
)

@Composable
private fun SymbolKey(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, accent: Boolean = false) {
    Box(
        modifier
            .fillMaxHeight()
            .widthIn(min = 36.dp)
            // Raw tap, no `clickable`: a clickable would request focus and dismiss the keyboard.
            .pointerInput(label) { detectTapGestures { onClick() } }
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = Ca.type.codeSmall,
            color = if (accent) Ca.colors.accent else Ca.colors.textPrimary,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** An icon variant of [SymbolKey] for the line-action group (raw tap, no `clickable`, to keep editor focus). */
@Composable
private fun IconKey(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxHeight()
            .widthIn(min = 36.dp)
            .pointerInput(label) { detectTapGestures { onClick() } }
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, Modifier.size(18.dp), tint = Ca.colors.textPrimary)
    }
}
