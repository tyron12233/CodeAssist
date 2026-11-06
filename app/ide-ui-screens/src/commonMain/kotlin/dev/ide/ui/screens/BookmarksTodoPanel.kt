package dev.ide.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ide.ui.IdeUiState
import dev.ide.ui.backend.UiSearchOptions
import dev.ide.ui.backend.UiTextMatch
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.bookmarks_empty
import dev.ide.ui.generated.resources.bookmarks_title
import dev.ide.ui.generated.resources.todo_empty
import dev.ide.ui.generated.resources.todo_refresh
import dev.ide.ui.generated.resources.todo_title
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ide
import org.jetbrains.compose.resources.stringResource

// A TODO or FIXME after a comment marker (line, block or doc comment, `#`, or an XML comment), so the word inside
// a string or a name is not one.
private const val TODO_PATTERN = """(//|/\*|\*|#|<!--)\s*(TODO|FIXME)\b"""

/**
 * Bookmarks and TODOs: the project's bookmarked lines (tap to go, ✕ to remove), then every TODO and FIXME comment
 * in its files, found with the same search Find in Files runs (refreshed when the panel opens and on Refresh).
 */
@Composable
internal fun BookmarksTodoPanel(state: IdeUiState, onNavigated: () -> Unit, modifier: Modifier = Modifier) {
    var todos by remember { mutableStateOf<List<UiTextMatch>?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(refresh) {
        todos = runCatching {
            state.backend.search.findInFiles(TODO_PATTERN, UiSearchOptions(caseSensitive = true, regex = true), limit = 500)
        }.getOrDefault(emptyList())
    }
    val marks = state.bookmarks.entries.sortedBy { it.key }.flatMap { (path, lines) -> lines.sorted().map { path to it } }
    LazyColumn(modifier) {
        item { SectionHeader(stringResource(Res.string.bookmarks_title)) }
        if (marks.isEmpty()) {
            item { Hint(stringResource(Res.string.bookmarks_empty)) }
        }
        items(marks, key = { "b:${it.first}:${it.second}" }) { (path, line) ->
            val open = state.openFiles.firstOrNull { it.path == path }
            val text = open?.session?.doc?.takeIf { line < it.lineCount }?.let { it.substring(it.lineStart(line), it.lineEnd(line)).trim() }
            Row(
                Modifier.fillMaxWidth().clickable { state.openAtLine(path, line + 1, 1); onNavigated() }.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(CaIcons.pin, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text("${path.substringAfterLast('/')}:${line + 1}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                    if (!text.isNullOrEmpty()) {
                        Text(text, style = Ide.type.codeSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Text(
                    "✕",
                    Modifier.clickable { state.removeBookmark(path, line) }.padding(horizontal = 12.dp, vertical = 6.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionHeader(stringResource(Res.string.todo_title), Modifier.weight(1f))
                Text(
                    stringResource(Res.string.todo_refresh),
                    Modifier.clickable { refresh++ }.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        val list = todos
        if (list != null && list.isEmpty()) item { Hint(stringResource(Res.string.todo_empty)) }
        if (list != null) {
            items(list, key = { "t:${it.filePath}:${it.offset}" }) { m ->
                Column(
                    Modifier.fillMaxWidth().clickable { state.openAt(m.filePath, m.offset); onNavigated() }.padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    // From the TODO or FIXME itself to the end of the line, without a block comment's closer.
                    val note = (TODO_WORD.find(m.lineText)?.let { m.lineText.substring(it.range.first) } ?: m.lineText.trim())
                        .trim().removeSuffix("*/").removeSuffix("-->").trim()
                    Text(
                        note, style = Ide.type.codeSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = if (note.startsWith("FIXME")) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    Text("${m.fileName}:${m.line}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private val TODO_WORD = Regex("""\b(TODO|FIXME)\b""")

@Composable
private fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier.padding(start = 16.dp, top = 14.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Hint(text: String) {
    Text(text, Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
