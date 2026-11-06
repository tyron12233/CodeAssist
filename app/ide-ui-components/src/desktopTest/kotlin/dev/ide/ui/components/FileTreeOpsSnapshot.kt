package dev.ide.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.NodeKind
import dev.ide.ui.backend.PackageSegment
import dev.ide.ui.backend.TreeNode
import dev.ide.ui.platform.LocalMouseInput
import dev.ide.ui.theme.CodeAssistTheme
import dev.ide.ui.theme.Ide
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/**
 * Off-screen PNG of the file tree's batch operations: touch selection mode with the selection bar and the
 * clipboard strip, a desktop drag onto a folder, the multi-selection context menu, and the name-conflict dialog
 * over the undo snackbar. Not an assertion; for eyeballing the layout.
 */
class FileTreeOpsSnapshot {

    private val src = "/p/app/src/main/java"

    private fun pkg(name: String, vararg children: TreeNode) = TreeNode(
        "pkg:$src/${name.replace('.', '/')}", name, NodeKind.Package, null, "package", children.toList(),
        sourceRootPath = src, packageSegments = listOf(PackageSegment(name, "$src/${name.replace('.', '/')}")),
        dirPath = "$src/${name.replace('.', '/')}",
    )

    private fun kt(pkg: String, name: String) =
        TreeNode("file:$src/${pkg.replace('.', '/')}/$name.kt", "$name.kt", NodeKind.File, "$src/${pkg.replace('.', '/')}/$name.kt", "kotlin")

    private fun tree() = TreeNode(
        "workspace", "Prism", NodeKind.Workspace, null, dirPath = "/p",
        children = listOf(
            TreeNode(
                "module:app", "app", NodeKind.Module, null, "module", dirPath = "/p/app",
                children = listOf(
                    TreeNode(
                        "root:$src", "java", NodeKind.SourceRoot, null, "sourceset.java", sourceRootPath = src, dirPath = src,
                        children = listOf(
                            pkg("com.prism.ui", kt("com.prism.ui", "HomeScreen"), kt("com.prism.ui", "Theme")),
                            pkg("com.prism.ui.components", kt("com.prism.ui.components", "Card"), kt("com.prism.ui.components", "Chip")),
                            pkg("com.prism.data", kt("com.prism.data", "Repository"), kt("com.prism.data", "Api"), kt("com.prism.data", "Models")),
                            kt("com.prism", "MainActivity"),
                        ),
                    ),
                ),
            ),
        ),
    )

    private fun expandedAll(root: TreeNode) = mutableStateMapOf<String, Boolean>().also { m ->
        fun walk(n: TreeNode) { if (n.children.isNotEmpty()) m[n.id] = true; n.children.forEach(::walk) }
        walk(root)
    }

    private val ops = TreeFileOps(clipboard = TreeClipboard(listOf("$src/com/prism/data/Api.kt", "$src/com/prism/data/Models.kt"), cut = true))

    @Composable
    private fun Panel(mouse: Boolean, seed: TreeStateSeed?, content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {}) {
        val root = tree()
        Box(Modifier.width(330.dp).fillMaxHeight().padding(6.dp).background(Ide.colors.editorBg)) {
            CompositionLocalProvider(LocalMouseInput provides mouse, LocalTreeStateSeed provides seed) {
                FileNavigator(
                    root = root, moduleCount = 1, activePath = "$src/com/prism/ui/HomeScreen.kt", onOpen = {},
                    expandedState = expandedAll(root), modifier = Modifier.fillMaxSize(), canModify = true, fileOps = ops,
                )
            }
            content()
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun renderBatchOperations() {
        // 1: touch selection mode, three rows ticked; Api.kt + Models.kt are on the clipboard by Cut (dimmed).
        val touchSel = TreeSelection().apply {
            mode = true
            listOf("file:$src/com/prism/ui/HomeScreen.kt", "file:$src/com/prism/ui/Theme.kt", "pkg:$src/com/prism/ui/components")
                .forEach { ids[it] = true }
            focusId = "pkg:$src/com/prism/ui/components"
        }
        // 2: desktop, two files selected and being dragged onto com.prism.data.
        val dragSel = TreeSelection().apply {
            ids["file:$src/com/prism/ui/HomeScreen.kt"] = true; ids["file:$src/com/prism/ui/Theme.kt"] = true
        }
        val drag = TreeDrag().apply {
            paths = listOf("$src/com/prism/ui/HomeScreen.kt", "$src/com/prism/ui/Theme.kt")
            targetId = "pkg:$src/com/prism/data"
            targetDir = "$src/com/prism/data"
            pointer = Offset(250f, 560f)
        }
        // 3: desktop, two selected; a right-click opens the batch menu.
        val menuSel = TreeSelection().apply {
            ids["file:$src/com/prism/data/Repository.kt"] = true; ids["file:$src/com/prism/data/Models.kt"] = true
        }
        val scene = ImageComposeScene(width = 4 * 660, height = 1300, density = Density(2f)) {
            CodeAssistTheme(dark = true) {
                Row(Modifier.fillMaxSize().background(Ide.colors.editorBg)) {
                    Panel(mouse = false, seed = TreeStateSeed(touchSel, TreeDrag()))
                    Panel(mouse = true, seed = TreeStateSeed(dragSel, drag))
                    Panel(mouse = true, seed = TreeStateSeed(menuSel, TreeDrag()))
                    Panel(mouse = true, seed = null) {
                        TransferConflictDialog(
                            conflicts = listOf("$src/com/prism/ui/Theme.kt", "$src/com/prism/ui/HomeScreen.kt"),
                            destName = "data", onResolve = {}, onCancel = {},
                        )
                        FileOpSnackbar(
                            text = "Moved 2 items to data", error = false, canUndo = true, key = 1L,
                            onUndo = {}, onDismiss = {}, modifier = Modifier.align(Alignment.BottomCenter),
                        )
                    }
                }
            }
        }
        try {
            var img = scene.render()
            for (frame in 1..30) img = scene.render(frame * 16_666_667L)
            // Right-click Repository.kt in panel 3 (rows are 30dp tall on desktop, the header ~ 90dp).
            val at = Offset(2 * 660f + 200f, 2f * (6 + 78 + 6 + 30 * 9 + 15))
            scene.sendPointerEvent(PointerEventType.Move, at, type = PointerType.Mouse)
            scene.sendPointerEvent(PointerEventType.Press, at, type = PointerType.Mouse,
                buttons = PointerButtons(isSecondaryPressed = true), button = PointerButton.Secondary)
            scene.sendPointerEvent(PointerEventType.Release, at, type = PointerType.Mouse,
                buttons = PointerButtons(), button = PointerButton.Secondary)
            for (frame in 31..60) img = scene.render(frame * 16_666_667L)
            val out = File(OUT_DIR, "file-tree-ops.png")
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
