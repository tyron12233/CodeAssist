package dev.ide.ui.screens

import dev.ide.ui.theme.Ide
import androidx.compose.material3.MaterialTheme
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
import dev.ide.ui.backend.CustomizationActions
import dev.ide.ui.backend.UiSymbolKey
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.symbolbar_customize
import dev.ide.ui.generated.resources.symbolbar_next_problem
import dev.ide.ui.icons.CaIcons
import org.jetbrains.compose.resources.stringResource

/**
 * A keyboard accessory bar, shown above the soft keyboard while typing on touch (the caller gates it on the
 * IME being visible). Fully data-driven from the effective customization set: [symbols] carries every key in
 * order, each either a **text key** (inserts [UiSymbolKey.insert] via smart-insert, so `{`/`(`/`"` auto-close)
 * or an **action key** ([UiSymbolKey.action], a built-in editor op dispatched through [onAction] — Tab, comment,
 * move/duplicate line). [UiSymbolKey.pinned] keys render in the fixed left group; the rest scroll horizontally.
 * A contextual next-problem key is appended when [showDiagnosticJump]; a trailing gear opens the editor.
 *
 * Keys use a raw pointer tap (NOT `clickable`, which would take focus): the editor must keep focus so the
 * keyboard — and its input connection — stays open while you punch in symbols.
 */
@Composable
internal fun EditorSymbolBar(
    onSymbol: (String) -> Unit,
    onAction: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** Every key in order — supplied by the host from the effective customization set. Defaults to the shipped
     *  [DEFAULT_SYMBOL_KEYS] so the bar renders standalone (preview / snapshot). */
    symbols: List<UiSymbolKey> = DEFAULT_SYMBOL_KEYS,
    /** Append the contextual "next problem" key (shown only while the file has diagnostics). */
    showDiagnosticJump: Boolean = false,
    /** Opens the Symbols & Macros editor. Null hides the trailing customize (gear) key. */
    onCustomize: (() -> Unit)? = null,
) {
    val separator = MaterialTheme.colorScheme.outlineVariant // captured for the draw lambda (can't read the theme inside drawBehind)
    val pinned = symbols.filter { it.pinned }
    val scrolling = symbols.filter { !it.pinned }
    Row(
        modifier
            .fillMaxWidth()
            .height(38.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .drawBehind { // hairline separating the bar from the editor above
                drawLine(separator, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1f)
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Fixed (pinned) group: Tab / comment / line ops by default, but fully user-customizable.
        for (key in pinned) BarKey(key, onSymbol, onAction)
        // Jump to the next diagnostic — contextual (shown only while the file has any), so it isn't a stored key.
        if (showDiagnosticJump) IconKey(CaIcons.warning, stringResource(Res.string.symbolbar_next_problem), onClick = { onAction(CustomizationActions.NEXT_PROBLEM) })
        if (pinned.isNotEmpty() || showDiagnosticJump) {
            Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
        }
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (key in scrolling) BarKey(key, onSymbol, onAction)
        }
        // Trailing customize key: opens the Symbols & Macros editor. Pinned (not in the scroll) so it's always
        // reachable. Opening it dismisses the keyboard (the editor is a full sheet) — intended.
        if (onCustomize != null) {
            Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
            IconKey(CaIcons.gear, stringResource(Res.string.symbolbar_customize), onClick = onCustomize)
        }
    }
}

/** Renders one bar key: an action key as its mapped icon (or an accent text label — Tab, `//`), a text key as
 *  its label. The label is what shows; a text key commits [UiSymbolKey.insert]. */
@Composable
private fun BarKey(key: UiSymbolKey, onSymbol: (String) -> Unit, onAction: (String) -> Unit) {
    val action = key.action
    if (action != null) {
        val icon = symbolActionIcon(action)
        if (icon != null) IconKey(icon, key.label, onClick = { onAction(action) })
        else SymbolKey(key.label, onClick = { onAction(action) }, accent = action == CustomizationActions.TAB)
    } else {
        SymbolKey(key.label, onClick = { onSymbol(key.insert) })
    }
}

/** The icon for an action key, or null to render its label as text (Tab, the `//` comment key, unknown ids). */
private fun symbolActionIcon(action: String): ImageVector? = when (action) {
    CustomizationActions.MOVE_LINE_UP -> CaIcons.chevronUp
    CustomizationActions.MOVE_LINE_DOWN -> CaIcons.chevronDown
    CustomizationActions.DUPLICATE_LINE -> CaIcons.copy
    CustomizationActions.NEXT_PROBLEM -> CaIcons.warning
    else -> null
}

/** The shipped default symbol-bar keys (mirrors `ide-core`'s `DefaultCustomizations.SYMBOLS`) — the fallback
 *  when the host supplies none, and the standalone default for previews/snapshots. */
internal val DEFAULT_SYMBOL_KEYS: List<UiSymbolKey> = buildList {
    add(UiSymbolKey("Tab", "", pinned = true, action = CustomizationActions.TAB))
    add(UiSymbolKey("//", "", pinned = true, action = CustomizationActions.COMMENT))
    add(UiSymbolKey("Move line up", "", pinned = true, action = CustomizationActions.MOVE_LINE_UP))
    add(UiSymbolKey("Move line down", "", pinned = true, action = CustomizationActions.MOVE_LINE_DOWN))
    add(UiSymbolKey("Duplicate line", "", pinned = true, action = CustomizationActions.DUPLICATE_LINE))
    addAll(
        listOf(
            "{", "}", "(", ")", ";", "=", ".", ",", "\"", "'", ":", "<", ">", "/", "*",
            "[", "]", "+", "-", "&", "|", "!", "?", "@", "#", "_", "%", "\\",
        ).map { UiSymbolKey(it, it) },
    )
}

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
            style = Ide.type.codeSmall,
            color = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
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
        Icon(icon, label, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurface)
    }
}
