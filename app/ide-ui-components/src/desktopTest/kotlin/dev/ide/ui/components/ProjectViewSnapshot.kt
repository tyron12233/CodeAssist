package dev.ide.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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
 * Off-screen PNG of an Android module in the Project view and the All Files view side by side, shaped the way the
 * backend builds them: real nested folders (`app ▸ src ▸ main ▸ kotlin`, `test ▸ java` beside `main`), each source
 * root under its own name with its role's icon (sources, test sources, Android res), and packages compacted only
 * inside a root. Not an assertion; for eyeballing the layout.
 */
class ProjectViewSnapshot {

    private val p = "/p/SampleApp"

    private fun file(path: String, icon: String) =
        TreeNode("file:$p/$path", path.substringAfterLast('/'), NodeKind.File, "$p/$path", icon)

    private fun folder(path: String, icon: String, vararg children: TreeNode, resDir: Boolean = false) = TreeNode(
        "dir:$p/$path", path.substringAfterLast('/'), NodeKind.Folder, null, icon, children.toList(),
        resDirPath = if (resDir) "$p/$path" else null, dirPath = "$p/$path",
    )

    /** A package compacted from [rel] (`com/example/app`) down, under the root at [root]; [from] is its first level. */
    private fun pkg(root: String, rel: String, from: Int, vararg children: TreeNode): TreeNode {
        val levels = rel.split('/')
        val segs = (from until levels.size).map { k ->
            PackageSegment(levels.subList(0, k + 1).joinToString("."), "$p/$root/" + levels.subList(0, k + 1).joinToString("/"))
        }
        return TreeNode(
            "pkg:$p/$root/$rel", levels.subList(from, levels.size).joinToString("."), NodeKind.Package, null, "package",
            children.toList(), sourceRootPath = "$p/$root", packageSegments = segs, dirPath = "$p/$root/$rel",
        )
    }

    private fun root(path: String, icon: String, vararg children: TreeNode, res: Boolean = false) = TreeNode(
        "root:$p/$path", path.substringAfterLast('/'), NodeKind.SourceRoot, null, icon, children.toList(),
        sourceRootPath = if (res) null else "$p/$path", resDirPath = if (res) "$p/$path" else null, dirPath = "$p/$path",
    )

    private fun resFolders(base: String) = arrayOf(
        folder("$base/layout", "folder", file("$base/layout/activity_main.xml", "xml"), resDir = true),
        folder("$base/values", "folder", file("$base/values/strings.xml", "xml"), resDir = true),
    )

    private fun projectTree() = TreeNode(
        "workspace", "SampleApp", NodeKind.Workspace, null, dirPath = p,
        children = listOf(
            TreeNode(
                "module:app", "app", NodeKind.Module, null, "module.android.app", dirPath = "$p/app",
                children = listOf(
                    folder(
                        "app/src", "folder",
                        folder(
                            "app/src/main", "folder",
                            root(
                                "app/src/main/kotlin", "sourceset.kotlin",
                                pkg(
                                    "app/src/main/kotlin", "com/example/app", 0,
                                    pkg("app/src/main/kotlin", "com/example/app/ui", 3, file("app/src/main/kotlin/com/example/app/ui/Theme.kt", "kotlin")),
                                    file("app/src/main/kotlin/com/example/app/MainActivity.kt", "kotlin"),
                                ),
                            ),
                            root("app/src/main/res", "sourceset.android-res", *resFolders("app/src/main/res"), res = true),
                            file("app/src/main/AndroidManifest.xml", "manifest"),
                        ),
                        folder(
                            "app/src/test", "folder",
                            root(
                                "app/src/test/java", "sourceset.test",
                                pkg("app/src/test/java", "com/example/app", 0, file("app/src/test/java/com/example/app/ExampleUnitTest.java", "java")),
                            ),
                        ),
                    ),
                    file("app/build.gradle.kts", "kotlin"),
                    file("app/module.toml", "toml"),
                    file("app/proguard-rules.pro", "proguard"),
                ),
            ),
            file("README.md", "markdown"),
            file("settings.gradle.kts", "kotlin"),
        ),
    )

    private fun allFilesTree() = TreeNode(
        "workspace", "SampleApp", NodeKind.Workspace, null, dirPath = p,
        children = listOf(
            folder(
                "app", "module.android.app",
                folder(
                    "app/src", "folder",
                    folder(
                        "app/src/main", "folder",
                        folder(
                            "app/src/main/kotlin", "sourceset.kotlin",
                            folder(
                                "app/src/main/kotlin/com", "folder",
                                folder(
                                    "app/src/main/kotlin/com/example", "folder",
                                    folder(
                                        "app/src/main/kotlin/com/example/app", "folder",
                                        folder("app/src/main/kotlin/com/example/app/ui", "folder", file("app/src/main/kotlin/com/example/app/ui/Theme.kt", "kotlin")),
                                        file("app/src/main/kotlin/com/example/app/MainActivity.kt", "kotlin"),
                                    ),
                                ),
                            ),
                        ),
                        folder("app/src/main/res", "sourceset.android-res", *resFolders("app/src/main/res")),
                        file("app/src/main/AndroidManifest.xml", "manifest"),
                    ),
                    folder(
                        "app/src/test", "folder",
                        folder(
                            "app/src/test/java", "sourceset.test",
                            folder(
                                "app/src/test/java/com", "folder",
                                folder(
                                    "app/src/test/java/com/example", "folder",
                                    folder("app/src/test/java/com/example/app", "folder", file("app/src/test/java/com/example/app/ExampleUnitTest.java", "java")),
                                ),
                            ),
                        ),
                    ),
                ),
                file("app/build.gradle.kts", "kotlin"),
                file("app/module.toml", "toml"),
                file("app/proguard-rules.pro", "proguard"),
            ),
            file("README.md", "markdown"),
            file("settings.gradle.kts", "kotlin"),
        ),
    )

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun renderProjectAndAllFilesViews() {
        val trees = listOf(projectTree() to TreeViewMode.Project, allFilesTree() to TreeViewMode.AllFiles)
        val scene = ImageComposeScene(width = 2 * 640, height = 2400, density = Density(2f)) {
            CodeAssistTheme(dark = true) {
                Row(Modifier.fillMaxSize().background(Ide.colors.editorBg)) {
                    for ((root, mode) in trees) {
                        val expanded = mutableStateMapOf<String, Boolean>()
                        fun walk(n: TreeNode) { if (n.children.isNotEmpty()) expanded[n.id] = true; n.children.forEach(::walk) }
                        walk(root)
                        Box(Modifier.width(320.dp).fillMaxHeight()) {
                            CompositionLocalProvider(LocalMouseInput provides false) {
                                FileNavigator(
                                    root = root, moduleCount = 1,
                                    activePath = "$p/app/src/main/kotlin/com/example/app/MainActivity.kt",
                                    onOpen = {}, mode = mode, expandedState = expanded, modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }
                }
            }
        }
        try {
            var img = scene.render()
            for (frame in 1..40) img = scene.render(frame * 16_666_667L)
            val out = File(OUT_DIR, "project-view-nested.png")
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
