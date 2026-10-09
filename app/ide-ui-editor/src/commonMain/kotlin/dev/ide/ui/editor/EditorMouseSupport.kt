package dev.ide.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import dev.ide.ui.backend.UiActionItem
import dev.ide.ui.backend.UiDiagnostic
import dev.ide.ui.backend.UiMenuGroup
import dev.ide.ui.backend.UiMenuNode
import dev.ide.ui.backend.UiQuickDoc
import dev.ide.ui.components.CaDropdownMenu
import dev.ide.ui.components.CaMenuItem
import dev.ide.ui.components.CaSubmenuItem
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.codeaction_show_context_actions
import dev.ide.ui.generated.resources.copy
import dev.ide.ui.generated.resources.edchrome_optimize_imports
import dev.ide.ui.generated.resources.edchrome_reformat_code
import dev.ide.ui.generated.resources.edoverlay_cut
import dev.ide.ui.generated.resources.edoverlay_paste
import dev.ide.ui.generated.resources.edoverlay_quick_documentation
import dev.ide.ui.generated.resources.edoverlay_select_all
import dev.ide.ui.generated.resources.nav_declaration
import dev.ide.ui.generated.resources.nav_go_to
import dev.ide.ui.generated.resources.nav_implementations
import dev.ide.ui.generated.resources.nav_super
import dev.ide.ui.generated.resources.nav_type_declaration
import dev.ide.ui.generated.resources.rename
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import org.jetbrains.compose.resources.stringResource

/** How long the pointer rests on code before its documentation or problem shows, as in IntelliJ. */
internal const val HOVER_DELAY_MS = 600L

/** The grace before a hover popup hides, so the pointer can travel from the code into the popup. */
internal const val HOVER_HIDE_GRACE_MS = 250L

/** Where the mouse rests over the code: the document [offset] under it, or -1 over no text. */
internal data class HoverPoint(val offset: Int)

/** What a resting pointer turned up: a symbol's documentation, or the problems under it. */
internal sealed interface HoverInfo {
    /** The span the pointer may move within without the popup hiding. */
    val start: Int
    val end: Int

    class Doc(val doc: UiQuickDoc, override val start: Int, override val end: Int) : HoverInfo
    class Problem(val diagnostic: UiDiagnostic, override val start: Int, override val end: Int) : HoverInfo
}

/**
 * Mouse-only editor state: what the pointer rests on, the popup that resting produced, and the Ctrl/⌘ link
 * under the pointer. Pointer moves go through [moves], a flow rather than snapshot state, so a moving mouse
 * never recomposes the editor; only a shown or hidden popup does.
 */
@Stable
internal class EditorHoverState {
    val moves = MutableSharedFlow<HoverPoint?>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** The popup the last rest produced, or null. */
    var info by mutableStateOf<HoverInfo?>(null)

    /** While the pointer is inside the popup, which keeps it up after the pointer leaves the code. */
    var popupHovered by mutableStateOf(false)

    /** The identifier under the pointer while Ctrl/⌘ is held: underlined, and the target of a click. */
    var link by mutableStateOf<IntRange?>(null)

    /** Hides the popup at once (a click, a key press, Escape). */
    fun dismiss() {
        info = null
        popupHovered = false
        moves.tryEmit(null)
    }
}

/** The identifier (letters, digits, `_`, `$`) spanning [offset], or null when [offset] is not on one. */
internal fun identifierRangeAt(text: CharSequence, offset: Int): IntRange? {
    if (offset < 0 || offset > text.length) return null
    fun isId(c: Char) = c.isLetterOrDigit() || c == '_' || c == '$'
    var start = offset
    var end = offset
    if (end < text.length && isId(text[end])) {
        while (start > 0 && isId(text[start - 1])) start--
        while (end < text.length && isId(text[end])) end++
    } else {
        return null
    }
    return if (end > start && !text[start].isDigit()) start until end else null
}

/**
 * Places an editor popup at [anchorX] just below the line spanning [lineTop]..[lineBottom] (pane pixels), or
 * just above it when there is no room below, keeping it inside the window horizontally.
 */
