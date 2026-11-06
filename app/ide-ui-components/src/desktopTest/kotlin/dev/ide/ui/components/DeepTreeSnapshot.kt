package dev.ide.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import dev.ide.ui.backend.NodeKind
import dev.ide.ui.backend.PackageSegment
import dev.ide.ui.backend.TreeNode
import dev.ide.ui.backend.TreeViewMode
import dev.ide.ui.platform.LocalMouseInput
import dev.ide.ui.theme.CodeAssistTheme
import dev.ide.ui.theme.Ide
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/**
 * Off-screen PNG of a deep All-Files tree in a phone-width drawer, with touch sizing: compacted folder chains,
 * indent guides, and source files without their extension. Not an assertion; for eyeballing the layout.
 */
class DeepTreeSnapshot {

    private fun folder(path: String, icon: String = "folder", vararg children: TreeNode): TreeNode {
        val labels = path.split('/')
        val segs = if (labels.size > 1) labels.indices.map { k ->
            PackageSegment(labels.subList(0, k + 1).joinToString("/"), "/p/" + labels.subList(0, k + 1).joinToString("/"))
        } else emptyList()
        return TreeNode("dir:/p/$path", path, NodeKind.Folder, null, icon, children.toList(), packageSegments = segs, dirPath = "/p/$path")
    }

    private fun java(name: String) = TreeNode("file:$name", "$name.java", NodeKind.File, "/p/$name.java", "java")

    private fun tree() = TreeNode(
        "workspace", "AssemblyLab", NodeKind.Workspace, null,
        children = listOf(
            folder(
                "src/main", "folder",
                folder(
                    "java/com/tanvir/assemblylab", "sourceset.java",
                    folder(
                        "stage06_analysis", "folder",
                        java("AnalysisManager"), java("AnalysisReport"), java("AnalysisSection"),
                    ),
                    java("DotNetFieldReader"), java("DotNetIdentityResolver"), java("DotNetMetadataStream"),
                    java("DotNetMethodBodyDecoder"), java("DotNetTablesReader"), java("DotNetTypeDefinition"),
                ),
            ),
            TreeNode("file:b", "build.gradle.kts", NodeKind.File, "/p/build.gradle.kts", "kotlin"),
        ),
    )

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun renderDeepTreeAtPhoneWidth() {
        val root = tree()
        val expanded = mutableStateMapOf<String, Boolean>()
        fun walk(n: TreeNode) { if (n.children.isNotEmpty()) expanded[n.id] = true; n.children.forEach(::walk) }
        walk(root)
        // 304dp: the push drawer on a 360dp phone (screen width minus the 56dp peek).
        val scene = ImageComposeScene(width = 608, height = 900, density = Density(2f)) {
            CodeAssistTheme(dark = false) {
                CompositionLocalProvider(LocalMouseInput provides false) {
                    Box(Modifier.fillMaxSize().background(Ide.colors.editorBg)) {
                        FileNavigator(
                            root = root, moduleCount = 1, activePath = null, onOpen = {},
                            mode = TreeViewMode.AllFiles, expandedState = expanded, modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
        try {
            var img = scene.render()
            for (frame in 1..40) img = scene.render(frame * 16_666_667L)
            val out = File(OUT_DIR, "deep-tree-phone.png")
            out.writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            println("wrote snapshot: $out")
        } finally {
            scene.close()
        }
    }

    private companion object {
        val OUT_DIR: String = File(System.getProperty("java.io.tmpdir"), "codeassist-snapshots").apply { mkdirs() }.absolutePath
    }
}
