package dev.ide.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.NodeKind
import dev.ide.ui.backend.TreeNode
import dev.ide.ui.theme.Ca
import dev.ide.ui.theme.CodeAssistTheme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/** Off-screen PNGs of the compact file-tree push drawer (closed → open) so the push geometry, seam
 *  shading, and the restyled navigator rows can be eyeballed without a device. Not an assertion. */
class PushDrawerSnapshot {

    private fun demoTree() = TreeNode(
        "root", "SampleApp", NodeKind.Workspace, null,
        children = listOf(
            TreeNode(
                "app", "app", NodeKind.Module, null, iconId = "module",
                children = listOf(
                    TreeNode(
                        "src", "src/main/java", NodeKind.SourceRoot, null, iconId = "sourceset.java",
                        children = listOf(
                            TreeNode(
                                "pkg", "com.example.app", NodeKind.Package, null, iconId = "package",
                                children = listOf(
                                    TreeNode("f1", "MainActivity.kt", NodeKind.File, "/p/MainActivity.kt", iconId = "kotlin"),
                                    TreeNode("f2", "Util.java", NodeKind.File, "/p/Util.java", iconId = "java"),
                                ),
                            ),
                        ),
                    ),
                    TreeNode(
                        "res", "res", NodeKind.Folder, null, iconId = "folder",
                        children = listOf(
                            TreeNode("f3", "activity_main.xml", NodeKind.File, "/p/activity_main.xml", iconId = "xml"),
                        ),
                    ),
                ),
            ),
            TreeNode("core", "core", NodeKind.Module, null, iconId = "module"),
        ),
    )

    @Composable
    private fun DrawerScreen(open: Boolean) {
        PushDrawer(
            open = open,
            onOpenChange = {},
            drawerContent = {
                FileNavigator(
                    root = demoTree(),
                    moduleCount = 2,
                    activePath = "/p/MainActivity.kt",
                    onOpen = {},
                    modifier = Modifier.fillMaxSize(),
                )
            },
        ) {
            Column(Modifier.fillMaxSize().background(Ca.colors.editorBg)) {
                Box(Modifier.fillMaxWidth().height(52.dp).background(Ca.colors.glassReg))
                Text(
                    "fun main() {\n    println(\"hello\")\n}",
                    color = Ca.colors.textSecondary,
                    style = Ca.type.code,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun renderOpenDrawer() {
        snapshot("push-drawer-open.png", 420, 760) { DrawerScreen(open = true) }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun renderClosedDrawer() {
        snapshot("push-drawer-closed.png", 420, 760) { DrawerScreen(open = false) }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun snapshot(name: String, w: Int, h: Int, content: @Composable () -> Unit) {
        val scene = ImageComposeScene(width = w, height = h, density = Density(2f)) {
            CodeAssistTheme(dark = true) { content() }
        }
        try {
            // Step through frames like a real display would — a single big time jump can leave a pending
            // recomposition eating the frame the animation needed, freezing it mid-flight.
            var img = scene.render()
            for (frame in 1..80) img = scene.render(frame * 16_666_667L)
            val png = img.encodeToData(EncodedImageFormat.PNG)!!.bytes
            val out = "$OUT_DIR/$name"
            File(out).apply { parentFile?.mkdirs() }.writeBytes(png)
            println("wrote snapshot: $out (${png.size} bytes)")
        } finally {
            scene.close()
        }
    }

    private companion object {
        const val OUT_DIR = "/private/tmp/claude-501/-Users-tyronscott-JavaProjects-CodeAssist/3ef37a35-7870-4cde-976f-e90e5e713766/scratchpad"
    }
}
