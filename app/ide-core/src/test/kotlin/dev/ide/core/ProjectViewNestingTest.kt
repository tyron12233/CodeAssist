package dev.ide.core

import dev.ide.model.ContentRole
import dev.ide.testkit.withTempDir
import dev.ide.ui.backend.NodeKind
import dev.ide.ui.backend.TreeNode
import dev.ide.ui.backend.TreeViewMode
import dev.ide.ui.backend.defaultExpandedTreeIds
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Project view draws a module the way IntelliJ's Project view does: real nested folders down to each source
 * root (`app ▸ src ▸ main ▸ kotlin`, with `test ▸ java` beside `main`), each root named by its own directory and
 * told apart by its icon, and packages inside a root compacted (`com.example.app`). Nothing outside a root is
 * ever merged into one row.
 */
class ProjectViewNestingTest {

    private fun flatten(n: TreeNode): List<TreeNode> = listOf(n) + n.children.flatMap(::flatten)

    private fun TreeNode.child(name: String): TreeNode =
        assertNotNull(children.firstOrNull { it.name == name }, "no '$name' under '${this.name}': ${children.map { it.name }}")

    @Test
    fun sourceRootsSitInRealNestedFoldersAndPackagesCompact() {
        withTempDir("ide-tree-nested") { dir ->
            IdeServices.bootstrapDemo(dir).use { ide ->
                val appDir = assertNotNull(ide.moduleRoot(ide.modules().first { it.name == "app" }))
                assertNotNull(ide.moduleService.addSourceRoot("app", "main", "kotlin", setOf(ContentRole.SOURCE)))
                assertNotNull(ide.moduleService.addSourceRoot("app", "test", "java", setOf(ContentRole.SOURCE)))
                Files.createDirectories(appDir.resolve("src/main/kotlin/com/example/app"))
                appDir.resolve("src/main/kotlin/com/example/app/MainScreen.kt").toFile()
                    .writeText("package com.example.app\n\nclass MainScreen\n")
                Files.createDirectories(appDir.resolve("src/test/java/com/example/app"))
                appDir.resolve("src/test/java/com/example/app/ExampleTest.java").toFile()
                    .writeText("package com.example.app;\n\npublic class ExampleTest {}\n")

                val tree = IdeServicesBackend(ide).files.fileTree(TreeViewMode.Project)
                val app = tree.children.first { it.kind == NodeKind.Module && it.name == "app" }

                // app ▸ src ▸ main ▸ kotlin, and test ▸ java beside main: real folders, one row each.
                val src = app.child("src")
                assertEquals(NodeKind.Folder, src.kind)
                assertEquals(appDir.resolve("src").toString(), src.dirPath)
                val main = src.child("main")
                val test = src.child("test")
                assertEquals(NodeKind.Folder, main.kind)
                assertEquals(NodeKind.Folder, test.kind)

                // Each root shows just its own name and carries its role in its icon.
                val kotlin = main.child("kotlin")
                assertEquals(NodeKind.SourceRoot, kotlin.kind)
                assertEquals("sourceset.kotlin", kotlin.iconId)
                assertEquals(appDir.resolve("src/main/kotlin").toString(), kotlin.sourceRootPath)
                val res = main.child("res")
                assertEquals(NodeKind.SourceRoot, res.kind)
                assertEquals("sourceset.android-res", res.iconId)
                assertEquals(appDir.resolve("src/main/res").toString(), res.resDirPath)
                val testJava = test.child("java")
                assertEquals(NodeKind.SourceRoot, testJava.kind)
                assertEquals("sourceset.test", testJava.iconId, "a test root is marked as one")
                assertEquals(NodeKind.SourceRoot, main.child("java").kind)
                assertEquals("sourceset.java", main.child("java").iconId)

                // The manifest sits in src/main, where it is on disk, and only there.
                val nodes = flatten(app)
                assertEquals(1, nodes.count { it.name == "AndroidManifest.xml" })
                assertNotNull(main.children.firstOrNull { it.name == "AndroidManifest.xml" })

                // Packages inside a root still compact into one row, with every level a New target.
                val pkg = kotlin.child("com.example.app")
                assertEquals(NodeKind.Package, pkg.kind)
                assertEquals(listOf("com", "com.example", "com.example.app"), pkg.packageSegments.map { it.packageName })
                assertEquals(listOf("MainScreen.kt"), pkg.children.map { it.name })
                assertEquals("com.example.app", testJava.child("com.example.app").name)

                // Nothing outside a root is merged: no row names a path, and no folder carries chain segments.
                assertTrue(nodes.none { it.kind != NodeKind.Package && it.name.contains('/') },
                    "merged rows: ${nodes.filter { it.kind != NodeKind.Package && it.name.contains('/') }.map { it.name }}")
                assertTrue(nodes.filter { it.kind != NodeKind.Package }.all { it.packageSegments.isEmpty() })

                // A fresh project opens the way down to its roots, and leaves packages closed.
                val open = defaultExpandedTreeIds(tree)
                for (n in listOf(app, src, main, test, kotlin, res, testJava)) assertTrue(n.id in open, "${n.name} opens by default")
                assertTrue(pkg.id !in open)
            }
            dir.toFile().deleteRecursively()
        }
    }

    /** The All Files view draws the same folders one row each, with the roots' icons and no packages. */
    @Test
    fun allFilesViewNestsTheSameFoldersWithoutMergingThem() {
        withTempDir("ide-tree-nested-all") { dir ->
            IdeServices.bootstrapDemo(dir).use { ide ->
                val appDir = assertNotNull(ide.moduleRoot(ide.modules().first { it.name == "app" }))
                assertNotNull(ide.moduleService.addSourceRoot("app", "test", "java", setOf(ContentRole.SOURCE)))
                Files.createDirectories(appDir.resolve("src/test/java/com/example/app"))
                appDir.resolve("src/test/java/com/example/app/ExampleTest.java").toFile().writeText("class ExampleTest {}\n")

                val tree = IdeServicesBackend(ide).files.fileTree(TreeViewMode.AllFiles)
                val app = tree.child("app")
                val test = app.child("src").child("test")
                val java = test.child("java")
                assertEquals("sourceset.test", java.iconId)
                assertEquals("example", java.child("com").child("example").name)
                assertNull(flatten(tree).firstOrNull { it.name.contains('/') }, "no merged rows")
            }
            dir.toFile().deleteRecursively()
        }
    }
}
