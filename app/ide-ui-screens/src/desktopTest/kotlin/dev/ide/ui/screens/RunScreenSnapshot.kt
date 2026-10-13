package dev.ide.ui.screens

import androidx.compose.material3.MaterialTheme
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
import dev.ide.ui.backend.TestOutput
import dev.ide.ui.backend.TreeNode
import dev.ide.ui.backend.TreeViewMode
import dev.ide.ui.backend.UiCompletionResult
import dev.ide.ui.backend.UiDiagnostic
import dev.ide.ui.backend.UiSeverity
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

    /** A result record as the IDE's test runner prints it (see TestOutput). */
    private fun record(event: String, cls: String = "", method: String = "", ms: Long = 0, message: String = "") =
        "${TestOutput.MARK}$event ${cls.length}:$cls ${method.length}:$method $ms ${message.length}:$message\n"

    private fun testTranscript(finished: Boolean): List<ConsoleChunk> {
        val greeter = "com.example.GreeterTest"
        val calc = "com.example.calc.CalculatorTest"
        val all = listOf(greeter to "greets by name", greeter to "greets the world", greeter to "seesInternals", calc to "adds", calc to "divides by zero", calc to "parses", calc to "slowPath",
            calc to "squares[1] 2, 4", calc to "squares[2] 3, 9", calc to "squares[3] 4, 15")
        val text = StringBuilder()
        all.forEach { (c, m) -> text.append(record("QUEUED", c, m)) }
        text.append(record("STARTED", greeter, "greets by name")).append("printed by a test\n").append(record("PASSED", greeter, "greets by name", 12))
        text.append(record("STARTED", greeter, "greets the world")).append(record("PASSED", greeter, "greets the world", 3))
        text.append(record("STARTED", greeter, "seesInternals")).append(record("FAILED", greeter, "seesInternals", 5, "java.lang.AssertionError: Expected <42>, actual <41>."))
        text.append(record("STARTED", calc, "adds")).append(record("PASSED", calc, "adds", 1))
        text.append(record("STARTED", calc, "divides by zero"))
        if (finished) {
            text.append(record("FAILED", calc, "divides by zero", 8, "org.opentest4j.AssertionFailedError: Expected java.lang.ArithmeticException to be thrown, but nothing was thrown."))
            text.append(record("SKIPPED", calc, "parses", 0, "not on this platform"))
            text.append(record("STARTED", calc, "slowPath")).append(record("PASSED", calc, "slowPath", 1240))
            text.append(record("PASSED", calc, "squares[1] 2, 4", 2)).append(record("PASSED", calc, "squares[2] 3, 9", 1))
            text.append(record("FAILED", calc, "squares[3] 4, 15", 1, "org.opentest4j.AssertionFailedError: expected: <15> but was: <16>"))
            text.append(record("FINISHED"))
        }
        return listOf(ConsoleChunk(text.toString(), ConsoleChunkKind.OUTPUT)) +
            if (finished) listOf(ConsoleChunk("\nProcess finished with exit code 1\n", ConsoleChunkKind.SYSTEM)) else emptyList()
    }

    @Test
    fun renderTestRunFinished() {
        val rc = RunConsoleUi(id = 4, moduleName = "app", mainClass = "Tests in app", phase = RunPhase.Finished, exitCode = 1, transcript = testTranscript(true))
        snapshot("run-tests.png", 824, 1500, FakeBackend(rc, BuildState(status = RunStatus.Failed)))
        snapshot("run-tests-narrow.png", 460, 900, FakeBackend(rc, BuildState(status = RunStatus.Failed)))
        snapshot("run-tests-light.png", 824, 1500, FakeBackend(rc, BuildState(status = RunStatus.Failed)), dark = false)
        snapshot("run-tests-wide.png", 1200, 420, FakeBackend(rc, BuildState(status = RunStatus.Failed)), docked = true)
    }

    @Test
    fun renderTestRunInProgress() {
        val rc = RunConsoleUi(id = 5, moduleName = "app", mainClass = "Tests in app", phase = RunPhase.Running, acceptsInput = true, transcript = testTranscript(false))
        snapshot("run-tests-running.png", 460, 900, FakeBackend(rc, BuildState(status = RunStatus.Running)))
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun snapshot(name: String, w: Int, h: Int, backend: IdeBackend, dark: Boolean = true, docked: Boolean = false) {
        val scene = ImageComposeScene(width = w, height = h, density = Density(2f)) {
            CodeAssistTheme(dark = dark) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    RunScreen(backend, onBack = {}, onHide = if (docked) ({}) else null, onOpenSource = { _, _ -> })
                }
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
        val OUT_DIR: String = java.io.File(System.getProperty("java.io.tmpdir"), "codeassist-snapshots").apply { mkdirs() }.absolutePath
    }
}
