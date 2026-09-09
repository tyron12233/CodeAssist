package dev.ide.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.material3.Icon
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiDiagnostic
import dev.ide.ui.backend.UiGutterMark
import dev.ide.ui.backend.UiSeverity
import dev.ide.ui.clipForClipboard
import dev.ide.ui.editor.core.EditorDocument
import dev.ide.ui.editor.core.EditorSession
import dev.ide.ui.ext.EditorAnchor
import dev.ide.ui.ext.EditorLayerContext
import dev.ide.ui.ext.EditorLayerRegistry
import dev.ide.ui.icons.actionIcon
import dev.ide.ui.theme.CodeAssistColors
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The non-popup editor overlays that scroll with the document: the per-line diagnostic chips, the touch
 * selection toolbar, and the `@Preview` gutter icons. Extracted from [CodeEditor] so its emission composable
 * stays under ART's per-method instruction limit. Per-frame derived values (metrics/gutter width/wrap) are
 * explicit params so recomposition tracks them (see the caret-anchored layers at the bottom of CodeEditor.kt).
 */

/**
 * Every diagnostic grouped by the line it *starts* on, each group ordered most severe first and then by
 * position. This is the unit the whole diagnostic surface speaks in: one chip per group (showing the group's
 * loudest message), the chip's badge counting the group, and the sheet listing the group when it is tapped,
 * so a problem hidden behind a more severe one on the same line, or on the very same span, stays reachable.
 * Grouping on the START line (not everything a span covers) keeps a degenerate multi-line span from inflating
 * the count of every line it crosses, and matches where the chip and the gutter glyph are drawn.
 *
 * [lineOf] maps a diagnostic's start offset to its document line (the caller clamps it to the buffer).
 */
internal fun diagnosticsByStartLine(
    diagnostics: List<UiDiagnostic>,
    lineOf: (Int) -> Int,
): Map<Int, List<UiDiagnostic>> {
    if (diagnostics.isEmpty()) return emptyMap()
    val byLine = HashMap<Int, MutableList<UiDiagnostic>>()
    for (d in diagnostics) byLine.getOrPut(lineOf(d.startOffset)) { ArrayList(2) }.add(d)
    // lower severity ordinal = more severe (Error before Warning before Info before Hint)
    val order = compareBy<UiDiagnostic>({ it.severity.ordinal }, { it.startOffset }, { it.endOffset })
    return byLine.mapValues { (_, group) -> if (group.size > 1) group.sortedWith(order) else group }
}

/**
 * One line's chip: [primary] is the message the chip shows (the most severe Error/Warning starting on that
 * line) and [count] is how many diagnostics of ANY severity start there. A count above 1 badges the chip, and
 * a tap opens the whole line's group in the sheet, so the quieter problems stacked behind the loudest one
 * (and the Info/Hints that never get a chip of their own) are still reachable.
 */
private class ChipGroup(val primary: UiDiagnostic, val count: Int)

/**
 * Inline diagnostic chips: one per line, showing the most severe diagnostic on it, positioned in the layout
 * phase so scrolling moves them without recomposition. Only a line carrying an Error or Warning gets a chip
 * (a line of Info/Hint alone stays quiet: squiggle + gutter only), but the chip speaks for every diagnostic on
 * its line: it badges the total and its tap opens them all.
 */
