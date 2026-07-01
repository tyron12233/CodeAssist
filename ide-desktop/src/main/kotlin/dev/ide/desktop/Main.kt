package dev.ide.desktop

import androidx.compose.material.Button
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.ide.core.IdeServicesBackend
import dev.ide.core.ProjectManager
import dev.ide.ui.CodeAssistApp
import java.nio.file.Path

/**
 * Launches the CodeAssist desktop IDE. Projects live under a real projects root (`~/.codeassist/projects`
 * by default, one workspace dir each); a [ProjectManager] creates/opens/lists them and the IDE supports
 * live in-session switching. The IDE starts on the project picker; a first-run user creates a project from
 * there (or via the onboarding tour's final step).
 */
fun main(args: Array<String>) {
    System.setProperty("apple.awt.application.appearance", "system")
    System.setProperty("apple.awt.application.name", "CodeAssist")
    // Survive an unexpected exception on the AWT event thread (e.g. the live-preview interpreter crashing deep
    // in Compose's measure/semantics pass on a half-typed buffer) instead of taking the whole IDE down.
    AwtThreadGuard.install()

    val projectsRoot = Path.of(
        System.getProperty("codeassist.projects.root")
            ?: "${System.getProperty("user.home")}/.codeassist/projects",
    )
    val manager = ProjectManager.desktop(projectsRoot)

    // Start with no project open: the picker is shown (projectEpoch stays 0), and opening a project from it
    // creates that project's IdeServices on demand. The download cache is still shared across projects via
    // the ProjectManager (sharedCachesRoot = projects-root parent), so deps resolve once.
    val backend = IdeServicesBackend(initial = null, manager = manager)
    application {
        val state = rememberWindowState(size = DpSize(1360.dp, 880.dp))
        Window(
            onCloseRequest = ::exitApplication,
            state = state,
            title = "CodeAssist",
        ) {
            CodeAssistApp(
                backend,
                fileActions = DesktopFileActions(backend),
                // Live @Preview rendering on desktop: the interpreter drives Compose for Desktop (see
                // DesktopComposePreviewHost). The backend instance is stable across project switches.
                composePreviewHost = DesktopComposePreviewHost(backend),
            )
        }
    }
    backend.close()
}
