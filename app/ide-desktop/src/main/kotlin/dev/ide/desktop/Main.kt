package dev.ide.desktop

import androidx.compose.material.Button
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.ide.core.IdeServicesBackend
import dev.ide.core.project.ProjectManager
import dev.ide.ui.CodeAssistApp
import dev.ide.ui.editor.preview.ImageSceneComposer
import dev.ide.ui.editor.preview.OffscreenPreviewRenderer
import dev.ide.ui.ext.PreviewSnapshots
import dev.ide.ui.platform.LocalWindowTitleBar
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
    // Live @Preview rendering on desktop: the interpreter drives Compose for Desktop (see
    // DesktopComposePreviewHost). The backend instance is stable across project switches.
    val previewHost = DesktopComposePreviewHost(backend)
    // The same previews rendered off screen, for the AI agent to look at a file the user has not opened.
    PreviewSnapshots.registerHeadless(OffscreenPreviewRenderer(backend, previewHost, ImageSceneComposer()))
    application {
        val state = rememberWindowState(size = DpSize(1360.dp, 880.dp))
        Window(
            onCloseRequest = ::exitApplication,
            state = state,
            title = "CodeAssist",
        ) {
            // Remembered: the app state is keyed on it, so a new instance on recomposition (the title bar's height
            // changes whenever the editor is left) would start the app over and land back in the editor.
            val fileActions = remember { DesktopFileActions(backend) }
            // IntelliJ-style: the app draws the title bar (the editor's top bar, or a plain strip elsewhere)
            // with the native window controls on top of it.
            CompositionLocalProvider(LocalWindowTitleBar provides rememberJbrWindowTitleBar(window, state)) {
                CodeAssistApp(
                    backend,
                    fileActions = fileActions,
                    composePreviewHost = previewHost,
                )
            }
        }
    }
    backend.close()
}
