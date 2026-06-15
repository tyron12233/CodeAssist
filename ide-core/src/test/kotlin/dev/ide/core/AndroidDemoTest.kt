package dev.ide.core

import dev.ide.ui.backend.TreeNode
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The default demo is the Android multi-module app (`app → feature → core`), and the IDE's file tree
 * surfaces what an Android project needs to edit: the `AndroidManifest.xml` (which sits above the source
 * roots) and the `res/` resources. SDK-independent — it checks the model + tree, not analysis.
 */
class AndroidDemoTest {

    @Test
    fun demoIsAnAndroidAppWithVisibleManifestAndResources() {
        val dir = Files.createTempDirectory("ide-android-demo")
        IdeServices.bootstrapDemo(dir).use { ide ->
            assertEquals(setOf("app", "feature", "core"), ide.modules().map { it.name }.toSet())
            assertEquals("android-app", ide.modules().first { it.name == "app" }.type.id)
            assertEquals("android-lib", ide.modules().first { it.name == "feature" }.type.id)

            val backend = IdeServicesBackend(ide)
            val tree = backend.fileTree()
            fun flatten(n: TreeNode): List<TreeNode> = listOf(n) + n.children.flatMap { flatten(it) }
            val nodes = flatten(tree)
            val names = nodes.map { it.name }.toSet()

            assertTrue("AndroidManifest.xml" in names, "manifest must be visible in the tree: $names")
            assertTrue("strings.xml" in names, "res/strings must be visible: $names")
            assertTrue("colors.xml" in names && "styles.xml" in names, "editable res (colors/styles) must be visible: $names")

            // The manifest is an openable file (has a path) and reads back its content.
            val manifest = nodes.first { it.name == "AndroidManifest.xml" }
            assertTrue(manifest.filePath != null, "manifest node must be openable")
            assertTrue("com.example.app" in backend.readFile(manifest.filePath!!), "manifest content should be readable/editable")
        }
        dir.toFile().deleteRecursively()
    }

    @Test
    fun runPickerOffersAndroidAssembleVariants() {
        val dir = Files.createTempDirectory("ide-android-run")
        IdeServices.bootstrapDemo(dir).use { ide ->
            val ids = IdeServicesBackend(ide).runTasks().map { it.id }.toSet()
            assertTrue("assemble:app:debug" in ids, "Run picker should offer assemble debug: $ids")
            assertTrue("assemble:app:release" in ids, "Run picker should offer assemble release: $ids")
            // reindex is a fire-and-forget background action; it must at least not throw.
            ide.reindex()
        }
        dir.toFile().deleteRecursively()
    }
}
