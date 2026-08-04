package dev.ide.ui.editor

import dev.ide.ui.theme.Ide
import androidx.compose.material3.MaterialTheme
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupPositionProvider
import dev.ide.ui.backend.UiCompletionItem
import dev.ide.ui.components.entrancePop
import dev.ide.ui.components.pressScale
import dev.ide.ui.theme.Motion
import kotlinx.coroutines.delay
import dev.ide.ui.backend.UiQuickDoc
import dev.ide.ui.backend.UiSeverity
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.quickdoc_parameters
import dev.ide.ui.generated.resources.quickdoc_returns
import dev.ide.ui.generated.resources.quickdoc_see_also
import dev.ide.ui.generated.resources.quickdoc_throws
import dev.ide.ui.generated.resources.copy
import dev.ide.ui.generated.resources.edoverlay_cut
import dev.ide.ui.generated.resources.edoverlay_go_to_line
import dev.ide.ui.generated.resources.edoverlay_go_to_line_hint
import dev.ide.ui.generated.resources.edoverlay_no_documentation
import dev.ide.ui.generated.resources.edoverlay_paste
import dev.ide.ui.generated.resources.ctxmenu_actions
import dev.ide.ui.generated.resources.edoverlay_quick_documentation
import dev.ide.ui.generated.resources.edoverlay_rename_hint
import dev.ide.ui.generated.resources.edoverlay_rename_kind
import dev.ide.ui.generated.resources.edoverlay_renaming
import dev.ide.ui.generated.resources.edoverlay_select_all
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca
import org.jetbrains.compose.resources.stringResource

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
            .background(Ide.colors.glassThick, RoundedCornerShape(Ca.radius.lg))
            .border(1.dp, Ide.colors.glassEdge, RoundedCornerShape(Ca.radius.lg))
            .padding(16.dp),
    ) {
        Text(stringResource(Res.string.edoverlay_rename_kind, state.kind), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.size(8.dp))
        Box(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(Ca.radius.control))
                .border(1.dp, if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(Ca.radius.control))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            BasicTextField(
                value = field,
                onValueChange = { field = it; onChange(it.text) },
                singleLine = true,
                enabled = !busy,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface, fontFamily = Ide.type.codeFamily),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
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
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.size(6.dp))
        Text(if (busy) stringResource(Res.string.edoverlay_renaming) else stringResource(Res.string.edoverlay_rename_hint, state.oldName),
            color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * A centered prompt to jump to a line — accepts `line` or `line:column` (both 1-based). Enter navigates,
 * Esc cancels. Auto-focused; only digits and a single `:` are accepted so it can't be mistyped into prose.
 */
@Composable
internal fun GoToLinePopup(
    lineCount: Int,
    onGo: (line: Int, column: Int) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }
    var field by remember { mutableStateOf(TextFieldValue("")) }
    LaunchedEffect(Unit) { focus.requestFocus() }
    fun submit() {
        val parts = field.text.trim().split(':', limit = 2)
        val line = parts.getOrNull(0)?.trim()?.toIntOrNull()
        val col = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 1
        if (line != null) onGo(line, col) else onCancel()
    }
    Column(
        modifier.padding(top = 48.dp).width(320.dp)
            .background(Ide.colors.glassThick, RoundedCornerShape(Ca.radius.lg))
            .border(1.dp, Ide.colors.glassEdge, RoundedCornerShape(Ca.radius.lg))
            .padding(16.dp),
    ) {
        Text(stringResource(Res.string.edoverlay_go_to_line), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.size(8.dp))
        Box(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(Ca.radius.control))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(Ca.radius.control))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            BasicTextField(
                value = field,
                onValueChange = { v -> field = v.copy(text = v.text.filter { it.isDigit() || it == ':' }) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface, fontFamily = Ide.type.codeFamily),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).onPreviewKeyEvent { ev ->
                    if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (ev.key) {
                        Key.Enter -> { submit(); true }
                        Key.Escape -> { onCancel(); true }
                        else -> false
                    }
                },
            )
        }
        Spacer(Modifier.size(6.dp))
        Text(stringResource(Res.string.edoverlay_go_to_line_hint, lineCount), color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * Quick documentation popup: the symbol's signature (monospace) + container, then its rendered doc comment
 * (rich KDoc/Javadoc via [parseQuickDoc]). A floating card over the editor; the body scrolls when long.
 * Dismissed by the caller (Esc / tap-out). [doc] is null-checked by the caller, so it's always present here.
 */
@Composable
internal fun QuickDocPopup(doc: UiQuickDoc, modifier: Modifier = Modifier) {
    val codeStyle = SpanStyle(fontFamily = Ide.type.codeFamily, color = MaterialTheme.colorScheme.primary)
    val content = remember(doc, codeStyle) { doc.doc?.takeIf { it.isNotBlank() }?.let { parseQuickDoc(it, codeStyle) } }
    Column(
        modifier.padding(top = 56.dp).widthIn(max = 440.dp).heightIn(max = 360.dp)
            .background(Ide.colors.glassThick, RoundedCornerShape(Ca.radius.lg))
            .border(1.dp, Ide.colors.glassEdge, RoundedCornerShape(Ca.radius.lg))
            .padding(14.dp),
    ) {
        Text(
            doc.signature,
            style = MaterialTheme.typography.bodyLarge.copy(fontFamily = Ide.type.codeFamily),
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium,
        )
        doc.container?.takeIf { it.isNotEmpty() }?.let {
            Spacer(Modifier.size(2.dp))
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.size(10.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
        Spacer(Modifier.size(10.dp))
        Column(Modifier.verticalScroll(rememberScrollState())) {
            if (content == null) {
                Text(stringResource(Res.string.edoverlay_no_documentation), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.outline)
            } else {
                if (content.description.isNotEmpty()) {
                    Text(content.description, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                for (sec in content.sections) {
                    Spacer(Modifier.size(10.dp))
                    if (sec.title.isNotEmpty()) {
                        Text(docSectionLabel(sec.title), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.size(3.dp))
                    }
                    for (item in sec.items) {
                        Text(item, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 2.dp))
                    }
                }
            }
        }
    }
}

/** Localized display for a quick-doc section header. The parser ([parseQuickDoc]) keeps the English key
 *  ("Parameters"/"Returns"/"Throws"/"See also"); an unknown/plugin title falls through unchanged. */
@Composable
private fun docSectionLabel(title: String): String = when (title) {
    "Parameters" -> stringResource(Res.string.quickdoc_parameters)
    "Returns" -> stringResource(Res.string.quickdoc_returns)
    "Throws" -> stringResource(Res.string.quickdoc_throws)
    "See also" -> stringResource(Res.string.quickdoc_see_also)
    else -> title
}

/** The inline diagnostic chip: a pill at the right of a diagnostic line — severity-tinted fill, icon,
 *  message. Colour/icon follow [severity]; an [unused] warning is muted rather than alarming.
 *
 *  Sized off the editor's (zoom-scaled) code metrics so it reads as text ON its line: the pill's text is the
 *  code [fontSize], icon + paddings are derived from that same em, and the whole thing is vertically centred
 *  inside a [lineHeightPx]-tall box — so it grows and stays line-aligned as the editor is pinch-zoomed. */
@Composable
internal fun DiagnosticChip(
    severity: UiSeverity,
    unused: Boolean,
    message: String,
    fontSize: TextUnit,
    lineHeightPx: Float,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val color = when (severity) {
        UiSeverity.Error -> MaterialTheme.colorScheme.error
        UiSeverity.Warning -> if (unused) MaterialTheme.colorScheme.outline else Ide.colors.warning
        UiSeverity.Info -> Ide.colors.info
        UiSeverity.Hint -> MaterialTheme.colorScheme.outline
    }
    val icon = when (severity) {
        UiSeverity.Error -> CaIcons.error
        UiSeverity.Warning -> CaIcons.warning
        UiSeverity.Info, UiSeverity.Hint -> CaIcons.info
    }
    val density = LocalDensity.current
    val lineHeightDp = with(density) { lineHeightPx.toDp() }
    val em = with(density) { fontSize.toDp() } // the code font's em in dp — scales the icon + paddings with zoom
    // Outer box is exactly one line tall (positioned at the row top by [modifier]'s offset); the pill sits
    // content-sized and vertically centred within it, so its baseline tracks the code glyphs on the same row.
    Box(modifier.height(lineHeightDp), contentAlignment = Alignment.CenterStart) {
        Row(
            Modifier
                .background(color.copy(alpha = 0.16f), RoundedCornerShape(Ca.radius.pill))
                .clickable(onClick = onClick)
                .padding(horizontal = em * 0.5f, vertical = em * 0.12f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(em * 0.35f),
        ) {
            Icon(icon, null, Modifier.size(em * 0.95f), tint = color)
            Text(
                message,
                color = color,
                fontSize = fontSize,
                lineHeight = fontSize, // tight box so vertical centring lands on the code glyphs, not below them
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Delay step (ms) between the toolbar's items cascading in when it appears. */
private const val ToolbarItemStaggerMs = 30

/**
 * The floating touch selection toolbar: Copy / Cut / Paste / Select all, then (past a divider) the quick-doc
 * and quick-fix icons. A frosted-glass pill that pops up from the selection ([entrancePop]); its items cascade
 * in left-to-right, and each gives a scale + accent-tint press response. Clipboard actions appear only with a
 * live selection; the two utility icons only when relevant.
 */
@Composable
internal fun SelectionToolbar(
    hasSelection: Boolean,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onPaste: () -> Unit,
    onSelectAll: () -> Unit,
    onDocs: (() -> Unit)? = null,
    onMenu: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(Ca.radius.pill)
    Row(
        Modifier
            .entrancePop()
            .shadow(12.dp, shape, clip = false)
            .background(Ide.colors.glassThick, shape)
            .border(1.dp, Ide.colors.glassEdge, shape)
            .padding(horizontal = 4.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        var i = 0
        if (hasSelection) {
            ToolbarTextItem(stringResource(Res.string.copy), i++, onCopy)
            ToolbarTextItem(stringResource(Res.string.edoverlay_cut), i++, onCut)
        }
        ToolbarTextItem(stringResource(Res.string.edoverlay_paste), i++, onPaste)
        ToolbarTextItem(stringResource(Res.string.edoverlay_select_all), i++, onSelectAll)
        if (onMenu != null || onDocs != null) ToolbarDivider()
        // Quick documentation for the symbol under the caret (the touch path for Ctrl-Q).
        if (onDocs != null) {
            ToolbarIconItem(CaIcons.info, stringResource(Res.string.edoverlay_quick_documentation), i++, MaterialTheme.colorScheme.onSurfaceVariant, onDocs)
        }
        // The unified editor context menu — Go to / Quick fixes / Intentions (the long-press actions menu).
        if (onMenu != null) {
            ToolbarIconItem(CaIcons.ellipsis, stringResource(Res.string.ctxmenu_actions), i++, MaterialTheme.colorScheme.onSurfaceVariant, onMenu)
        }
    }
}

@Composable
private fun ToolbarTextItem(label: String, index: Int, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        Modifier
            .toolbarItemEntrance(index)
            .pressScale(interaction)
            .clip(RoundedCornerShape(Ca.radius.xs))
            .background(if (pressed) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .clickable(interaction, indication = null, onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (pressed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

@Composable
private fun ToolbarIconItem(icon: ImageVector, description: String, index: Int, tint: Color, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        Modifier
            .toolbarItemEntrance(index)
            .pressScale(interaction)
            .clip(RoundedCornerShape(Ca.radius.xs))
            .background(if (pressed) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .clickable(interaction, indication = null, onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, Modifier.size(16.dp), tint = if (pressed) MaterialTheme.colorScheme.primary else tint)
    }
}

/** A thin vertical rule separating the clipboard actions from the utility (docs / quick-fix) icons. */
@Composable
private fun ToolbarDivider() {
    Box(Modifier.padding(horizontal = 3.dp).width(1.dp).height(18.dp).background(MaterialTheme.colorScheme.outlineVariant))
}

/** Per-item cascade: fade + a slight rise, delayed by [index] steps, so the items flow in left-to-right. */
@Composable
private fun Modifier.toolbarItemEntrance(index: Int): Modifier {
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (index > 0) delay(index.toLong() * ToolbarItemStaggerMs)
        appeared = true
    }
    val progress by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = tween(durationMillis = Motion.FAST, easing = Motion.quiet),
        label = "toolbarItem",
    )
    return this.graphicsLayer {
        alpha = progress
        translationY = (1f - progress) * 5.dp.toPx()
    }
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