@Composable
internal fun BoxScope.DiagnosticChipsLayer(
    session: EditorSession,
    render: EditorRenderState,
    diagnostics: List<UiDiagnostic>,
    metrics: EditorMetrics,
    gutterWidthPx: Float,
    wordWrap: Boolean,
    vlayout: VLayout,
    vOffset: MutableFloatState,
    hOffset: MutableFloatState,
    onOpenSheet: (UiDiagnostic) -> Unit,
    onChipExtent: (Float) -> Unit,
) {
    val doc = session.doc
    // Per line: the most-severe Error/Warning plus how many diagnostics live there in total. Memoized on
    // (diagnostics, doc): a caret-only move leaves the buffer untouched, so this is a cache hit then, and it
    // changes only on an actual edit (or a fresh analysis).
    val chipPerLine = remember(diagnostics, doc) {
        val byLine = diagnosticsByStartLine(diagnostics) { doc.lineForOffset(it.coerceIn(0, doc.length)) }
        // A line only gets a chip when it carries an Error/Warning; one of Info/Hint alone stays quiet. The
        // group is severity-ordered, so the first such entry is the loudest message on the line; every
        // severity in the group counts towards the badge, since the sheet lists them all.
        buildMap {
            for ((ln, group) in byLine) {
                val primary = group.firstOrNull {
                    it.severity == UiSeverity.Error || it.severity == UiSeverity.Warning
                } ?: continue
                put(ln, ChipGroup(primary, group.size))
            }
        }
    }
    val fm = session.foldModel
    val density = LocalDensity.current
    // Report how far the widest chip reaches past its line so the editor's horizontal scroll extent
    // ([EditorGeometry.contentWidth]) can grow to reveal it — otherwise a chip overhanging the longest line
    // is clipped at the viewport edge with no way to scroll to it. Measured (not just estimated) since the
    // chip text is proportional, not the editor's monospace; recomputed only when the chips/geometry change.
    val measurer = rememberTextMeasurer()
    val fontSize = render.codeStyle.fontSize
    val chipExtent = remember(chipPerLine, fontSize, wordWrap, metrics.charWidth, density, session.foldRegions) {
        val em = with(density) { fontSize.toPx() }
        // Icon (em*0.95) + row spacing (em*0.35) + horizontal padding (em*0.5 each side) around the message.
        val chrome = em * (0.95f + 0.35f + 1.0f)
        var maxRight = 0f
        for ((ln, g) in chipPerLine) {
            if (fm.isHidden(ln)) continue
            val chipLayout =
                if (fm.foldStartingAt(ln) != null) render.compositeLayoutFor(ln) else render.layoutFor(ln)
            val lastSub = if (wordWrap) (chipLayout.lineCount - 1).coerceAtLeast(0) else 0
            val lineWidth = if (wordWrap) chipLayout.getLineRight(lastSub) else chipLayout.size.width.toFloat()
            val textW = measurer.measure(
                g.primary.message,
                TextStyle(fontSize = fontSize, fontWeight = FontWeight.SemiBold),
                maxLines = 1,
            ).size.width.toFloat()
            // The count badge (only when the line stacks several) adds its own gap + icon + padding + digits.
            val badgeW = if (g.count > 1) {
                val digits = measurer.measure(
                    g.count.toString(),
                    TextStyle(fontSize = fontSize * CountBadgeTextScale, fontWeight = FontWeight.Bold),
                    maxLines = 1,
                ).size.width.toFloat()
                em * (0.35f + 0.7f + 0.12f + 0.56f) + digits
            } else {
                0f
            }
            // Right edge in the same (gutter-excluded) frame [contentWidth] uses: padLeft + line + gap + chip.
            val right = metrics.padLeft + lineWidth + metrics.charWidth * 3f + chrome + textW + badgeW
            if (right > maxRight) maxRight = right
        }
        maxRight
    }
    // Push after composition (contentWidth reads it in the draw/scroll phase); a same-value write is a no-op.
    SideEffect { onChipExtent(chipExtent) }
    // Clip the chips to the code area (right of the gutter): a chip that scrolls left then slides UNDER the
    // gutter instead of overlapping it. Draw-only clip; the chips keep their absolute positions.
    Box(
        Modifier.matchParentSize().drawWithContent {
            clipRect(left = gutterWidthPx) { this@drawWithContent.drawContent() }
        },
    ) {
        for ((ln, g) in chipPerLine) {
            if (fm.isHidden(ln)) continue // diagnostic inside a collapsed region → no chip
            val d = g.primary
            // Place after the composite text on a fold-start line, else after the real line. When wrapping, sit
            // after the end of the line's LAST wrapped row.
            val chipLayout =
                if (fm.foldStartingAt(ln) != null) render.compositeLayoutFor(ln) else render.layoutFor(ln)
            val lastSub = if (wordWrap) (chipLayout.lineCount - 1).coerceAtLeast(0) else 0
            val lineWidth =
                if (wordWrap) chipLayout.getLineRight(lastSub) else chipLayout.size.width.toFloat()
            DiagnosticChip(
                d.severity,
                d.unused,
                d.message,
                count = g.count,
                fontSize = render.codeStyle.fontSize, // zoom-scaled code size, so the chip grows with the editor
                lineHeightPx = metrics.lineHeight,
                onClick = { onOpenSheet(d) },
                modifier = Modifier.offset {
                    IntOffset(
                        // Gap after the line end scales with the (zoomed) char width, not a fixed px count.
                        (gutterWidthPx + metrics.padLeft + lineWidth + metrics.charWidth * 3f - hOffset.floatValue).roundToInt(),
                        (metrics.padTop + (vlayout.topRow(ln) + lastSub) * metrics.lineHeight - vOffset.floatValue).roundToInt(),
                    )
                },
            )
        }
    }
}

