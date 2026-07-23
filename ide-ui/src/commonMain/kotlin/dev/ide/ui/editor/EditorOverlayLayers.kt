package dev.ide.ui.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import dev.ide.ui.backend.UiDiagnostic
import dev.ide.ui.backend.UiSeverity
import dev.ide.ui.editor.core.EditorSession
import kotlin.math.roundToInt

/**
 * The non-popup editor overlays that scroll with the document: the per-line diagnostic chips, the touch
 * selection toolbar, and the `@Preview` gutter icons. Extracted from [CodeEditor] so its emission composable
 * stays under ART's per-method instruction limit. Per-frame derived values (metrics/gutter width/wrap) are
 * explicit params so recomposition tracks them (see the caret-anchored layers at the bottom of CodeEditor.kt).
 */

/**
 * Inline diagnostic chips — one per line, the most severe diagnostic on it, positioned in the layout phase so
 * scrolling moves them without recomposition. Only Error and Warning get a chip (Info/Hint stay quiet: squiggle
 * + gutter only).
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
) {
    val doc = session.doc
    // The most-severe Error/Warning per line, memoized on (diagnostics, doc): a caret-only move leaves the
    // buffer untouched, so this is a cache hit then — it changes only on an actual edit.
    val chipPerLine = remember(diagnostics, doc) {
        val m = HashMap<Int, UiDiagnostic>()
        for (d in diagnostics) {
            if (d.severity != UiSeverity.Error && d.severity != UiSeverity.Warning) continue
            val off = d.startOffset.coerceIn(0, doc.length)
            val ln = doc.lineForOffset(off)
            val cur = m[ln]
            // lower ordinal = more severe (Error before Warning)
            if (cur == null || d.severity.ordinal < cur.severity.ordinal) m[ln] = d
        }
        m
    }
    val fm = session.foldModel
    // Clip the chips to the code area (right of the gutter): a chip that scrolls left then slides UNDER the
    // gutter instead of overlapping it. Draw-only clip; the chips keep their absolute positions.
    Box(
        Modifier.matchParentSize().drawWithContent {
            clipRect(left = gutterWidthPx) { this@drawWithContent.drawContent() }
        },
    ) {
        for ((ln, d) in chipPerLine) {
            if (fm.isHidden(ln)) continue // diagnostic inside a collapsed region → no chip
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
                onClick = { onOpenSheet(d) },
                modifier = Modifier.offset {
                    IntOffset(
                        (gutterWidthPx + metrics.padLeft + lineWidth + 24f - hOffset.floatValue).roundToInt(),
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
    acts: EditorActionsController,
    onDocs: () -> Unit,
) {
    if (!(interaction.handlesVisible && interaction.lastInputWasTouch)) return
    val density = LocalDensity.current
    @Suppress("DEPRECATION") val clipboard = LocalClipboardManager.current
    val selMin = session.selection.min
    val (_, selX, selTop) = geometry.caretGeometry(selMin)
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
                // Keep quick-FIXES out of this clipboard toolbar: on a diagnostic the gutter lightbulb owns them, so
                // the toolbar stays Copy/Cut/Paste only. Off a diagnostic it still surfaces caret INTENTIONS here.
                hasActions = acts.available.isNotEmpty() && acts.caretDiagnostic == null,
                onActions = { interaction.handlesVisible = false; acts.openMenu() },
                onCopy = {
                    session.selectedText()?.let { clipboard.setText(AnnotatedString(it)) }
                    interaction.handlesVisible = false
                },
                onCut = {
                    session.cutSelection()?.let { clipboard.setText(AnnotatedString(it)) }
                    interaction.handlesVisible = false
                },
                onPaste = {
                    clipboard.getText()?.text?.let { if (it.isNotEmpty()) session.commitText(it) }
                    interaction.handlesVisible = false
                },
                onSelectAll = { session.selectAll() },
                onDocs = { interaction.handlesVisible = false; onDocs() },
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
