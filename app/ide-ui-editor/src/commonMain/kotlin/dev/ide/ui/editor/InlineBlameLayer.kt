package dev.ide.ui.editor

import androidx.compose.foundation.layout.offset
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiBlameLine
import dev.ide.ui.editor.core.EditorSession
import dev.ide.ui.editor.core.baseLineFor
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.blame_uncommitted
import dev.ide.ui.theme.Ide
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/**
 * Who last changed the caret's line, faded after the line's end: `Ana Lima, 3 days ago · Fix the parser`. A line
 * changed since the last commit says so instead. Blame is read once per file and repository state (it is per HEAD
 * line, so typing does not re-read it; the buffer's lines map onto HEAD's through the [markers]), and the label
 * shows once the caret has rested on a line for a moment, so moving through the code does not flicker it.
 */
@Composable
internal fun InlineBlameLayer(
    path: String,
    session: EditorSession,
    backend: IdeBackend,
    markers: ChangeMarkers,
    caretOffset: Int,
    caretGeometry: (Int) -> Triple<Int, Float, Float>,
    metrics: EditorMetrics,
    largeFile: Boolean,
) {
    val status by backend.vcs.status.collectAsState()
    var blame by remember(path) { mutableStateOf<List<UiBlameLine?>>(emptyList()) }
    LaunchedEffect(path, status, largeFile) {
        blame = if (largeFile || !status.present) emptyList() else runCatching { backend.vcs.blame(path) }.getOrDefault(emptyList())
    }
    if (blame.isEmpty() || markers.base == null || !session.selection.collapsed) return
    val doc = session.doc
    val line = doc.lineForOffset(caretOffset.coerceIn(0, doc.length))
    // Shown after the caret rests: keyed on the line, so moving within it keeps the label up.
    var restedOn by remember { mutableStateOf(-1) }
    LaunchedEffect(line) {
        restedOn = -1
        delay(450)
        restedOn = line
    }
    if (restedOn != line) return
    val baseLine = baseLineFor(markers.changes, line)
    val entry = baseLine?.let { blame.getOrNull(it) }
    val label = when {
        baseLine == null -> stringResource(Res.string.blame_uncommitted)
        entry == null -> return
        else -> "${entry.author}, ${entry.age} · ${entry.summary}"
    }
    val gap = with(LocalDensity.current) { 24.dp.toPx() }
    Text(
        label,
        modifier = Modifier.offset {
            val (_, endX, top) = caretGeometry(doc.lineEnd(line))
            IntOffset((endX + gap).roundToInt(), (top + (metrics.lineHeight - 15.dp.toPx()) / 2f).roundToInt())
        },
        style = Ide.type.codeSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
        maxLines = 1,
        overflow = TextOverflow.Clip,
        softWrap = false,
    )
}
