package dev.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import dev.ide.ui.backend.BuildDiagnosticUi
import dev.ide.ui.backend.BuildState
import dev.ide.ui.backend.ConsoleChunk
import dev.ide.ui.backend.ConsoleChunkKind
import dev.ide.ui.StubBackend
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.IndexUiStatus
import dev.ide.ui.backend.NodeKind
import dev.ide.ui.backend.ProjectInfo
import dev.ide.ui.backend.RunConsoleUi
import dev.ide.ui.backend.RunPhase
import dev.ide.ui.backend.RunStatus
import dev.ide.ui.backend.SymbolHit
import dev.ide.ui.backend.TreeNode
import dev.ide.ui.backend.TreeViewMode
import dev.ide.ui.backend.UiCompletionResult
import dev.ide.ui.backend.UiDiagnostic
import dev.ide.ui.backend.UiSeverity
import dev.ide.ui.theme.Ca
import dev.ide.ui.theme.CodeAssistTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/**
 * Renders [RunScreen] off-screen to a PNG so its layout (terminal body + input bar, and the build-error
 * variant) can be eyeballed without launching the app. Also a guard that the screen renders without a
 * runtime layout error (bad scroll/weight scope, etc.) — `scene.render()` would throw otherwise.
 */
class RunScreenSnapshot {

    private class FakeBackend(
        rc: RunConsoleUi?,
        bs: BuildState,
    ) : StubBackend() {
        override val buildState: StateFlow<BuildState> = MutableStateFlow(bs)
        override val runConsole: StateFlow<RunConsoleUi?> = MutableStateFlow(rc)
    }

    @Test
    fun renderRunningWithInput() {
        val rc = RunConsoleUi(
            id = 1, moduleName = "app", mainClass = "com.example.MainKt",
            phase = RunPhase.Running, acceptsInput = true,
            transcript = listOf(
                ConsoleChunk("What is your name? ", ConsoleChunkKind.OUTPUT),
                ConsoleChunk("World\n", ConsoleChunkKind.INPUT),
                ConsoleChunk("Hello, World!\nFavourite number? ", ConsoleChunkKind.OUTPUT),
            ),
        )
        snapshot("run-running.png", 460, 900, FakeBackend(rc, BuildState(status = RunStatus.Running)))
    }

    @Test
    fun renderBuildFailed() {
        val rc = RunConsoleUi(
            id = 2, moduleName = "app", mainClass = "com.example.MainKt",
            phase = RunPhase.Finished, exitCode = null, transcript = emptyList(),
        )
        val bs = BuildState(
            status = RunStatus.Failed,
            diagnostics = listOf(
                BuildDiagnosticUi(UiSeverity.Error, "unresolved reference: prntln", kind = "compiler", source = "kotlin", file = "/demo/app/src/main/kotlin/com/example/Main.kt", line = 4),
            ),
        )
        snapshot("run-failed.png", 460, 900, FakeBackend(rc, bs))
    }

    @Test
    fun renderRunningLightTheme() {
        val rc = RunConsoleUi(
            id = 3, moduleName = "app", mainClass = "com.example.MainKt",
            phase = RunPhase.Running, acceptsInput = true,
            transcript = listOf(
                ConsoleChunk("What is your name? ", ConsoleChunkKind.OUTPUT),
                ConsoleChunk("World\n", ConsoleChunkKind.INPUT),
                ConsoleChunk("Hello, World!\nFavourite number? ", ConsoleChunkKind.OUTPUT),
            ),
        )
        snapshot("run-running-light.png", 460, 900, FakeBackend(rc, BuildState(status = RunStatus.Running)), dark = false)
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun snapshot(name: String, w: Int, h: Int, backend: IdeBackend, dark: Boolean = true) {
        val scene = ImageComposeScene(width = w, height = h, density = Density(2f)) {
            CodeAssistTheme(dark = dark) {
                Box(Modifier.fillMaxSize().background(Ca.colors.bg)) { RunScreen(backend, onBack = {}) }
            }
        }
        try {
            scene.render()
            val img = scene.render(16_000_000L)
            val png = img.encodeToData(EncodedImageFormat.PNG)!!.bytes
            File("$OUT_DIR/$name").apply { parentFile?.mkdirs() }.writeBytes(png)
            println("wrote snapshot: $OUT_DIR/$name (${png.size} bytes)")
        } finally {
            scene.close()
        }
    }

    private companion object {
        const val OUT_DIR = "/private/tmp/claude-501/-Users-tyronscott-JavaProjects-CodeAssist/b65de382-ff5b-4a3c-9931-04443b19714a/scratchpad"
    }
}
