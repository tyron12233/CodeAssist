package dev.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import dev.ide.ui.IdeUiState
import dev.ide.ui.OpenFile
import dev.ide.ui.backend.UiHistoryVersion
import dev.ide.ui.editor.core.LineChange
import dev.ide.ui.editor.core.lineChanges
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.history_back
import dev.ide.ui.generated.resources.history_empty
import dev.ide.ui.generated.resources.history_restore
import dev.ide.ui.generated.resources.history_same
import dev.ide.ui.generated.resources.history_title
import dev.ide.ui.generated.resources.notif_days
import dev.ide.ui.generated.resources.notif_hours
import dev.ide.ui.generated.resources.notif_just_now
import dev.ide.ui.generated.resources.notif_minutes
import dev.ide.ui.theme.Ide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock

/**
 * Local history for the active file: its saved versions, newest first. Picking one shows what changed between it
 * and the buffer now (lines the version had in red, lines the buffer has now in green), and Restore puts that
 * version back in the buffer as one undoable edit; nothing is written until the next save.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LocalHistorySheet(state: IdeUiState, file: OpenFile, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var versions by remember(file.path) { mutableStateOf<List<UiHistoryVersion>?>(null) }
    var picked by remember(file.path) { mutableStateOf<UiHistoryVersion?>(null) }
    var pickedText by remember(file.path) { mutableStateOf<String?>(null) }
    LaunchedEffect(file.path) { versions = state.backend.editor.localHistory(file.path) }
    LaunchedEffect(picked) { pickedText = picked?.let { state.backend.editor.localHistoryText(file.path, it.id) } }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(Res.string.history_title), style = MaterialTheme.typography.headlineSmall)
                    Text(file.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val p = picked
                if (p != null) {
                    TextButton(onClick = { picked = null }) { Text(stringResource(Res.string.history_back)) }
                    val text = pickedText
                    TextButton(
                        enabled = text != null,
                        onClick = {
                            val s = file.session
                            if (text != null) s.replaceRange(0, s.doc.length, text, TextRange(s.selection.start.coerceAtMost(text.length)))
                            onDismiss()
                        },
                    ) { Text(stringResource(Res.string.history_restore)) }
                }
            }
            HorizontalDivider()
            val p = picked
            if (p == null) {
                val list = versions
                when {
                    list == null -> {}
                    list.isEmpty() -> Text(
                        stringResource(Res.string.history_empty),
                        Modifier.padding(24.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> LazyColumn(Modifier.heightIn(max = 520.dp)) {
                        items(list, key = { it.id }) { v ->
                            Row(
                                Modifier.fillMaxWidth().clickable { picked = v }.padding(horizontal = 24.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(ago(v.timeMs), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "${(v.sizeChars + 512) / 1024} KB".takeIf { v.sizeChars >= 1024 } ?: "${v.sizeChars} B",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        }
                    }
                }
            } else {
                val old = pickedText
                if (old != null) VersionDiff(old, file.session.doc.text)
            }
        }
    }
}

/** The stretches that differ between [version] and [now], each with a line of context either side. */
@Composable
private fun VersionDiff(version: String, now: String) {
    var changes by remember(version, now) { mutableStateOf<List<LineChange>?>(null) }
    LaunchedEffect(version, now) { changes = withContext(Dispatchers.Default) { lineChanges(version, now) } }
    val list = changes ?: return
    if (list.isEmpty()) {
        Text(stringResource(Res.string.history_same), Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val oldLines = remember(version) { version.split('\n') }
    val newLines = remember(now) { now.split('\n') }
    val removed = Ide.colors.gitDeleted.copy(alpha = 0.16f)
    val added = Ide.colors.gitAdded.copy(alpha = 0.16f)
    // Inside a sideways scroll a row cannot fill the width, so each is given the sheet's width as its minimum:
    // the red and green bands then run edge to edge however short their line.
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val rowMin = maxWidth
        Column(
            Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
        ) {
            for (c in list) {
                val context = (c.oldStart - 1).takeIf { it >= 0 }
                if (context != null) DiffLine(" ", oldLines[context], null, rowMin)
                for (i in c.oldStart until c.oldEnd) DiffLine("-", oldLines[i], removed, rowMin)
                for (i in c.newStart until c.newEnd) DiffLine("+", newLines[i], added, rowMin)
                Text("⋯", Modifier.padding(horizontal = 16.dp, vertical = 2.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun DiffLine(sign: String, text: String, background: androidx.compose.ui.graphics.Color?, minWidth: androidx.compose.ui.unit.Dp) {
    Row(
        Modifier.widthIn(min = minWidth).then(if (background != null) Modifier.background(background) else Modifier).padding(horizontal = 16.dp, vertical = 1.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(sign, style = Ide.type.codeSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = Ide.type.codeSmall, color = MaterialTheme.colorScheme.onSurface, softWrap = false)
    }
}

@Composable
private fun ago(timeMs: Long): String {
    val minutes = ((Clock.System.now().toEpochMilliseconds() - timeMs).coerceAtLeast(0)) / 60_000
    return when {
        minutes < 1 -> stringResource(Res.string.notif_just_now)
        minutes < 60 -> stringResource(Res.string.notif_minutes, minutes.toInt())
        minutes < 60 * 24 -> stringResource(Res.string.notif_hours, (minutes / 60).toInt())
        else -> stringResource(Res.string.notif_days, (minutes / (60 * 24)).toInt())
    }
}
