package dev.ide.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.ide.ui.IdeUiState
import dev.ide.ui.backend.UiToolchainWarning
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * State and intents for the toolchain-problem strip: the problems the open project has, which of them the
 * user collapsed or dismissed for this session, and the fix / accept actions.
 *
 * Dismissal is per session: it hides a problem without recording anything, so it comes back rather than
 * being silently forgotten.
 */
@Stable
internal class ToolchainWarningState(
    private val state: IdeUiState,
    private val scope: CoroutineScope,
) {
    var warnings: List<UiToolchainWarning> by mutableStateOf(emptyList())
        private set
    var dismissed: Set<String> by mutableStateOf(emptySet())
        private set
    var expanded: Set<String> by mutableStateOf(emptySet())
        private set
    var listOpen: Boolean by mutableStateOf(false)
        private set

    /** The problem whose action is running, or null when idle. */
    var busyId: String? by mutableStateOf(null)
        private set

    /** The last action's message per problem, shown in place of its explanation. */
    var results: Map<String, String> by mutableStateOf(emptyMap())
        private set

    /** The problems still worth showing. */
    val shown: List<UiToolchainWarning> get() = warnings.filterNot { it.id in dismissed }

    init {
        // Read on open, after an action, and once a dependency resolve settles (the versions may just have
        // changed from the Dependencies screen, which is the other way this gets fixed).
        scope.launch {
            state.backend.deps.depsState.map { it.resolving }.distinctUntilChanged().collect { resolving ->
                if (!resolving) reload()
            }
        }
    }

    fun toggleList() { listOpen = !listOpen }

    fun toggleDetail(id: String) {
        expanded = if (id in expanded) expanded - id else expanded + id
    }

    fun dismiss(id: String) { dismissed = dismissed + id }

    fun dismissAll() { dismissed = dismissed + shown.map { it.id } }

    /** Apply the offered fix (a version bump, a toolchain switch), then re-analyze what is open. */
    fun fix(warning: UiToolchainWarning) {
        run(warning) { state.backend.modules.fixToolchainWarning(warning.moduleName, warning.id) }
    }

    /** Acknowledge the problem and build anyway. */
    fun accept(warning: UiToolchainWarning) {
        run(warning, reanalyze = false) {
            state.backend.modules.acceptToolchainWarning(warning.moduleName, warning.id)
        }
    }

    private fun run(
        warning: UiToolchainWarning,
        reanalyze: Boolean = true,
        action: suspend () -> dev.ide.ui.backend.UiConfigResult,
    ) {
        busyId = warning.id
        scope.launch {
            val result = action()
            results = results + (warning.id to result.message)
            busyId = null
            reload()
            if (reanalyze && result.success) state.reanalyzeOpenFiles()
        }
    }

    private suspend fun reload() {
        warnings = runCatching { state.backend.modules.toolchainWarnings() }.getOrDefault(emptyList())
    }
}

@Composable
internal fun rememberToolchainWarningState(
    state: IdeUiState,
    scope: CoroutineScope = rememberCoroutineScope(),
): ToolchainWarningState = remember(state, state.backend.project.rootPath, scope) {
    ToolchainWarningState(state, scope)
}
