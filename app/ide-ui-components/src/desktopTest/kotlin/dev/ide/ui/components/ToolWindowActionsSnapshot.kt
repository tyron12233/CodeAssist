package dev.ide.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.CodeAssistTheme
import dev.ide.ui.theme.Ide
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/**
 * Renders a docked pane whose header actions show only on hover: once idle (actions faded out, title kept) and
 * once with the pointer over the pane. Not an assertion; for eyeballing the IntelliJ-style behavior.
 */
class ToolWindowActionsSnapshot {

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun idleThenHovered() {
        val panels = listOf(
            SidebarPanel("files", "Files", CaIcons.docText, 10) {
                ToolWindowHeaderActions(actions = { IconButtonCa(CaIcons.ellipsis, "More", {}, boxSize = 28, iconSize = 16) })
                Text("content")
            },
        )
        val scene = ImageComposeScene(width = 800, height = 300, density = Density(2f)) {
            CodeAssistTheme(dark = true) {
                Row(Modifier.fillMaxSize().background(Ide.colors.editorBg)) {
                    SidebarPane(panels, "files", RailSide.Left, paneWidth = 280.dp, onHide = {}, alwaysShowActions = false)
                    Box(Modifier.width(120.dp).fillMaxHeight())
                }
            }
        }
        val out = File(System.getProperty("java.io.tmpdir"), "codeassist-snapshots").apply { mkdirs() }
        try {
            var t = 0L
            fun settle() = repeat(15) { t += 32_000_000L; scene.render(t) }
            scene.render(); settle()
            File(out, "toolwindow-actions-idle.png").writeBytes(scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes)
            scene.sendPointerEvent(PointerEventType.Enter, Offset(200f, 200f))
            scene.sendPointerEvent(PointerEventType.Move, Offset(210f, 200f))
            settle()
            File(out, "toolwindow-actions-hover.png").writeBytes(scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        } finally {
            scene.close()
        }
    }
}
