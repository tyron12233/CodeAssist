package dev.ide.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiLogEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * State and intents for the Logs viewer: the tailed ring buffer plus the level / source / text filters that
 * narrow it. The tail refreshes on its own until the user pauses it.
 */
@Stable
internal class LogsScreenState(
    private val backend: IdeBackend,
    private val scope: CoroutineScope,
) {
    var all: List<UiLogEntry> by mutableStateOf(backend.diagnostics.recentLogs())
        private set
    var filter: LogFilter by mutableStateOf(LogFilter.All)
        private set
    var query: String by mutableStateOf("")
        private set
    var paused: Boolean by mutableStateOf(false)
        private set

    /** The source (plugin id) to show, or null for all. Only surfaced when some record carries a source. */
    var sourceFilter: String? by mutableStateOf(null)
        private set

    /** Every source present in the buffer right now, for the filter chips. */
    val sources: List<String> by derivedStateOf { all.mapNotNull { it.source }.distinct().sorted() }

    /** The selected source, ignored once that plugin's records age out of the ring buffer. */
    val activeSource: String? by derivedStateOf { sourceFilter?.takeIf { it in sources } }

    /** The records the list shows: newest first, narrowed by level, source, and the search text. */
    val shown: List<UiLogEntry> by derivedStateOf {
        val q = query.trim()
        val source = activeSource
        all.asReversed().filter { e ->
            filter.keep(e.level) &&
                (source == null || e.source == source) &&
                (q.isEmpty() || e.message.contains(q, true) || e.tag.contains(q, true) ||
                    (e.source?.contains(q, true) == true) || (e.stackTrace?.contains(q, true) == true))
        }
    }

    init {
        // Live tail: refresh from the ring buffer periodically while the sheet is open (cheap, a snapshot of
        // at most a few hundred records), unless the user paused it so they can read without the list shifting.
        scope.launch {
            snapshotFlow { paused }.collectLatest { isPaused ->
                while (!isPaused) {
                    all = backend.diagnostics.recentLogs()
                    delay(1500)
                }
            }
        }
    }

    fun refresh() { all = backend.diagnostics.recentLogs() }

    fun togglePaused() { paused = !paused }

    fun selectFilter(value: LogFilter) { filter = value }

    fun updateQuery(value: String) { query = value }

    fun selectSource(value: String?) { sourceFilter = value }

    /** Write the buffer to a file and hand it to the host's share sheet. */
    fun export(share: (String) -> Unit) {
        scope.launch { backend.diagnostics.exportLogs()?.let(share) }
    }
}

@Composable
internal fun rememberLogsScreenState(
    backend: IdeBackend,
    scope: CoroutineScope = rememberCoroutineScope(),
): LogsScreenState = remember(backend, scope) { LogsScreenState(backend, scope) }
