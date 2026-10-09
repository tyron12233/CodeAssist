package dev.ide.ui.screens

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import dev.ide.ui.IdeUiState
import dev.ide.ui.StubBackend
import dev.ide.ui.backend.BuildState
import dev.ide.ui.backend.FileActions
import dev.ide.ui.backend.IndexUiStatus
import dev.ide.ui.theme.CodeAssistTheme
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/**
 * Off-screen renders of the whole wide editor layout (top bar, tool-window stripes, panes, console), for
 * eyeballing how the window chrome fits together. Not an assertion.
 */
class EditorFrameSnapshot {

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun renderWideLayout() {
        render("editor-frame-desktop.png", 1280, 760, dark = true) { it.consoleOpen = true }
        render("editor-frame-desktop-light.png", 1280, 760, dark = false) { it.consoleOpen = false; it.selectLeftPanel("search") }
        render("editor-frame-landscape.png", 860, 390, dark = true) { it.consoleOpen = false; it.selectLeftPanel("structure") }
        render("editor-frame-phone-drawer.png", 400, 820, dark = true, compact = true) { it.consoleOpen = false; it.selectLeftPanel("files") }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun render(name: String, wDp: Int, hDp: Int, dark: Boolean, compact: Boolean = false, setup: (IdeUiState) -> Unit) {
        val state = IdeUiState(StubBackend(), mainDispatcher = Dispatchers.Unconfined, ioDispatcher = Dispatchers.Unconfined)
        setup(state)
        val scene = ImageComposeScene(width = wDp * 2, height = hDp * 2, density = Density(2f)) {
            CodeAssistTheme(dark = dark) {
                if (compact) CompactLayout(
                    state = state,
                    onToggleTheme = {}, onOpenHub = {}, onOpenIconManager = {},
                    indexStatus = IndexUiStatus(), buildState = BuildState(),
                    onNewFile = { _, _ -> }, onNewFolder = { _, _ -> }, onNewResource = {}, onNewImageAsset = {},
                    onNewSource = { _, _, _ -> }, onFileOp = { _, _ -> },
                    onOpenDependencies = {}, onOpenModuleConfig = {}, onCloseProject = {},
                    fileActions = FileActions.None,
                ) else ExpandedLayout(
                    state = state,
                    onToggleTheme = {}, onOpenHub = {}, onOpenIconManager = {},
                    indexStatus = IndexUiStatus(), buildState = BuildState(),
                    onNewFile = { _, _ -> }, onNewFolder = { _, _ -> }, onNewResource = {}, onNewImageAsset = {},
                    onNewSource = { _, _, _ -> }, onFileOp = { _, _ -> },
                    onOpenDependencies = {}, onOpenModuleConfig = {}, onCloseProject = {},
                    fileActions = FileActions.None,
                )
            }
        }
        try {
            scene.render()
            var t = 0L
            repeat(20) { t += 32_000_000L; scene.render(t) }
            val png = scene.render(t + 500_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
            val out = File(System.getProperty("java.io.tmpdir"), "codeassist-snapshots/$name").apply { parentFile?.mkdirs() }
            out.writeBytes(png)
            println("wrote snapshot: $out")
        } finally {
            scene.close()
        }
    }
}