/** Floating selection toolbar (touch): Copy / Cut / Paste / Select all above the selection. */
@Composable
internal fun SelectionToolbarLayer(
    session: EditorSession,
    geometry: EditorGeometry,
    interaction: EditorInteraction,
    onDocs: () -> Unit,
    onMenu: () -> Unit,
) {
    if (!(interaction.handlesVisible && interaction.lastInputWasTouch)) return
    val density = LocalDensity.current
    @Suppress("DEPRECATION") val clipboard = LocalClipboardManager.current
    // Anchor at the ACTIVE end of the selection (`end` is always the moving handle — see the handle-drag in
    // EditorInputModifier), so the toolbar follows the finger and lands where the user finished selecting
    // rather than staying back at where the selection started.
    val selActive = session.selection.end
    val (_, selX, selTop) = geometry.caretGeometry(selActive)
    val gapPx = with(density) { 8.dp.roundToPx() }
    Popup(
        popupPositionProvider = remember(selX, selTop, gapPx) {
            AboveAnchorPositionProvider(selX.roundToInt(), selTop.roundToInt(), gapPx)
        },
    ) {
        // Report the toolbar's height so the lightbulb (anchored above this same line) can stack above it.
        Box(Modifier.onSizeChanged { interaction.selectionToolbarHeightPx = it.height }) {
            SelectionToolbar(
                hasSelection = !session.selection.collapsed,
                onCopy = {
                    // Cap the payload: putting a multi-MB selection on the system clipboard marshals it across a
                    // Binder transaction and throws TransactionTooLargeException (crash observed in the field).
                    session.selectedText()?.let { clipboard.setText(AnnotatedString(clipForClipboard(it))) }
                    interaction.handlesVisible = false
                },
                onCut = {
                    session.cutSelection()?.let { clipboard.setText(AnnotatedString(clipForClipboard(it))) }
                    interaction.handlesVisible = false
                },
                onPaste = {
                    clipboard.getText()?.text?.let { if (it.isNotEmpty()) session.commitText(it) }
                    interaction.handlesVisible = false
                },
                onSelectAll = { session.selectAll() },
                onDocs = { interaction.handlesVisible = false; onDocs() },
                onMenu = onMenu,
            )
        }
    }
}

/**
 * `@Preview` gutter icons — a tappable glyph in the gutter beside each Compose `@Preview` annotation. Tapping
 * switches this tab to the Preview surface rendering that variant. Positioned per line and read in the layout
 * phase, so they scroll with the document. Variants of one annotation share an offset → one icon per line.
 */