internal class EditorLinePositionProvider(
    private val anchorX: Int,
    private val lineTop: Int,
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
        val below = anchorBounds.top + lineBottom + gapPx
        val above = anchorBounds.top + lineTop - gapPx - popupContentSize.height
        val y = if (below + popupContentSize.height <= windowSize.height - marginPx || above < marginPx) below else above
        return IntOffset(x, y)
    }
}

/** [EditorLinePositionProvider] for the line holding [offset], from the editor's [caretGeometry]. */
@Composable
internal fun rememberLinePosition(
    offset: Int,
    caretGeometry: (Int) -> Triple<Int, Float, Float>,
    lineHeight: Float,
    minX: Float,
): EditorLinePositionProvider {
    val density = LocalDensity.current
    val (_, x, top) = caretGeometry(offset)
    val gap = with(density) { 4.dp.roundToPx() }
    val margin = with(density) { 8.dp.roundToPx() }
    return remember(x, top, lineHeight, minX, gap, margin) {
        EditorLinePositionProvider(
            x.coerceAtLeast(minX).toInt(), top.toInt(), (top + lineHeight).toInt(), gap, margin,
        )
    }
}

/**
 * Tracks whether the pointer is inside a hover popup, so moving from the code into the popup (to read or
 * scroll it, or pick a fix) keeps it up, and leaving it lets it hide.
 */
internal fun Modifier.trackPopupHover(hover: EditorHoverState): Modifier = pointerInput(hover) {
    awaitPointerEventScope {
        while (true) {
            when (awaitPointerEvent().type) {
                PointerEventType.Enter, PointerEventType.Move -> hover.popupHovered = true
                PointerEventType.Exit -> {
                    hover.popupHovered = false
                    hover.moves.tryEmit(null)
                }
            }
        }
    }
}

/** A hovered symbol's documentation in a card beside it, the same body as the quick-doc popup. */
@Composable
internal fun HoverDocPopup(
    info: HoverInfo.Doc,
    hover: EditorHoverState,
    caretGeometry: (Int) -> Triple<Int, Float, Float>,
    lineHeight: Float,
    minX: Float,
) {
    val position = rememberLinePosition(info.start, caretGeometry, lineHeight, minX)
    Popup(popupPositionProvider = position, onDismissRequest = { hover.dismiss() }) {
        QuickDocPopup(info.doc, Modifier.trackPopupHover(hover))
    }
}

/** Underlines the Ctrl/⌘-hovered identifier [range] (a single line) as a link. */
@Composable
internal fun BoxScope.LinkUnderline(
    range: IntRange,
    caretGeometry: (Int) -> Triple<Int, Float, Float>,
    lineHeight: Float,
) {
    val color = MaterialTheme.colorScheme.primary
    val (_, x1, top) = caretGeometry(range.first)
    val (_, x2, _) = caretGeometry(range.last + 1)
    Canvas(Modifier.matchParentSize()) {
        val y = top + lineHeight - 2f
        drawLine(color, Offset(x1, y), Offset(x2, y), strokeWidth = 1.5f)
    }
}

/**
 * The editor's right-click menu, opened at the pointer ([at], pane pixels): context actions, the go-to
 * family, rename, the clipboard, then reformat / optimize imports and documentation, followed by the
 * plugin actions resolved for the editor ([pluginMenu]). Rows that cannot apply (cut with no selection, paste
 * into a read-only file) are disabled rather than hidden, so the menu keeps its shape. Opened with a mouse,
 * so its rows are desktop-dense rather than touch-sized.
 */
