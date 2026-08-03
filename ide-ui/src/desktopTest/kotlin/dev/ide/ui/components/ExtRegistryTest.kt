package dev.ide.ui.components

import dev.ide.ui.theme.Ide
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.BuildState
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.IndexUiStatus
import dev.ide.ui.backend.NodeKind
import dev.ide.ui.backend.ProjectInfo
import dev.ide.ui.backend.SymbolHit
import dev.ide.ui.backend.TreeNode
import dev.ide.ui.backend.TreeViewMode
import dev.ide.ui.backend.UiActionPlaces
import dev.ide.ui.backend.UiCompletionResult
import dev.ide.ui.backend.UiDiagnostic
import dev.ide.ui.StubBackend
import dev.ide.ui.ext.UiPluginHost
import dev.ide.ui.ext.ToolWindowAnchor
import dev.ide.ui.ext.ToolWindowContribution
import dev.ide.ui.ext.ToolWindowRegistry
import dev.ide.ui.ext.UiActionHost
import dev.ide.ui.ext.UiActionRegistry
import dev.ide.ui.theme.CodeAssistTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies the Phase B installment-2 seams: a plugin BOTTOM tool window appears as a console tab, and the
 * command palette's UI-navigation commands resolve from [UiActionRegistry]. Snapshots are for eyeballing.
 */
class ExtRegistryTest {

    private class FakeBackend : StubBackend()

    private class RecordingHost(override val backend: IdeBackend) : UiActionHost {
        val navigated = ArrayList<String>()
        var themeToggled = false
        override fun navigate(destination: String) { navigated += destination }
        override fun toggleTheme() { themeToggled = true }
        override fun openFile(path: String, offset: Int) {}
    }

    @Test
    fun paletteUiCommandsResolveFromRegistry() {
        UiPluginHost.ensureLoaded()
        val ids = UiActionRegistry.forPlace(UiActionPlaces.COMMAND_PALETTE, RecordingHost(FakeBackend())).map { it.id }
        assertEquals(
            listOf("ui.hub", "ui.dependencies", "ui.toggleTheme"),
            ids,
            "the palette's UI-navigation commands resolve from the registry, in order",
        )
    }

    @Test
    fun bottomToolWindowRegisters() {
        val reg = ToolWindowRegistry.register(
            ToolWindowContribution("test.logcat", "Logcat", "terminal", ToolWindowAnchor.BOTTOM) {}
        )
        try {
            assertTrue(ToolWindowRegistry.forAnchor(ToolWindowAnchor.BOTTOM).any { it.id == "test.logcat" })
        } finally {
            reg.dispose()
        }
    }

    /** A LEFT-anchored tool window is available to the left activity rail through the same registry the
     *  built-in Files/Search/Structure/Source panels and the RIGHT/BOTTOM windows use (see `buildLeftPanels`). */
    @Test
    fun leftToolWindowRegisters() {
        val reg = ToolWindowRegistry.register(
            ToolWindowContribution("test.explorer", "Explorer", "folder", ToolWindowAnchor.LEFT) {}
        )
        try {
            assertTrue(ToolWindowRegistry.forAnchor(ToolWindowAnchor.LEFT).any { it.id == "test.explorer" })
        } finally {
            reg.dispose()
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun renderConsoleWithPluginTab() {
        val reg = ToolWindowRegistry.register(
            ToolWindowContribution("test.logcat", "Logcat", "terminal", ToolWindowAnchor.BOTTOM) { ctx ->
                Text("Logcat for ${ctx.activeFilePath ?: "no file"}", color = MaterialTheme.colorScheme.onSurface)
            }
        )
        try {
            snapshot("console-toolwindow.png", 760, 360) {
                Box(Modifier.fillMaxSize().background(Ide.colors.editorBg)) {
                    BuildConsole(
                        buildState = BuildState(),
                        indexStatus = IndexUiStatus(),
                        onRun = {}, onStop = {}, onCollapse = {},
                        modifier = Modifier.fillMaxSize().padding(14.dp),
                        backend = FakeBackend(),
                        activeFilePath = "/p/A.kt",
                    )
                }
            }
        } finally {
            reg.dispose()
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun snapshot(name: String, w: Int, h: Int, content: @Composable () -> Unit) {
        val scene = ImageComposeScene(width = w, height = h, density = Density(2f)) {
            CodeAssistTheme(dark = true) { content() }
        }
        try {
            scene.render()
            val img = scene.render(16_000_000L)
            val png = img.encodeToData(EncodedImageFormat.PNG)!!.bytes
            val out = "$OUT_DIR/$name"
            File(out).apply { parentFile?.mkdirs() }.writeBytes(png)
            println("wrote snapshot: $out (${png.size} bytes)")
        } finally {
            scene.close()
        }
    }

    private companion object {
        const val OUT_DIR = "/private/tmp/claude-501/-Users-tyronscott-JavaProjects-CodeAssist/8c43bdb9-8226-43cd-9fd3-d7d5ec53763d/scratchpad"
    }
}
