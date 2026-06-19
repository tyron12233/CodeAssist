package dev.ide.desktop

import androidx.compose.material.Button
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.ide.core.IdeServices
import dev.ide.core.IdeServicesBackend
import dev.ide.core.ProjectManager
import dev.ide.platform.log.ConsoleLogSink
import dev.ide.platform.log.Log
import dev.ide.ui.CodeAssistApp
import java.nio.file.Path

/**
 * Launches the CodeAssist desktop IDE. Projects live under a real projects root (`~/.codeassist/projects`
 * by default, one workspace dir each); a [ProjectManager] creates/opens/lists them and the IDE supports
 * live in-session switching. On first launch (no projects yet) the rich Android multi-module sample
 * (`app → feature → core`) is seeded so the picker isn't empty.
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

    // Active engine: the most recent existing project, or a freshly-seeded sample on first launch.
    val services = if (manager.isEmpty()) {
        // Share the download cache with every later project (sibling of projects/, so deps resolve once).
        IdeServices.bootstrapDemo(projectsRoot.resolve("android-sample"), sharedCachesRoot = projectsRoot.parent ?: projectsRoot)
    } else {
        manager.open(manager.list().first().rootPath)
    }

    // Headless mode: ensure a project exists and exit (CI/smoke checks, no display needed).
    if ("--generate-only" in args || System.getProperty("codeassist.generateOnly") == "true") {
        println("Projects at $projectsRoot: " + manager.list().joinToString { it.name })
        services.close()
        return
    }

    Log.addSink(ConsoleLogSink())

    val backend = IdeServicesBackend(services, manager)
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