@Composable
internal fun EditorContextMenu(
    at: Offset,
    hasSelection: Boolean,
    readOnly: Boolean,
    pluginMenu: UiMenuGroup,
    onShowActions: () -> Unit,
    onCommand: (String) -> Unit,
    onCut: () -> Unit,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
    onSelectAll: () -> Unit,
    onPluginAction: (UiActionItem) -> Unit,
    onDismiss: () -> Unit,
) {
    var goToOpen by remember { mutableStateOf(false) }
    Box(Modifier.offset { IntOffset(at.x.toInt(), at.y.toInt()) }.size(1.dp)) {
        CaDropdownMenu(expanded = true, onDismissRequest = onDismiss, offset = DpOffset.Zero) {
            fun run(action: () -> Unit) { onDismiss(); action() }
            CaMenuItem(stringResource(Res.string.codeaction_show_context_actions), onClick = { run(onShowActions) }, modifier = Dense)
            CaSubmenuItem(stringResource(Res.string.nav_go_to), icon = null, expanded = goToOpen, onExpandedChange = { goToOpen = it }) {
                CaMenuItem(stringResource(Res.string.nav_declaration), onClick = { run { onCommand(EditorCommands.GO_TO_DECLARATION) } }, modifier = Dense)
                CaMenuItem(stringResource(Res.string.nav_implementations), onClick = { run { onCommand(EditorCommands.GO_TO_IMPLEMENTATION) } }, modifier = Dense)
                CaMenuItem(stringResource(Res.string.nav_type_declaration), onClick = { run { onCommand(EditorCommands.GO_TO_TYPE_DECLARATION) } }, modifier = Dense)
                CaMenuItem(stringResource(Res.string.nav_super), onClick = { run { onCommand(EditorCommands.GO_TO_SUPER) } }, modifier = Dense)
            }
            CaMenuItem(stringResource(Res.string.rename), onClick = { run { onCommand(EditorCommands.RENAME) } }, enabled = !readOnly, modifier = Dense)
            CaMenuItem(stringResource(Res.string.edoverlay_quick_documentation), onClick = { run { onCommand(EditorCommands.QUICK_DOC) } }, modifier = Dense)
            MenuSeparator()
            CaMenuItem(stringResource(Res.string.edoverlay_cut), onClick = { run(onCut) }, enabled = hasSelection && !readOnly, modifier = Dense)
            CaMenuItem(stringResource(Res.string.copy), onClick = { run(onCopy) }, enabled = hasSelection, modifier = Dense)
            CaMenuItem(stringResource(Res.string.edoverlay_paste), onClick = { run(onPaste) }, enabled = !readOnly, modifier = Dense)
            CaMenuItem(stringResource(Res.string.edoverlay_select_all), onClick = { run(onSelectAll) }, modifier = Dense)
            MenuSeparator()
            CaMenuItem(stringResource(Res.string.edchrome_reformat_code), onClick = { run { onCommand(EditorCommands.REFORMAT) } }, enabled = !readOnly, modifier = Dense)
            CaMenuItem(stringResource(Res.string.edchrome_optimize_imports), onClick = { run { onCommand(EditorCommands.OPTIMIZE_IMPORTS) } }, enabled = !readOnly, modifier = Dense)
            if (pluginMenu.items.isNotEmpty()) {
                MenuSeparator()
                PluginMenuNodes(pluginMenu.items) { run { onPluginAction(it) } }
            }
        }
    }
}

/** A desktop menu row: 32dp rather than Material's 48dp touch minimum, as the pointer needs no thumb room. */
private val Dense = Modifier.height(32.dp)

/** The plugin entries of the editor menu, flyouts for their submenus. */
@Composable
private fun PluginMenuNodes(nodes: List<UiMenuNode>, onPick: (UiActionItem) -> Unit) {
    var openSub by remember { mutableStateOf<String?>(null) }
    nodes.forEach { node ->
        when (node) {
            is UiMenuNode.Item -> CaMenuItem(node.action.text, onClick = { onPick(node.action) }, enabled = node.action.enabled, modifier = Dense)
            is UiMenuNode.Submenu -> CaSubmenuItem(
                node.text, icon = null,
                expanded = openSub == node.text,
                onExpandedChange = { openSub = if (it) node.text else null },
            ) { PluginMenuNodes(node.items, onPick) }
            UiMenuNode.Separator -> MenuSeparator()
        }
    }
}

@Composable
private fun MenuSeparator() {
    HorizontalDivider(
        Modifier.padding(vertical = 4.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}
