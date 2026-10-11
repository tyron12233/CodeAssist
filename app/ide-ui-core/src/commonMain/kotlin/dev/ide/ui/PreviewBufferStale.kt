package dev.ide.ui

import androidx.compose.runtime.State
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Whether the buffer a [ComposePreviewHost] was handed has already been edited past.
 *
 * The preview pane hands its host a DEBOUNCED copy of the buffer, so a host's lowering pass is keyed on that
 * copy and keeps going when typing resumes. Lowering an outdated buffer is pure waste, and worse than waste: the
 * engine re-parses the file at that old text, rewinding the analyzer the editor is about to need at the new
 * one. The pane provides `true` here from the first keystroke after the copy was taken until the next copy;
 * a host runs its pass through [whileBufferCurrent] so it stands down meanwhile. Defaults to never stale, for
 * callers (lessons, tests) whose text doesn't move under them.
 */
val LocalPreviewBufferStale = staticCompositionLocalOf<State<Boolean>> { NeverStale }

private object NeverStale : State<Boolean> {
    override val value: Boolean get() = false
}

/**
 * Run [pass] while [stale] is false: wait for it to clear before starting, and when it turns true mid-way,
 * cancel the pass and start it again from the top once the buffer is current again (the same text can come
 * back, e.g. a character typed and deleted inside the debounce). Returns when a pass runs to completion.
 */
suspend fun whileBufferCurrent(stale: State<Boolean>, pass: suspend () -> Unit) {
    while (true) {
        snapshotFlow { stale.value }.first { !it }
        val completed = coroutineScope {
            val work = async { pass() }
            val watch = launch {
                snapshotFlow { stale.value }.first { it }
                work.cancel()
            }
            try {
                work.await()
                true
            } catch (e: CancellationException) {
                // Our own caller being cancelled ends this; only the watcher's cancel means "go round again".
                currentCoroutineContext().ensureActive()
                false
            } finally {
                watch.cancel()
            }
        }
        if (completed) return
    }
}
