package dev.ide.core

import dev.ide.testkit.withTempDir
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
        withTempDir("ide-tree-java") { dir ->
            IdeServices.bootstrapJavaDemo(dir).use { ide ->
                val backend = IdeServicesBackend(ide)
                val nodes = flatten(backend.files.fileTree())

                // (a) a .java file gets the Java badge id.
                assertEquals("java", nodes.first { it.name == "Greeter.java" }.iconId)

                // (b) the main Java source root gets the java source-set id and carries its path (a new-class target).
                val srcRoot = nodes.first { it.kind == NodeKind.SourceRoot && it.dirPath!!.endsWith("src/main/java") }
                assertEquals("java", srcRoot.name, "a source root shows just its own name")
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
    }

    @Test
    fun markdownAtWorkspaceRootIsVisibleWithIcon() {
        withTempDir("ide-tree-md") { dir ->
            IdeServices.bootstrapJavaDemo(dir).use { ide ->
                val backend = IdeServicesBackend(ide)
                // A top-level README.md (the sample templates put one here) — a workspace-root file that sits
                // outside every module, so it's only reachable via the workspace node's own children.
                dir.resolve("README.md").toFile().writeText("# Title\n\nHello.\n")

                val root = backend.files.fileTree()
                // It appears as a direct child of the workspace node (not only under the All-Files view)...
                val readme = root.children.firstOrNull { it.name == "README.md" }
                assertNotNull(readme, "a workspace-root README.md must be visible in the Project tree")
                // ...with the Markdown icon and an openable file path.
                assertEquals("markdown", readme.iconId)
                assertEquals(NodeKind.File, readme.kind)
                assertNotNull(readme.filePath)
            }
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun androidTreeHasResAndModuleIcons() {
        withTempDir("ide-tree-android") { dir ->
            IdeServices.bootstrapDemo(dir).use { ide ->
                val backend = IdeServicesBackend(ide)
                val nodes = flatten(backend.files.fileTree())

                val appModule = nodes.firstOrNull { it.kind == NodeKind.Module && it.name == "app" }
                assertNotNull(appModule, "app module must be present")
                assertEquals("module.android.app", appModule.iconId)

                val resRoot = nodes.firstOrNull { it.kind == NodeKind.SourceRoot && it.iconId == "sourceset.android-res" }
                assertNotNull(resRoot, "app should expose a res/ root with the android-res icon")
            }
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * `src/main/jniLibs` shows in the Project (Android) view as its own root, holding the ABI folders and `.so`
     * files, and not only under All Files. Also when the model never declared it (a project made before 3.3,
     * simulated by removing the declared root): the tree follows the build's `src/<set>/jniLibs` convention.
     * Either way it stays out of the text roots that search and rename walk.
     */
    @Test
    fun jniLibsShowInTheProjectViewDeclaredOrNot() {
        withTempDir("ide-tree-jnilibs") { dir ->
            IdeServices.bootstrapDemo(dir).use { ide ->
                val backend = IdeServicesBackend(ide)
                val module = ide.modules().first { it.name == "app" }
                val moduleDir = ide.moduleRoot(module)!!
                val abi = moduleDir.resolve("src/main/jniLibs/arm64-v8a")
                Files.createDirectories(abi)
                Files.write(abi.resolve("libfoo.so"), byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()))

                fun jniRoot(): TreeNode? = flatten(backend.files.fileTree())
                    .firstOrNull { it.kind == NodeKind.SourceRoot && it.dirPath?.endsWith("src/main/jniLibs") == true }

                fun assertShown(label: String) {
                    val root = jniRoot()
                    assertNotNull(root, "$label: src/main/jniLibs must be a root in the Project view")
                    assertEquals("jniLibs", root.name)
                    assertEquals("sourceset.jni-libs", root.iconId)
                    assertTrue(flatten(root).any { it.name == "libfoo.so" && it.kind == NodeKind.File }, "$label: the .so is listed")
                    val fresh = ide.modules().first { it.name == "app" }
                    val textRoots = ide.treeRoots(fresh).map { it.toAbsolutePath().normalize() }
                    assertTrue(abi.parent.toAbsolutePath().normalize() !in textRoots, "$label: jniLibs stays out of search roots")
                }
                assertShown("declared")

                // An older project: the model has no jniLibs root at all.
                val declared = module.sourceSets.flatMap { ss ->
                    ss.contentRoots.filter { dev.ide.model.ContentRole.JNI_LIBS in it.roles }.map { ss.name to it }
                }
                for ((set, root) in declared) backend.modules.removeSourceRoot("app", set, root.dir.path)
                assertTrue(
                    ide.modules().first { it.name == "app" }.sourceSets.none { ss ->
                        ss.contentRoots.any { dev.ide.model.ContentRole.JNI_LIBS in it.roles }
                    },
                    "the model has no jniLibs root any more",
                )
                assertShown("undeclared")
            }
            dir.toFile().deleteRecursively()
        }
    }

    /** IntelliJ's Project Files view: a module's directory carries the module mark, a source root its role. */
    @Test
    fun allFilesViewMarksModuleDirectoriesAndSourceRoots() {
        withTempDir("ide-tree-allfiles") { dir ->
            IdeServices.bootstrapDemo(dir).use { ide ->
                val nodes = flatten(IdeServicesBackend(ide).files.fileTree(dev.ide.ui.backend.TreeViewMode.AllFiles))

                val appDir = nodes.firstOrNull { it.kind == NodeKind.Folder && it.name == "app" }
                assertNotNull(appDir, "the app module's directory is in the All Files view")
                assertEquals("module.android.app", appDir.iconId)
                assertNotNull(
                    nodes.firstOrNull { it.kind == NodeKind.Folder && it.name == "res" && it.iconId == "sourceset.android-res" },
                    "the res root shows its role's icon, not a plain folder",
                )
            }
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * The All-Files view never merges a chain of single-child directories into one row: IntelliJ's Project Files
     * view draws every directory on its own row, and only packages inside a source root ever compact.
     */
    @Test
    fun allFilesViewDrawsEveryFolderOnItsOwnRow() {
        withTempDir("ide-tree-compact") { dir ->
            IdeServices.bootstrapJavaDemo(dir).use { ide ->
                Files.createDirectories(dir.resolve("docs/guides/intro"))
                dir.resolve("docs/guides/intro/start.md").toFile().writeText("# Start\n")

                val root = IdeServicesBackend(ide).files.fileTree(dev.ide.ui.backend.TreeViewMode.AllFiles)
                val nodes = flatten(root)
                assertTrue(nodes.none { it.name.contains('/') }, "no merged row: ${nodes.filter { it.name.contains('/') }.map { it.name }}")
                assertTrue(nodes.all { it.packageSegments.isEmpty() }, "no row carries chain segments")

                val docs = root.children.first { it.name == "docs" }
                assertEquals("dir:${dir.resolve("docs")}", docs.id)
                val guides = docs.children.single()
                assertEquals("guides", guides.name)
                val intro = guides.children.single()
                assertEquals("intro", intro.name)
                assertEquals(dir.resolve("docs/guides/intro").toString(), intro.dirPath)
                assertEquals(listOf("start.md"), intro.children.map { it.name })

                // The java source root is a row of its own with its role's icon, and the folders under it are plain
                // folders here too: `com ▸ example ▸ core`, not `com.example.core`.
                val javaRoot = nodes.first { it.kind == NodeKind.Folder && it.iconId == "sourceset.java" }
                assertEquals("java", javaRoot.name)
                assertEquals(listOf("com"), javaRoot.children.map { it.name })
                assertEquals(listOf("example"), javaRoot.children.single().children.map { it.name })
                assertTrue(nodes.none { it.kind == NodeKind.Package }, "the All Files view has no packages")
            }
            dir.toFile().deleteRecursively()
        }
    }
}
