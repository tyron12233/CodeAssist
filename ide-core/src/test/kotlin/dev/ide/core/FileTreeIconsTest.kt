package dev.ide.core

import dev.ide.ui.backend.NodeKind
import dev.ide.ui.backend.TreeNode
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The file tree carries extensible icon ids (resolved by `platform.fileIcon` providers), compacts middle
 * packages, and can create a class at any package level. SDK-independent — it inspects the tree the
 * backend builds, not analysis.
 */
class FileTreeIconsTest {

    private fun flatten(n: TreeNode): List<TreeNode> = listOf(n) + n.children.flatMap(::flatten)

    @Test
    fun javaTreeHasIconsCompactedPackagesAndCreatesAClass() {
        val dir = Files.createTempDirectory("ide-tree-java")
        IdeServices.bootstrapJavaDemo(dir).use { ide ->
            val backend = IdeServicesBackend(ide)
            val nodes = flatten(backend.files.fileTree())

            // (a) a .java file gets the Java badge id.
            assertEquals("java", nodes.first { it.name == "Greeter.java" }.iconId)

            // (b) the main Java source root gets the java source-set id and carries its path (a new-class target).
            val srcRoot = nodes.first { it.kind == NodeKind.SourceRoot && it.name.endsWith("src/main/java") }
            assertEquals("sourceset.java", srcRoot.iconId)
            assertNotNull(srcRoot.sourceRootPath)

            // (c) com/example/core compacts into a single dotted package node with all three levels kept.
            val pkg = nodes.first { it.kind == NodeKind.Package && it.name == "com.example.core" }
            assertEquals(listOf("com", "com.example", "com.example.core"), pkg.packageSegments.map { it.packageName })

            // (d) create a class at the *intermediate* level (com.example) and see it in a fresh tree.
            val mid = pkg.packageSegments.first { it.packageName == "com.example" }
            val created = backend.files.createFile(mid.dirPath, "Mid.java", "package com.example;\n\npublic class Mid {\n}\n")
            assertNotNull(created, "createFile should return the new path")
            assertTrue(flatten(backend.files.fileTree()).any { it.name == "Mid.java" }, "the new file should appear in the tree")
        }
        dir.toFile().deleteRecursively()
    }

    @Test
    fun androidTreeHasResAndModuleIcons() {
        val dir = Files.createTempDirectory("ide-tree-android")
        IdeServices.bootstrapDemo(dir).use { ide ->
            val backend = IdeServicesBackend(ide)
            val nodes = flatten(backend.files.fileTree())

            val appModule = nodes.firstOrNull { it.kind == NodeKind.Module && it.name == "app" }
            assertNotNull(appModule, "app module must be present")
            assertEquals("module.android", appModule.iconId)

            val resRoot = nodes.firstOrNull { it.kind == NodeKind.SourceRoot && it.iconId == "sourceset.android-res" }
            assertNotNull(resRoot, "app should expose a res/ root with the android-res icon")
        }
        dir.toFile().deleteRecursively()
    }
}
