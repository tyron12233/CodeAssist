package dev.ide.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupPositionProvider
import dev.ide.ui.backend.UiCompletionItem
import dev.ide.ui.backend.UiSeverity
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca

// Editor overlay chrome: the small Compose surfaces that float over the canvas (rename prompt, inline
// diagnostic chip, selection toolbar) plus the popup position providers. Stateless leaves that take their
// data + callbacks from CodeEditor — moved out so the canvas file stays focused on the editor itself.

/** What the rename prompt is editing: where the caret was, the symbol's old name + kind, and the typed name. */
internal data class RenameUiState(val offset: Int, val oldName: String, val kind: String, val newName: String)

/** The last good completion render state, latched so the popup window survives a keystroke's transient gaps. */
internal data class ShownCompletion(val tokenStart: Int, val items: List<UiCompletionItem>, val prefix: String)

/** A centered prompt for the new identifier; Enter renames, Esc cancels. Auto-focused, with the name selected. */
@Composable
internal fun RenamePopup(
    state: RenameUiState,
    busy: Boolean,
    error: String?,
    onChange: (String) -> Unit,
    onCommit: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }
    // Prefill with the old name, fully selected, so typing replaces it (the IntelliJ rename feel).
    var field by remember { mutableStateOf(TextFieldValue(state.newName, TextRange(0, state.newName.length))) }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column(
        modifier.padding(top = 48.dp).width(320.dp)
            .background(Ca.colors.glassThick, RoundedCornerShape(Ca.radius.lg))
            .border(1.dp, Ca.colors.glassEdge, RoundedCornerShape(Ca.radius.lg))
            .padding(16.dp),
    ) {
        Text("Rename ${state.kind}", color = Ca.colors.textSecondary, style = Ca.type.caption, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.size(8.dp))
        Box(
            Modifier.fillMaxWidth().background(Ca.colors.surface2, RoundedCornerShape(Ca.radius.control))
                .border(1.dp, if (error != null) Ca.colors.error else Ca.colors.hairline, RoundedCornerShape(Ca.radius.control))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            BasicTextField(
                value = field,
                onValueChange = { field = it; onChange(it.text) },
                singleLine = true,
                enabled = !busy,
                textStyle = Ca.type.body.copy(color = Ca.colors.textPrimary, fontFamily = Ca.type.codeFamily),
                cursorBrush = SolidColor(Ca.colors.accent),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).onPreviewKeyEvent { ev ->
                    if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (ev.key) {
                        Key.Enter -> { onCommit(); true }
                        Key.Escape -> { onCancel(); true }
                        else -> false
                    }
                },
            )
        }
        if (error != null) {
            Spacer(Modifier.size(6.dp))
            Text(error, color = Ca.colors.error, style = Ca.type.caption2)
        }
        Spacer(Modifier.size(6.dp))
        Text(if (busy) "Renaming…" else "Enter to rename '${state.oldName}', Esc to cancel",
            color = Ca.colors.textTertiary, style = Ca.type.caption2)
    }
}

/** The inline diagnostic chip: a pill at the right of a diagnostic line — severity-tinted fill, icon,
 *  message. Colour/icon follow [severity]; an [unused] warning is muted rather than alarming. */
@Composable
internal fun DiagnosticChip(severity: UiSeverity, unused: Boolean, message: String, onClick: () -> Unit, modifier: Modifier) {
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
    Row(
        modifier
            .background(color.copy(alpha = 0.16f), RoundedCornerShape(Ca.radius.pill))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(icon, null, Modifier.size(13.dp), tint = color)
        Text(
            message,
            color = color,
            fontSize = 11.5f.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun SelectionToolbar(
    hasSelection: Boolean,
    hasActions: Boolean,
    onActions: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onPaste: () -> Unit,
    onSelectAll: () -> Unit,
) {
    Row(
        Modifier
            .background(Ca.colors.surface2, RoundedCornerShape(Ca.radius.sm))
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (hasSelection) {
            ToolbarAction("Copy", onCopy)
            ToolbarAction("Cut", onCut)
        }
        ToolbarAction("Paste", onPaste)
        ToolbarAction("Select all", onSelectAll)
        // Quick-fixes / intentions for the caret position, when any exist.
        if (hasActions) {
            Box(
                Modifier
                    .clickable(onClick = onActions)
                    .padding(horizontal = 9.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(CaIcons.lightbulb, "Quick actions", Modifier.size(16.dp), tint = Ca.colors.warning)
            }
        }
    }
}

@Composable
private fun ToolbarAction(label: String, onClick: () -> Unit) {
    Text(
        label,
        color = Ca.colors.textPrimary,
        style = Ca.type.caption,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 7.dp),
    )
}

// ---- popup positioning ----

/** Floor for the list so it stays usable (≈1.5 rows) even when the caret is near the pane's bottom. */
internal val MinListHeight = 64.dp

/** Generous ceiling for the list: it otherwise fills the room below the caret (re-expanding as the user
 *  scrolls), so this only keeps it sane on a tall desktop window. */
internal val MaxListHeight = 560.dp

/**
 * Positions the completion popup just below the caret line and clamps it horizontally so it never
 * overflows the window. [anchorX]/[lineBottom] are in the editor pane's coordinate space.
 */
internal class CompletionPopupPositionProvider(
    private val anchorX: Int,
    private val lineBottom: Int,
    private val gapPx: Int,
    private val marginPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val maxX = (windowSize.width - popupContentSize.width - marginPx).coerceAtLeast(marginPx)
        val x = (anchorBounds.left + anchorX).coerceIn(marginPx, maxX)
        val y = anchorBounds.top + lineBottom + gapPx
        return IntOffset(x, y)
    }
}

/** Positions the selection toolbar centered above an anchor point in the pane's coordinate space. */
internal class AboveAnchorPositionProvider(
    private val anchorX: Int,
    private val anchorTop: Int,
    private val gapPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = (anchorBounds.left + anchorX - popupContentSize.width / 2)
            .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val y = (anchorBounds.top + anchorTop - popupContentSize.height - gapPx).coerceAtLeast(0)
        return IntOffset(x, y)
    }
}
