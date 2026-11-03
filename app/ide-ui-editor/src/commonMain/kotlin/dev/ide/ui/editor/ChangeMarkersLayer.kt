package dev.ide.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.editor.core.EditorDocument
import dev.ide.ui.editor.core.EditorSession
import dev.ide.ui.editor.core.LineChange
import dev.ide.ui.editor.core.lineChanges
import dev.ide.ui.editor.core.revertEdit
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.change_added
import dev.ide.ui.generated.resources.change_before
import dev.ide.ui.generated.resources.change_revert
import dev.ide.ui.theme.Ide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/** The file's committed text and the buffer's changes against it, kept current by [rememberChangeMarkers]. */
internal class ChangeMarkers(val base: String?, val changes: List<LineChange>) {
    companion object {
        val NONE = ChangeMarkers(null, emptyList())
    }
}

/**
 * The buffer's changes since the last commit. The committed text is fetched when the file opens and again
 * whenever the repository's state changes (a commit, a checkout); the diff runs off the main thread a moment after
 * typing pauses. Nothing for a file outside a repository, not committed yet, or too large.
 */
@Composable
internal fun rememberChangeMarkers(path: String, session: EditorSession, doc: EditorDocument, backend: IdeBackend, largeFile: Boolean): ChangeMarkers {
    var base by remember(path) { mutableStateOf<String?>(null) }
    var markers by remember(path) { mutableStateOf(ChangeMarkers.NONE) }
    val vcsStatus by backend.vcs.status.collectAsState()
    LaunchedEffect(path, vcsStatus, largeFile) {
        base = if (largeFile || !vcsStatus.present) null else runCatching { backend.vcs.baseText(path) }.getOrNull()
    }
    LaunchedEffect(base, doc) {
        val b = base
        if (b == null) { markers = ChangeMarkers.NONE; return@LaunchedEffect }
        delay(250)
        val text = doc.awaitText()
        markers = ChangeMarkers(b, withContext(Dispatchers.Default) { lineChanges(b, text) })
    }
    return markers
}

/**
 * The change stripes in the gutter's gap between the line numbers and the fold strip: green for added lines, amber
 * for modified, and a red wedge where lines were deleted. A tap on one shows what the lines were and offers Revert.
 */
@Composable
internal fun ChangeMarkersLayer(
    session: EditorSession,
    markers: ChangeMarkers,
    metrics: EditorMetrics,
    vlayout: VLayout,
    vOffset: MutableFloatState,
    gutterWidthPx: Float,
    foldStripPx: Float,
) {
    val base = markers.base ?: return
    if (markers.changes.isEmpty()) return
    var open by remember(markers) { mutableStateOf<LineChange?>(null) }
    val density = LocalDensity.current
    val colors = Ide.colors
    val hitWidth = 14.dp
    val hitWidthPx = with(density) { hitWidth.toPx() }
    val stripeX = gutterWidthPx - foldStripPx - with(density) { 2.dp.toPx() } // centred in the 4dp gap
    val doc = session.doc
    for (change in markers.changes) {
        if (change.newStart < doc.lineCount && session.foldModel.isHidden(change.newStart)) continue
        val deleted = change.kind == LineChange.Kind.DELETED
        val rows = if (deleted) 0 else (change.newEnd - change.newStart)
        val color = when (change.kind) {
            LineChange.Kind.ADDED -> colors.gitAdded
            LineChange.Kind.MODIFIED -> colors.gitModified
            LineChange.Kind.DELETED -> colors.gitDeleted
        }
        val firstLine = change.newStart.coerceAtMost(doc.lineCount - 1)
        val heightPx = if (deleted) metrics.lineHeight * 0.6f else
            (vlayout.topRow((change.newEnd).coerceAtMost(doc.lineCount - 1)) - vlayout.topRow(firstLine)).toFloat()
                .coerceAtLeast(rows.toFloat()) * metrics.lineHeight
        key(change.newStart, change.newEnd, change.oldStart) {
            Box(
                Modifier
                    .offset {
                        val lineTop = metrics.padTop + vlayout.topRow(firstLine) * metrics.lineHeight - vOffset.floatValue
                        val top = if (deleted) {
                            // Where the removed lines were: the boundary above [newStart] (below the last line at EOF).
                            lineTop + (if (change.newStart >= doc.lineCount) metrics.lineHeight else 0f) - metrics.lineHeight * 0.3f
                        } else lineTop
                        IntOffset((stripeX - hitWidthPx / 2f).roundToInt(), top.roundToInt())
                    }
                    .width(hitWidth)
                    .height(with(density) { heightPx.toDp() })
                    .clickable { open = change }
                    .drawBehind {
                        val cx = size.width / 2f
                        if (deleted) {
                            val w = 6.dp.toPx()
                            val h = size.height
                            drawPath(
                                Path().apply { moveTo(cx - w / 2f, h / 2f - w / 2f); lineTo(cx + w / 2f, h / 2f); lineTo(cx - w / 2f, h / 2f + w / 2f); close() },
                                color,
                            )
                        } else {
                            val w = 2.5.dp.toPx()
                            drawRect(color, Offset(cx - w / 2f, 0f), Size(w, size.height))
                        }
                    },
            )
        }
    }
    open?.let { change ->
        ChangePopup(
            change = change,
            base = base,
            anchorY = {
                val line = change.newStart.coerceAtMost(doc.lineCount - 1)
                metrics.padTop + vlayout.topRow(line) * metrics.lineHeight - vOffset.floatValue +
                    (change.newEnd - change.newStart).coerceAtLeast(1) * metrics.lineHeight
            },
            gutterWidthPx = gutterWidthPx,
            onRevert = {
                val (s, e, r) = revertEdit(session.doc, base, change)
                session.replaceRange(s, e, r, TextRange(s))
                open = null
            },
            onDismiss = { open = null },
        )
    }
}

/** What the changed lines were in the last commit, and a Revert that puts them back. */
@Composable
private fun ChangePopup(
    change: LineChange,
    base: String,
    anchorY: () -> Float,
    gutterWidthPx: Float,
    onRevert: () -> Unit,
    onDismiss: () -> Unit,
) {
    val density = LocalDensity.current
    val gap = with(density) { 4.dp.roundToPx() }
    val margin = with(density) { 8.dp.roundToPx() }
    val position = remember(gutterWidthPx, gap, margin) {
        CompletionPopupPositionProvider(gutterWidthPx.roundToInt(), anchorY().roundToInt(), gap, margin)
    }
    val old = remember(change, base) { base.split('\n').subList(change.oldStart, change.oldEnd) }
    Popup(popupPositionProvider = position, onDismissRequest = onDismiss) {
        val shape = RoundedCornerShape(12.dp)
        Column(
            Modifier
                .width(340.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, shape)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(if (old.isEmpty()) Res.string.change_added else Res.string.change_before),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(Res.string.change_revert),
                    Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onRevert).padding(horizontal = 10.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (old.isNotEmpty()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .background(Ide.colors.gitDeleted.copy(alpha = 0.10f))
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Text(old.joinToString("\n"), style = Ide.type.codeSmall, color = MaterialTheme.colorScheme.onSurface, softWrap = false)
                }
            }
        }
    }
}
