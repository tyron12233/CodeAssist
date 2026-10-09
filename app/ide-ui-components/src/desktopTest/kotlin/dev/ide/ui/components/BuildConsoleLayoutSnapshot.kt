package dev.ide.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.BuildDiagnosticUi
import dev.ide.ui.backend.BuildLogLine
import dev.ide.ui.backend.BuildState
import dev.ide.ui.backend.BuildStepUi
import dev.ide.ui.backend.IndexUiStatus
import dev.ide.ui.backend.RunStatus
import dev.ide.ui.backend.StepStatus
import dev.ide.ui.backend.UiSeverity
import dev.ide.ui.theme.CodeAssistTheme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/**
 * Off-screen renders of the build console after a finished build: the wide (docked bottom pane) layout and the
 * phone sheet's stacked layout. Not an assertion; for eyeballing how much of the pane the controls take.
 */
class BuildConsoleLayoutSnapshot {

    private val tasks = listOf(
        "dexBuilderDebug", "mergeProjectDexDebug", "dexExtLibsDebug", "dexRDexDebug",
        "packageApkDebug", "signDebug", "assembleDebug",
    ).map { ":app:$it" }

    private val state = BuildState(
        status = RunStatus.Succeeded,
        moduleName = "app",
        steps = tasks.map { BuildStepUi(it, StepStatus.Done) },
        log = tasks.map { BuildLogLine("> Task $it", task = it) },
        diagnostics = listOf(BuildDiagnosticUi(UiSeverity.Warning, "minSdk below 26", task = ":app:dexBuilderDebug")),
        elapsedMs = 19_652,
        banner = "This module's minSdk is 21. Below API 26, on-device dexing must desugar the whole library " +
            "classpath, which is significantly slower and re-dexes every library when dependencies change. If " +
            "your app can require API 26+, raising minSdk makes library dexing far faster and cacheable.",
    )

    @Test
    fun renderWideAndPhone() {
        render("build-console-wide.png", 1000, 420) {
            BuildConsole(state, IndexUiStatus(), {}, {}, {}, Modifier.fillMaxSize(), wide = true)
        }
        render("build-console-phone.png", 400, 640) {
            BuildConsole(state, IndexUiStatus(), {}, {}, {}, Modifier.fillMaxSize().padding(14.dp))
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun render(name: String, wDp: Int, hDp: Int, content: @androidx.compose.runtime.Composable () -> Unit) {
        val scene = ImageComposeScene(width = wDp * 2, height = hDp * 2, density = Density(2f)) {
            CodeAssistTheme(dark = true) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow)) { content() }
            }
        }
        try {
            scene.render()
            var t = 0L
            repeat(10) { t += 32_000_000L; scene.render(t) }
            val png = scene.render(t + 300_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
            File(System.getProperty("java.io.tmpdir"), "codeassist-snapshots/$name").apply { parentFile?.mkdirs() }.writeBytes(png)
        } finally {
            scene.close()
        }
    }
}