@Composable
internal fun PreviewGutterIconsLayer(
    session: EditorSession,
    metrics: EditorMetrics,
    vlayout: VLayout,
    vOffset: MutableFloatState,
    docLength: Int,
    onPreview: (String) -> Unit,
) {
    val doc = session.doc
    val seenPreviewLines = HashSet<Int>()
    for (p in session.previewMarkers) {
        val ln = doc.lineForOffset(p.offset.coerceIn(0, docLength))
        if (session.foldModel.isHidden(ln)) continue // @Preview folded away → no gutter icon
        if (!seenPreviewLines.add(ln)) continue // one icon per annotation line
        PreviewGutterIcon(
            onClick = { onPreview(p.variantId) },
            modifier = Modifier.offset {
                IntOffset(
                    1.dp.roundToPx(),
                    (metrics.padTop + vlayout.topRow(ln) * metrics.lineHeight - vOffset.floatValue + (metrics.lineHeight - 20.dp.toPx()) / 2f).roundToInt(),
                )
            },
        )
    }
}

/**
 * A plugin's gutter marks (`platform.editorDecoration`): a tinted glyph beside the line number, tappable when
 * the mark named an action.
 *
 * A composable layer rather than a canvas draw, unlike the tinted ranges: a mark carries a tooltip and a tap
 * target, and the canvas can do neither. Positioned in the layout phase off the same row map the code uses,
 * so a mark scrolls with its line without recomposing.
 *
 * The gutter fits one glyph. Several marks on a line collapse to the highest
 * [dev.ide.plugin.editor.GutterMark.order], and a line that already carries a `@Preview` icon keeps it: that
 * icon is the IDE's own and switching surfaces is a more consequential tap than a plugin's indicator.
 */
@Composable
internal fun PluginGutterMarksLayer(
    session: EditorSession,
    metrics: EditorMetrics,
    vlayout: VLayout,
    vOffset: MutableFloatState,
    docLength: Int,
    colors: CodeAssistColors,
    onInvoke: (actionId: String, line: Int) -> Unit,
) {
    val marks = session.gutterMarks
    if (marks.isEmpty()) return
    val doc = session.doc
    val previewLines = remember(session.previewMarkers, doc) {
        session.previewMarkers.mapTo(HashSet()) { doc.lineForOffset(it.offset.coerceIn(0, docLength)) }
    }
    // One mark per line, the highest order winning, so the layer emits a stable set of composables rather
    // than stacking glyphs in a 20dp box.
    val byLine = remember(marks, previewLines) {
        val out = HashMap<Int, UiGutterMark>(marks.size)
        for (m in marks) {
            if (m.line in previewLines) continue
            val existing = out[m.line]
            if (existing == null || m.order > existing.order) out[m.line] = m
        }
        out
    }
    for ((line, mark) in byLine) {
        if (line >= doc.lineCount || session.foldModel.isHidden(line)) continue
        key(line, mark.iconId, mark.tint) {
            PluginGutterMark(
                mark = mark,
                colors = colors,
                onClick = mark.actionId?.let { id -> { onInvoke(id, line) } },
                modifier = Modifier.offset {
                    IntOffset(
                        1.dp.roundToPx(),
                        (
                            metrics.padTop + vlayout.topRow(line) * metrics.lineHeight - vOffset.floatValue +
                                (metrics.lineHeight - 20.dp.toPx()) / 2f
                            ).roundToInt(),
                    )
                },
            )
        }
    }
}

@Composable
private fun PluginGutterMark(
    mark: UiGutterMark,
    colors: CodeAssistColors,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val tint = decorationColor(mark.tint, colors)
    Box(
        modifier
            .size(20.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            actionIcon(mark.iconId),
            contentDescription = mark.tooltip,
            tint = tint,
            modifier = Modifier.size(14.dp),
        )
    }
}

