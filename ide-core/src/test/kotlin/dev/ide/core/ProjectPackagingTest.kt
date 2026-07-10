package dev.ide.core

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Round-trips a project through a `.caproj` package: the raw [ProjectPackaging] (exclusions + peek + the
 * optional bundled deps) and the full [ProjectManager] export → import path (a new workspace that opens with
 * the same modules), plus the manifest JSON codec.
 */
class ProjectPackagingTest {

    @Test
    fun exportsAndImportsAProjectRoundTrip() {
        val root = Files.createTempDirectory("cm-caproj")
        try {
            val manager = ProjectManager.desktop(root.resolve("projects"))
            var originalRoot = ""
            var originalModules: List<String> = emptyList()
            var originalName = ""
            manager.create("java-console", mapOf("name" to "Demo CLI", "packageName" to "com.acme.demo")).use { ide ->
                originalRoot = ide.workspaceRoot.toString()
                originalModules = ide.moduleNames()
                originalName = ide.projectDisplayName()
            }

            val src = Paths.get(originalRoot)
            // A freshly created + saved project has both the declared config and the resolved caches on disk;
            // the package must ship the former and drop the latter.
            assertTrue(Files.exists(src.resolve(".platform/workspace.json")), "created project has a workspace file")
            assertTrue(Files.exists(src.resolve(".platform/libraries.json")), "created project has a resolved-deps cache")

            val meta = ProjectPackaging.ExportMeta(
                name = originalName, isAndroid = false, packageName = null,
                modules = originalModules, createdBy = CaprojFormat.APP_NAME, exportedAt = 123L,
            )

            // --- lean package: manifest + peek + exclusions ---
            val leanPkg = root.resolve("lean.caproj")
            ProjectPackaging.export(src, leanPkg, ProjectPackaging.ExportOptions(false, "Me", "A demo"), null, meta)
            val preview = ProjectPackaging.readPreview(leanPkg)!!
            assertEquals("Demo CLI", preview.manifest.name)
            assertEquals("Me", preview.manifest.author)
            assertEquals("A demo", preview.manifest.description)
            assertEquals(originalModules.size, preview.manifest.moduleCount)
            assertFalse(preview.manifest.hasBundledDeps)
            assertTrue(preview.manifest.fileCount > 0 && preview.manifest.uncompressedSize > 0)
            val paths = preview.entries.map { it.path }.toSet()
            assertTrue(paths.any { it.endsWith("module.toml") }, "packages module.toml")
            assertTrue(paths.contains(".platform/workspace.json"), "packages workspace.json")
            assertFalse(paths.contains(".platform/libraries.json"), "excludes the resolved libraries.json")
            assertFalse(paths.contains(".platform/sdks.json"), "excludes the host-specific sdks.json")
            assertTrue(paths.none { it.startsWith(".platform/caches/") }, "excludes .platform/caches")

            // --- unpack the lean package into a raw dir (no engine open, so nothing regenerates) ---
            val leanDest = root.resolve("lean-dest")
            ProjectPackaging.unpack(leanPkg, leanDest)
            assertTrue(Files.exists(leanDest.resolve(".platform/workspace.json")))
            assertFalse(Files.exists(leanDest.resolve(".platform/libraries.json")), "lean import doesn't carry the resolved cache")

            // --- bundled package: the resolved libraries.json travels and is restored ---
            val bundledPkg = root.resolve("bundled.caproj")
            ProjectPackaging.export(src, bundledPkg, ProjectPackaging.ExportOptions(true, "", ""), null, meta)
            assertTrue(ProjectPackaging.readPreview(bundledPkg)!!.manifest.hasBundledDeps)
            val bundledDest = root.resolve("bundled-dest")
            ProjectPackaging.unpack(bundledPkg, bundledDest)
            assertTrue(Files.exists(bundledDest.resolve(".platform/libraries.json")), "bundled import restores the resolved cache")

            // --- full ProjectManager path: export → import into a new workspace that opens with the same modules ---
            val caproj = manager.exportProject(originalRoot, ProjectPackaging.ExportOptions(false, "Me", ""))
            assertEquals("caproj", caproj.fileName.toString().substringAfterLast('.'))
            manager.importProject(caproj.toString())!!.use { imported ->
                assertEquals(originalModules, imported.moduleNames())
                assertEquals(originalName, imported.projectDisplayName())
                assertNotEquals(originalRoot, imported.workspaceRoot.toString(), "imports into a fresh project dir")
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun roundTripsExploreStoreMetadataAndScreenshots() {
        val root = Files.createTempDirectory("cm-caproj-store")
        try {
            val manager = ProjectManager.desktop(root.resolve("projects"))
            var src = ""
            manager.create("java-console", mapOf("name" to "Storeable", "packageName" to "com.acme.s")).use { ide ->
                src = ide.workspaceRoot.toString()
            }

            val shotA = byteArrayOf(1, 2, 3, 4)
            val shotB = byteArrayOf(9, 8, 7)
            val meta = ProjectPackaging.ExportMeta("Storeable", false, null, listOf("app"), CaprojFormat.APP_NAME, 1L)
            val store = ProjectPackaging.StoreContent(
                summary = "A neat sample", category = "Java", tags = listOf("cli", "demo"),
                highlights = listOf("Runs on device"), language = "Java", screenshots = listOf(shotA, shotB),
            )
            val pkg = root.resolve("store.caproj")
            ProjectPackaging.export(Paths.get(src), pkg, ProjectPackaging.ExportOptions(false, "", ""), null, meta, store)

            val preview = ProjectPackaging.readPreview(pkg)!!
            val info = preview.manifest.store!!
            assertEquals("A neat sample", info.summary)
            assertEquals(listOf("cli", "demo"), info.tags)
            assertEquals(listOf("Runs on device"), info.highlights)
            assertEquals("Java", info.language)
            assertEquals(2, preview.screenshots.size)
            assertTrue(preview.screenshots[0].contentEquals(shotA))
            assertTrue(preview.screenshots[1].contentEquals(shotB))

            // Screenshots are Explore metadata, not project files: they must not extract into the imported project.
            val dest = root.resolve("store-dest")
            ProjectPackaging.unpack(pkg, dest)
            assertFalse(Files.exists(dest.resolve("store")), "store/ is package-only metadata, never extracted")
            assertTrue(Files.exists(dest.resolve(".platform/workspace.json")))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun manifestJsonRoundTrips() {
        val manifest = CaprojManifest(
            format = 1, kind = "project", name = "My App", description = "d", author = "a",
            createdBy = "CodeAssist", exportedAt = 42L, isAndroid = true, packageName = "com.x",
            moduleCount = 2, modules = listOf("app", "core"), fileCount = 10, uncompressedSize = 999L,
            hasBundledDeps = true, iconEntry = "icon.png",
        )
        assertEquals(manifest, CaprojFormat.decode(CaprojFormat.encode(manifest)))
    }

    @Test
    fun surfacesANewerFormatSoTheUiCanReject() {
        // A package from a newer build still decodes (format is preserved) so the import preview can mark it
        // incompatible rather than silently failing.
        val newer = CaprojManifest(
            format = CaprojFormat.FORMAT_VERSION + 5, kind = "project", name = "X", description = "", author = "",
            createdBy = "CodeAssist", exportedAt = 0L, isAndroid = false, packageName = null,
            moduleCount = 0, modules = emptyList(), fileCount = 0, uncompressedSize = 0L,
            hasBundledDeps = false, iconEntry = null,
        )
        assertEquals(CaprojFormat.FORMAT_VERSION + 5, CaprojFormat.decode(CaprojFormat.encode(newer))!!.format)
    }
}
