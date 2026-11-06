package dev.ide.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import dev.ide.ui.clipForClipboard
import dev.ide.ui.editor.core.ClipboardHistory
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.paste_history_clear
import dev.ide.ui.generated.resources.paste_history_empty
import dev.ide.ui.generated.resources.paste_history_lines
import dev.ide.ui.generated.resources.paste_history_title
import dev.ide.ui.theme.Ide
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/** Copy or cut [text] from the editor: onto the system clipboard (capped, see [clipForClipboard]) and into the
 *  [ClipboardHistory]. */
@Suppress("DEPRECATION")
internal fun ClipboardManager.copyFromEditor(text: String) {
    val clip = clipForClipboard(text)
    ClipboardHistory.record(clip)
    setText(AnnotatedString(clip))
}

/** The system clipboard's text for a paste, or null when it holds none. What it holds joins the history, which is
 *  how a copy made in another app gets there. */
@Suppress("DEPRECATION")
internal fun ClipboardManager.textForPaste(): String? =
    getText()?.text?.takeIf { it.isNotEmpty() }?.also(ClipboardHistory::record)

/**
 * Paste from History, anchored under the caret line like the code-actions menu: the recent clipboard texts,
 * newest first, each shown as its first lines. A tap pastes that entry (and makes it the newest).
 */
@Composable
internal fun PasteHistoryLayer(
    open: Boolean,
    caretOffset: Int,
    caretGeometry: (Int) -> Triple<Int, Float, Float>,
    metrics: EditorMetrics,
    gutterWidthPx: Float,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!open) return
    val density = LocalDensity.current
    val (_, anchorX, anchorTop) = caretGeometry(caretOffset)
    val lineBottomPx = anchorTop + metrics.lineHeight
    val gapPx = with(density) { 6.dp.roundToPx() }
    val marginPx = with(density) { 8.dp.roundToPx() }
    val position = remember(anchorX, lineBottomPx, gapPx, marginPx) {
        CompletionPopupPositionProvider(
            anchorX.roundToInt().coerceAtLeast(gutterWidthPx.roundToInt()), lineBottomPx.roundToInt(), gapPx, marginPx,
        )
    }
    Popup(popupPositionProvider = position, onDismissRequest = onDismiss) {
        BoxWithConstraints {
            val width = if (maxWidth < 600.dp) (maxWidth * 0.9f).coerceIn(240.dp, 360.dp) else 420.dp
            PasteHistoryList(ClipboardHistory.entries, width, onPick = onPick, onClear = { ClipboardHistory.clear(); onDismiss() })
        }
    }
}

@Composable
internal fun PasteHistoryList(
    entries: List<String>,
    width: androidx.compose.ui.unit.Dp,
    onPick: (String) -> Unit,
    onClear: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .width(width)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(Res.string.paste_history_title),
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (entries.isNotEmpty()) {
                Text(
                    stringResource(Res.string.paste_history_clear),
                    Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClear).padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (entries.isEmpty()) {
            Text(
                stringResource(Res.string.paste_history_empty),
                Modifier.padding(14.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                itemsIndexed(entries, key = { i, e -> "$i:${e.hashCode()}" }) { i, entry ->
                    if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    PasteHistoryRow(entry) { onPick(entry) }
                }
            }
        }
    }
}

@Composable
private fun PasteHistoryRow(entry: String, onClick: () -> Unit) {
    val lines = remember(entry) { entry.count { it == '\n' } + 1 }
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            remember(entry) { entry.trimIndent().lineSequence().take(3).joinToString("\n") },
            style = Ide.type.codeSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (lines > 3) {
            Text(
                pluralStringResource(Res.plurals.paste_history_lines, lines, lines),
                style = MaterialTheme.typography.labelSmall,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
