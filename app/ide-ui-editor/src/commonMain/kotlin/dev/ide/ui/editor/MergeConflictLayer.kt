package dev.ide.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.Layout
import dev.ide.ui.backend.UiDiagnostic
import dev.ide.ui.editor.core.ConflictChoice
import dev.ide.ui.editor.core.EditorDocument
import dev.ide.ui.editor.core.EditorSession
import dev.ide.ui.editor.core.MergeConflict
import dev.ide.ui.editor.core.findMergeConflicts
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.conflict_both
import dev.ide.ui.generated.resources.conflict_both_desc
import dev.ide.ui.generated.resources.conflict_current
import dev.ide.ui.generated.resources.conflict_current_desc
import dev.ide.ui.generated.resources.conflict_incoming
import dev.ide.ui.generated.resources.conflict_incoming_desc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/**
 * The merge-conflict blocks in [doc], found off the main thread after each edit. Until a scan lands the previous
 * blocks stay up; they are drawn by line, so a keystroke inside one moves nothing visibly, and resolving one
 * re-reads the live text rather than trusting these offsets (see [resolveConflict]).
 */
@Composable
internal fun rememberMergeConflicts(session: EditorSession, doc: EditorDocument): List<MergeConflict> {
    var found by remember(session) { mutableStateOf(emptyList<MergeConflict>()) }
    LaunchedEffect(doc) {
        val text = doc.awaitText()
        found = withContext(Dispatchers.Default) { findMergeConflicts(text) }
    }
    return found
}

/**
 * [diagnostics] without the ones inside a conflict block. A parser reads `<<<<<<<` as code and reports it
 * ("Tag name expected"); those findings are about the markers, which resolving the block removes.
 */
internal fun withoutConflictDiagnostics(diagnostics: List<UiDiagnostic>, conflicts: List<MergeConflict>): List<UiDiagnostic> =
    if (conflicts.isEmpty()) diagnostics
    else diagnostics.filter { d -> conflicts.none { d.startOffset < it.end && d.endOffset >= it.start } }

/**
 * Replace the conflict that starts on [startLine] with the side(s) [choice] keeps, as one undoable edit. The block
 * is found again in the live text, so a stale scan can never cut the wrong lines; nothing happens when it is gone.
 */
internal fun resolveConflict(session: EditorSession, startLine: Int, choice: ConflictChoice) {
    val text = session.doc.text
    val c = findMergeConflicts(text).firstOrNull { it.startLine == startLine } ?: return
    session.replaceRange(c.start, c.end, c.resolution(text, choice), TextRange(c.start))
}

/**
 * The resolve row on each conflict's `<<<<<<<` line: keep the Current side, the Incoming one, or Both. Each button
 * wears its side's color, the same one the side's band is tinted with. The row follows the marker's label, which
 * names the sides, so it stays readable; on a screen too narrow for both it slides left over the label rather
 * than off the edge.
 */
@Composable
internal fun MergeConflictLayer(
    session: EditorSession,
    conflicts: List<MergeConflict>,
    metrics: EditorMetrics,
    vlayout: VLayout,
    vOffset: MutableFloatState,
    hOffset: MutableFloatState,
    gutterWidthPx: Float,
    currentColor: Color,
    incomingColor: Color,
) {
    if (conflicts.isEmpty()) return
    val lineHeightDp = with(LocalDensity.current) { metrics.lineHeight.toDp() }
    for (c in conflicts) {
        if (session.foldModel.isHidden(c.startLine)) continue
        key(c.startLine) {
            // Where the marker line's text ends: `<<<<<<< ` and the label, then a gap.
            val labelColumns = 8 + c.currentLabel.length
            Layout(
                modifier = Modifier.fillMaxWidth().height(lineHeightDp),
                content = {
                    Row(Modifier.fillMaxHeight().padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    ConflictButton(stringResource(Res.string.conflict_current), stringResource(Res.string.conflict_current_desc), currentColor) {
                        resolveConflict(session, c.startLine, ConflictChoice.CURRENT)
                    }
                    ConflictButton(stringResource(Res.string.conflict_incoming), stringResource(Res.string.conflict_incoming_desc), incomingColor) {
                        resolveConflict(session, c.startLine, ConflictChoice.INCOMING)
                    }
                    ConflictButton(stringResource(Res.string.conflict_both), stringResource(Res.string.conflict_both_desc), MaterialTheme.colorScheme.onSurfaceVariant) {
                        resolveConflict(session, c.startLine, ConflictChoice.BOTH)
                    }
                    }
                },
            ) { measurables, constraints ->
                val row = measurables.single().measure(constraints.copy(minWidth = 0))
                val gap = 12.dp.roundToPx()
                val afterLabel = gutterWidthPx + metrics.padLeft + labelColumns * metrics.charWidth - hOffset.floatValue + gap
                val rightmost = constraints.maxWidth - row.width - 8.dp.roundToPx()
                val x = minOf(afterLabel, rightmost.toFloat()).coerceAtLeast(gutterWidthPx).roundToInt()
                val y = (metrics.padTop + vlayout.topRow(c.startLine) * metrics.lineHeight - vOffset.floatValue).roundToInt()
                layout(constraints.maxWidth, row.height) { row.place(x, y) }
            }
        }
    }
}

@Composable
private fun ConflictButton(label: String, description: String, color: Color, onClick: () -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier.fillMaxHeight()
            .clip(shape)
            // Opaque under the tint, so the marker text it sits over does not show through the label.
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, shape)
            .background(color.copy(alpha = 0.22f), shape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = description }
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Sized to sit inside one code line: a label taller than the line would be cut by the button's clip.
        Text(
            label, color = color, fontSize = 11.sp, lineHeight = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}