/**
 * The plugin-contributed anchored composables (`EditorLayerContribution`): a hover card, an inline button, a
 * code-lens row, placed at a document position and scrolling with the text.
 *
 * Each widget is positioned in the LAYOUT phase off the same row map the code canvas draws against, so a
 * scroll moves it without recomposing it, exactly like the diagnostic chips and the `@Preview` icons above.
 * That is what makes this viable at all: a widget re-laid-out per frame of a fling would cost more than the
 * whole editor.
 *
 * A layer's producer runs inside this composition, so its state reads make it reactive. It is NOT guarded,
 * unlike a painter's draw and unlike the `appliesTo` predicate the registry checks: a `@Composable` call
 * cannot be wrapped, because the slot table the composition is built from has no way to unwind half a
 * composable's work. A producer that throws therefore takes the editor's composition with it. This is why a
 * layer is documented as "read state and decide" and why the data tier exists for everything expressible as
 * data: `platform.editorDecoration` runs off the composition entirely and per-provider failures there are
 * contained.
 */
@Composable
internal fun BoxScope.PluginEditorLayers(
    session: EditorSession,
    metrics: EditorMetrics,
    vlayout: VLayout,
    vOffset: MutableFloatState,
    hOffset: MutableFloatState,
    gutterWidthPx: Float,
    path: String,
    backend: IdeBackend,
    visibleLines: IntRange,
) {
    val layers = EditorLayerRegistry.forFile(path)
    if (layers.isEmpty()) return
    val doc = session.doc
    val ctx = remember(path, session.textRevision, visibleLines, session.selection, backend) {
        object : EditorLayerContext {
            override val path = path
            override val text = doc.text
            override val visibleLines = visibleLines
            override val caretOffset = session.selection.min
            override val backend = backend
        }
    }
    for (layer in layers) {
        key(layer.id) {
            val widgets = layer.widgets(ctx)
            for (widget in widgets) {
                val anchorLine = when (val a = widget.anchor) {
                    is EditorAnchor.AtOffset -> doc.lineForOffset(a.offset.coerceIn(0, doc.length))
                    is EditorAnchor.AfterLine -> a.line
                    is EditorAnchor.AboveLine -> a.line
                }
                if (anchorLine !in 0 until doc.lineCount || session.foldModel.isHidden(anchorLine)) continue
                key(layer.id, widget.key) {
                    Box(
                        Modifier.offset {
                            IntOffset(
                                widgetX(widget.anchor, doc, metrics, gutterWidthPx, hOffset.floatValue),
                                widgetY(widget.anchor, anchorLine, metrics, vlayout, vOffset.floatValue),
                            )
                        },
                    ) {
                        widget.content()
                    }
                }
            }
        }
    }
}

/**
 * X of a widget, in px.
 *
 * Columns are measured in character advances rather than from the shaped line: this runs in the layout phase,
 * which has no `TextLayoutResult` to ask, and the editor's font is monospace so the advance is exact for
 * everything but a wrapped row. A wrapped row puts the widget at the row's start, which is where an
 * end-of-line widget belongs anyway.
 */
private fun widgetX(
    anchor: EditorAnchor,
    doc: EditorDocument,
    metrics: EditorMetrics,
    gutterWidthPx: Float,
    hOff: Float,
): Int {
    val col = when (anchor) {
        is EditorAnchor.AtOffset -> {
            val off = anchor.offset.coerceIn(0, doc.length)
            off - doc.lineStart(doc.lineForOffset(off))
        }
        // Past the line's last character, with one space of air so it does not touch the code.
        is EditorAnchor.AfterLine -> doc.lineLength(anchor.line.coerceIn(0, doc.lineCount - 1)) + 1
        // A full-width band starts at the text's left edge.
        is EditorAnchor.AboveLine -> 0
    }
    return (gutterWidthPx + metrics.padLeft + col * metrics.charWidth - hOff).roundToInt()
}

/** Y of a widget, in px: its anchor line's row top, or the row above it for [EditorAnchor.AboveLine]. */
private fun widgetY(
    anchor: EditorAnchor,
    line: Int,
    metrics: EditorMetrics,
    vlayout: VLayout,
    vOff: Float,
): Int {
    val top = metrics.padTop + vlayout.topRow(line) * metrics.lineHeight - vOff
    return when (anchor) {
        is EditorAnchor.AboveLine -> (top - metrics.lineHeight).roundToInt()
        else -> top.roundToInt()
    }
}
