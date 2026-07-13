package dev.ide.core

import dev.ide.ui.backend.TreeNode
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
            val tree = backend.files.fileTree()
            fun flatten(n: TreeNode): List<TreeNode> = listOf(n) + n.children.flatMap { flatten(it) }
            val nodes = flatten(tree)
            val names = nodes.map { it.name }.toSet()

            assertTrue("AndroidManifest.xml" in names, "manifest must be visible in the tree: $names")
            assertTrue("strings.xml" in names, "res/strings must be visible: $names")
            assertTrue("colors.xml" in names && ("themes.xml" in names || "styles.xml" in names), "editable res (colors + themes/styles) must be visible: $names")

            // The manifest is an openable file (has a path) and reads back its content.
            val manifest = nodes.first { it.name == "AndroidManifest.xml" }
            assertTrue(manifest.filePath != null, "manifest node must be openable")
            assertTrue("com.example.app" in backend.files.readFile(manifest.filePath!!), "manifest content should be readable/editable")
        }
        dir.toFile().deleteRecursively()
    }

    @Test
    fun runPickerOffersAndroidAssembleVariants() {
        val dir = Files.createTempDirectory("ide-android-run")
        IdeServices.bootstrapDemo(dir).use { ide ->
            val ids = IdeServicesBackend(ide).build.runTasks().map { it.id }.toSet()
            assertTrue("assemble:app:debug" in ids, "Run picker should offer assemble debug: $ids")
            assertTrue("assemble:app:release" in ids, "Run picker should offer assemble release: $ids")
            // reindex is a fire-and-forget background action; it must at least not throw.
            ide.reindex()
        }
        dir.toFile().deleteRecursively()
    }

    /**
     * A file under `res/` maps to its owning module through the backend's [moduleNameForFile] — the resolver
     * the layout-preview "prepare libraries" action ([onPrepare]) uses to build for the previewed file. The
     * source-only [moduleForFile] misses a resource file (its `res/` root is not a SOURCE root), so before the
     * fix that action silently no-op'd on a layout: it pressed but no build started. [moduleForEditableFile]
     * (source → res → manifest) resolves it.
     */
    @Test
    fun resourceFileResolvesToItsModuleForBuildActions() {
        val dir = Files.createTempDirectory("ide-android-resmod")
        IdeServices.bootstrapDemo(dir).use { ide ->
            val backend = IdeServicesBackend(ide)
            fun flatten(n: TreeNode): List<TreeNode> = listOf(n) + n.children.flatMap { flatten(it) }
            // A real editable resource in the demo (`res/values/strings.xml`), sitting under an ANDROID_RES root.
            val resFile = flatten(backend.files.fileTree()).first { it.name == "strings.xml" }.filePath
            assertNotNull(resFile, "the demo must surface an editable res file")
            val resPath = Paths.get(resFile)

            // The source-only lookup can't see a res file — this is exactly why onPrepare used to do nothing.
            assertNull(ide.moduleForFile(resPath), "a res/ file has no source root, so moduleForFile misses it")
            // The editable-file resolver (and the backend API that now delegates to it) resolves it.
            val module = ide.moduleForEditableFile(resPath)
            assertNotNull(module, "moduleForEditableFile must resolve a res file to its module")
            assertEquals(module.name, backend.files.moduleNameForFile(resFile), "moduleNameForFile must agree")
        }
        dir.toFile().deleteRecursively()
    }

    /** The Run picker surfaces tasks from EVERY module, not just the app: an android-lib packages an .aar,
     *  and a plain-Java library (no main) still offers a build task. Enumeration is SDK-independent. */
    @Test
    fun runPickerOffersLibraryBuildAndAarTasks() {
        val dir = Files.createTempDirectory("ide-android-run-lib")
        IdeServices.bootstrapDemo(dir).use { ide ->
            val ids = IdeServicesBackend(ide).build.runTasks().map { it.id }.toSet()
            // The android-lib ("feature") packages an .aar per variant — visible from Run.
            assertTrue("assembleAar:feature:debug" in ids, "Run picker should offer the android-lib AAR: $ids")
            assertTrue("assembleAar:feature:release" in ids, "Run picker should offer the android-lib AAR (release): $ids")
            // The plain-Java library ("core") builds even though it has no main().
            assertTrue("build:core" in ids, "Run picker should offer building the java-lib: $ids")
        }
        dir.toFile().deleteRecursively()
    }
}
