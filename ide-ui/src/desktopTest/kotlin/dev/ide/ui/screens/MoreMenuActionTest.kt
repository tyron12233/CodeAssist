package dev.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
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
import dev.ide.ui.ext.UiActionHost
import dev.ide.ui.ext.UiActionRegistry
import dev.ide.ui.ext.UiDestinations
import dev.ide.ui.theme.Ca
import dev.ide.ui.theme.CodeAssistTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies the More-menu migration onto the UI-side action registry (Phase B): the built-ins resolve in the
 * right order through [UiActionRegistry], invoking one routes to the [UiActionHost], and the real
 * [MoreSheetContent] renders them (off-screen PNG for eyeballing).
 */
class MoreMenuActionTest {

    private class FakeBackend : StubBackend() {
        var reindexed = false
        override fun reindex() { reindexed = true }
    }

    private class RecordingHost(override val backend: IdeBackend) : UiActionHost {
        val navigated = ArrayList<String>()
        var themeToggled = false
        override fun navigate(destination: String) { navigated += destination }
        override fun toggleTheme() { themeToggled = true }
        override fun openFile(path: String, offset: Int) {}
    }

    @Test
    fun builtInsResolveInOrder() {
        UiPluginHost.ensureLoaded()
        val host = RecordingHost(FakeBackend())
        val ids = UiActionRegistry.forPlace(UiActionPlaces.MORE_MENU, host).map { it.id }
        assertEquals(
            listOf("ui.hub", "ui.modules", "ui.reindex", "ui.logs", "ui.toggleTheme", "ui.closeProject"),
            ids,
            "the More-menu built-ins resolve from the registry in their declared order",
        )
    }

    @Test
    fun invokingAnActionRoutesToTheHost() {
        UiPluginHost.ensureLoaded()
        val backend = FakeBackend()
        val host = RecordingHost(backend)
        val actions = UiActionRegistry.forPlace(UiActionPlaces.MORE_MENU, host).associateBy { it.id }

        actions.getValue("ui.hub").perform(host)
        actions.getValue("ui.toggleTheme").perform(host)
        actions.getValue("ui.reindex").perform(host)

        assertEquals(listOf(UiDestinations.HUB), host.navigated)
        assertTrue(host.themeToggled, "Toggle theme routes to host.toggleTheme()")
        assertTrue(backend.reindexed, "Re-index routes to backend.search.reindex()")
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun renderMoreMenu() {
        UiPluginHost.ensureLoaded()
        val backend = FakeBackend()
        val host = RecordingHost(backend)
        snapshot("more-menu.png", 520, 560) {
            Box(Modifier.fillMaxSize().background(Ca.colors.glassThick)) {
                MoreSheetContent(backend = backend, host = host, modifier = Modifier.fillMaxWidth())
            }
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
