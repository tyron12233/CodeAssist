package dev.ide.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.ide.ui.RailDestination
import dev.ide.ui.backend.BuildState
import dev.ide.ui.backend.RunStatus
import dev.ide.ui.screens.BottomNav
import dev.ide.ui.theme.Ca
import dev.ide.ui.theme.CodeAssistTheme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/** Off-screen PNGs of the build dock's states — collapsed (idle / build running with strip+chip) and
 *  expanded to the half detent — so the bar face, chip, and console crossfade can be eyeballed. */
class BuildDockSnapshot {

    @Composable
    private fun DockScreen(open: Boolean, build: BuildState) {
        Box(Modifier.fillMaxSize().background(Ca.colors.editorBg)) {
            Text(
                "fun main() { }",
                color = Ca.colors.textSecondary,
                style = Ca.type.code,
                modifier = Modifier.padding(16.dp),
            )
            BuildDock(
                open = open,
                onOpenChange = {},
                buildState = build,
                modifier = Modifier.align(Alignment.BottomCenter),
                bar = { BottomNav(selected = RailDestination.Search, onSelect = {}) },
            ) {
                Column(Modifier.fillMaxWidth().weight(1f).padding(14.dp)) {
                    Text("Build: app", color = Ca.colors.textPrimary, style = Ca.type.headline)
                    Text("> Task :app:compileJava", color = Ca.colors.textTertiary, style = Ca.type.codeSmall)
                    Text("> Task :app:dexBuilder", color = Ca.colors.textTertiary, style = Ca.type.codeSmall)
                }
            }
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun renderCollapsedIdle() {
        snapshot("build-dock-idle.png", open = false, build = BuildState())
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun renderCollapsedRunning() {
        snapshot("build-dock-running.png", open = false, build = BuildState(status = RunStatus.Running))
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun renderExpandedHalf() {
        snapshot("build-dock-half.png", open = true, build = BuildState(status = RunStatus.Failed))
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun snapshot(name: String, open: Boolean, build: BuildState) {
        val scene = ImageComposeScene(width = 420, height = 720, density = Density(2f)) {
            CodeAssistTheme(dark = true) { DockScreen(open, build) }
        }
        try {
            // Step frames so the expand/chip animations settle (a single big jump can starve them).
            var img = scene.render()
            for (frame in 1..80) img = scene.render(frame * 16_666_667L)
            val png = img.encodeToData(EncodedImageFormat.PNG)!!.bytes
            val out = File(OUT).apply { mkdirs() }.resolve(name)
            out.writeBytes(png)
            println("wrote $out")
        } finally {
            scene.close()
        }
    }

    private companion object {
        /** Overridable so a caller can collect the renders; defaults to the build dir. */
        val OUT: String = System.getProperty("buildDock.snapshot.out")
            ?: "build/snapshots/build-dock"
    }
}
