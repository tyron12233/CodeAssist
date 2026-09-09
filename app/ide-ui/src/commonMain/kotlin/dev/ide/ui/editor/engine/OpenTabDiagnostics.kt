package dev.ide.ui.editor.engine

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import dev.ide.ui.IdeUiState
import dev.ide.ui.OpenFile
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.RunStatus
import dev.ide.ui.editor.core.isLarge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Diagnostics for the open tabs the user is not looking at.
 *
 * [EditorEngineDaemon] runs for the focused tab only, which leaves every other tab with whatever it was last
 * told: a restored session has nothing on any tab until it is clicked, and a tab's status dot can only report
 * the file already on screen. This sweep fills that in, one diagnostics pass per tab, when a tab opens and
 * again for all of them when the workspace index or a build settles (both change what analysis concludes: a
 * classpath appears, generated sources land).
 *
 * Deliberately narrow next to the daemon: one pass rather than five, one tab at a time with a gap between
 * them so the focused tab's daemon keeps the engine, never the focused tab itself, and nothing at all while
 * indexing is in flight or the user has on-the-fly analysis switched off. Large files opt out for the same
 * reason the daemon suppresses its heavy passes on them: parsing one builds an AST many times the size of
 * the source, which is what OOMs a low-heap device.
 */
internal class OpenTabDiagnosticsSweep(
    private val backend: IdeBackend,
    /** Yield between tabs, so a sweep of many tabs never holds the engine against the focused editor. */
    private val gapBetweenTabs: Duration = 40.milliseconds,
) {
    // tabId -> the generation the tab's diagnostics were produced in. A tab that opts out (read-only, large,
    // focused) is stamped too: it needs no pass in this generation.
    private val stamped = mutableMapOf<Long, Int>()

    /**
     * Analyze every tab in [tabs] that has not been handled in [generation], skipping the tab at [activePath].
     * Returns the paths it analyzed, for tests and diagnostics.
     */
    suspend fun sweep(tabs: List<OpenFile>, activePath: String?, generation: Int): List<String> {
        val analyzed = ArrayList<String>()
        for (file in tabs) {
            if (stamped[file.tabId] == generation) continue
            if (file.readOnly || file.path == activePath || file.session.doc.isLarge()) {
                stamped[file.tabId] = generation
                continue
            }
            if (analyzed.isNotEmpty()) delay(gapBetweenTabs)
            val text = file.session.doc.text
            val result = try {
                backend.editor.updateDocument(file.path, text)
                backend.editor.analyze(file.path, text)
            } catch (c: CancellationException) {
                throw c // a tab opened/closed, or the screen went away: the next sweep picks this up
            } catch (_: Throwable) {
                null // preempted by a higher-priority call, or a backend that cannot analyze this file
            }
            if (result == null) continue // unstamped, so the next sweep retries it
            // Drop a result the buffer has moved past: the user switched to this tab and typed while the
            // pass ran, and the daemon's own pass owns it now.
            if (file.session.doc.text == text) file.session.applyAnalysis(result)
            stamped[file.tabId] = generation
            analyzed += file.path
        }
        return analyzed
    }
}

/**
 * Drives [OpenTabDiagnosticsSweep] for the open tabs: a sweep per generation, restarted when the set of open
 * tabs or the focused tab changes (already-stamped tabs are not redone, so a restart costs nothing).
 */
@Composable
internal fun OpenTabDiagnosticsEffect(state: IdeUiState, indexBuilding: Boolean, buildStatus: RunStatus) {
    val sweep = remember(state.backend) { OpenTabDiagnosticsSweep(state.backend) }
    var generation by remember(state.backend) { mutableStateOf(0) }
    // The index finishing a build changes what analysis resolves, and so does a build (it writes generated
    // sources and R classes). Each transition is one new generation, which re-sweeps every open tab.
    LaunchedEffect(state.backend, indexBuilding) { if (!indexBuilding) generation++ }
    LaunchedEffect(state.backend, buildStatus) {
        if (buildStatus == RunStatus.Succeeded || buildStatus == RunStatus.Failed) generation++
    }
    val enabled = state.analyzeOnTheFly && !indexBuilding
    LaunchedEffect(state.backend, generation, enabled) {
        if (!enabled) return@LaunchedEffect
        snapshotFlow { state.openFiles.map { it.tabId } to state.active?.path }
            .collectLatest { (_, activePath) -> sweep.sweep(state.openFiles.toList(), activePath, generation) }
    }
}
