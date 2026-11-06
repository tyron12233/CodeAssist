package dev.ide.zipcli.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import dev.ide.ui.ext.ToolWindowAnchor
import dev.ide.ui.ext.ToolWindowContext
import dev.ide.ui.ext.ToolWindowContribution
import dev.ide.ui.ext.UiContributionScope
import dev.ide.ui.ext.UiPlugin
import dev.ide.zipcli.ZipCliProject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The ZipCLI terminal as a UI plugin: one BOTTOM tool window, which the build console shows as a tab beside
 * Log / Problems / App log. Co-declared with the engine facet `ZipCliPlugin` in ide-core's `BuiltInPlugins`, so
 * the Settings > Plugins toggle governs both halves.
 */
object ZipCliUiPlugin : UiPlugin {
    override val id: String = "zipcli-ui"

    const val TOOL_WINDOW_ID = "zipcli.terminal"

    override fun contributeUi(scope: UiContributionScope) {
        scope.toolWindow(
            ToolWindowContribution(
                id = TOOL_WINDOW_ID,
                title = "ZipCLI",
                iconId = "terminal",
                anchor = ToolWindowAnchor.BOTTOM,
                order = 50,
                content = { ctx -> ZipCliTerminal(ctx) },
            ),
        )
    }
}

@Composable
private fun ZipCliTerminal(ctx: ToolWindowContext) {
    val root = ctx.backend.project.rootPath
    if (root.isBlank()) {
        Text("Open a project to use the ZipCLI terminal.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val session = remember(root) { ZipCliSessions.forRoot(root) }
    // Re-pointed on every composition so the refresh always goes to the backend that is on screen now.
    SideEffect { session.onFilesMayHaveChanged = { ctx.backend.files.notifyExternalChange() } }
    TerminalPanel(session)
}

/**
 * One [TerminalSession] per project root, kept for the life of the process. Not owned by the composition: the
 * tool window leaves composition whenever another console tab is selected, and the session (scrollback,
 * working directory, a command still running) has to survive that.
 */
private object ZipCliSessions {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sessions = ConcurrentHashMap<String, TerminalSession>()

    fun forRoot(root: String): TerminalSession =
        sessions.computeIfAbsent(root) { TerminalSession(ProjectEngine(ZipCliProject(File(it))), scope) }
}

/** [TerminalEngine] over ZipCLI mounted on the project directory. */
private class ProjectEngine(private val project: ZipCliProject) : TerminalEngine {
    override val initialCwd: String = ZipCliProject.MOUNT

    override suspend fun run(
        script: String,
        onLine: (String) -> Unit,
        confirm: suspend (String) -> Boolean,
    ): TerminalRunResult {
        val outcome = project.run(
            script = script,
            timeoutSeconds = ZipCliProject.MAX_TIMEOUT_SECONDS,
            maxOutputChars = 0, // streamed through onLine; nothing needs the collected copy
            onLine = onLine,
            onConfirm = confirm,
        )
        return TerminalRunResult(outcome.status, outcome.cwd, outcome.timedOut)
    }
}
