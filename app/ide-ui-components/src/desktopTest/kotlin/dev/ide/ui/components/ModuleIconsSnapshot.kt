package dev.ide.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import dev.ide.ui.backend.NodeKind
import dev.ide.ui.backend.TreeNode
import dev.ide.ui.theme.CodeAssistTheme
import dev.ide.ui.theme.Ide
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/** Off-screen PNGs of the project tree's module icons (each kind of module, light and dark), to eyeball the
 *  IntelliJ-style folder-with-module-mark art. Not an assertion. */
class ModuleIconsSnapshot {

    private fun tree() = TreeNode(
        "root", "KotlinProject", NodeKind.Workspace, null, iconId = "workspace",
        children = listOf(
            TreeNode("a", "androidApp", NodeKind.Module, null, iconId = "module.android.app",
                children = listOf(TreeNode("s", "src/main/kotlin", NodeKind.SourceRoot, null, iconId = "sourceset.kotlin"))),
            TreeNode("d", "desktopApp", NodeKind.Module, null, iconId = "module"),
            TreeNode("s2", "shared", NodeKind.Module, null, iconId = "module.kmp"),
            TreeNode("l", "mylibrary", NodeKind.Module, null, iconId = "module.android.lib"),
            TreeNode("b", "buildSrc", NodeKind.Folder, null, iconId = "folder"),
        ),
    )

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun renderModuleIcons() {
        for (dark in listOf(true, false)) {
            val scene = ImageComposeScene(width = 1080, height = 900, density = Density(3f)) {
                CodeAssistTheme(dark = dark) {
                    Box(Modifier.fillMaxSize().background(Ide.colors.editorBg)) {
                        FileNavigator(root = tree(), moduleCount = 4, activePath = null, onOpen = {}, modifier = Modifier.fillMaxSize())
                    }
                }
            }
            try {
                var img = scene.render()
                for (frame in 1..30) img = scene.render(frame * 16_666_667L)
                val out = File(OUT_DIR, "module-icons-${if (dark) "dark" else "light"}.png")
                out.writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
                println("wrote snapshot: $out")
            } finally {
                scene.close()
            }
        }
    }

    private companion object {
        val OUT_DIR: File = File(System.getProperty("java.io.tmpdir"), "codeassist-snapshots").apply { mkdirs() }
    }
}
