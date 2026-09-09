package dev.ide.core

import dev.ide.testkit.withTempDir
import dev.ide.ui.backend.UiDrawable
import dev.ide.ui.backend.UiIconImport
import dev.ide.ui.backend.UiIconTarget
import dev.ide.ui.backend.UiIconVariant
import dev.ide.ui.backend.UiVectorGroup
import dev.ide.ui.backend.UiVectorPath
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Icon Manager backend against a real Android project: which `res/` directories an asset can be written
 * to, what the project's drawable catalogue looks like, and the import path end to end (name validation,
 * conflicts, overwrite, recolouring, and the file that actually lands on disk).
 */
class IconManagerTest {

    @Test
    fun theBundledRepositoryIsBrowsableAndItsArtworkRenders() {
        withTempDir("icons-repo") { dir ->
        IdeServices.bootstrapDemo(dir).use { ide ->
            val icons = IdeServicesBackend(ide).icons

            val bundled = assertNotNull(
                icons.iconRepositories().firstOrNull { !it.requiresNetwork },
                "the bundled Material repository should always be registered",
            )
            assertTrue(bundled.loaded)
            assertTrue(bundled.iconCount > 100, "got ${bundled.iconCount} icons")
            assertEquals("Apache-2.0", bundled.license)

            runBlocking {
                val hits = icons.searchIcons(bundled.id, "cart", limit = 10)
                assertTrue(hits.any { it.name.contains("cart") }, hits.map { it.name }.toString())

                val artwork = assertNotNull(icons.iconArtwork(bundled.id, "home", UiIconVariant()))
                val vector = assertTrue(artwork.drawable is UiDrawable.Vector, "expected a vector drawable")
                    .let { artwork.drawable as UiDrawable.Vector }
                assertEquals(960f, vector.viewportWidth)
                // The Material 960 box is offset, so the artwork arrives wrapped in a translating group.
                val group = vector.nodes.single() as UiVectorGroup
                assertEquals(960f, group.translateY)
                assertTrue((group.children.single() as UiVectorPath).pathData.isNotEmpty())
            }
        }
        }
    }

    @Test
    fun aNetworkRepositoryIsListedButNotLoaded() {
        withTempDir("icons-remote") { dir ->
        IdeServices.bootstrapDemo(dir).use { ide ->
            val remote = assertNotNull(
                IdeServicesBackend(ide).icons.iconRepositories().firstOrNull { it.requiresNetwork },
                "the remote Material repository should be registered",
            )
            assertFalse(remote.loaded, "nothing is downloaded until the user asks for it")
            assertEquals(0, remote.iconCount)
        }
        }
    }

    @Test
    fun importTargetsCoverEveryAndroidSourceSetWithTheAppsMainFirst() {
        withTempDir("icons-targets") { dir ->
        IdeServices.bootstrapDemo(dir).use { ide ->
            val targets = IdeServicesBackend(ide).icons.importTargets()
            assertTrue(targets.isNotEmpty(), "the Android sample has res/ directories")

            val default = targets.first()
            assertTrue(default.isDefault, "the first target is the preselected one")
            assertEquals("main", default.sourceSetName)
            assertTrue(default.resDirPath.replace('\\', '/').endsWith("src/main/res"), default.resDirPath)
            assertEquals(1, targets.count { it.isDefault }, "exactly one target is the default")
        }
        }
    }

    @Test
    fun theCatalogueListsTheProjectsOwnDrawablesWithTheirConfigurations() {
        withTempDir("icons-catalog") { dir ->
        IdeServices.bootstrapDemo(dir).use { ide ->
            val backend = IdeServicesBackend(ide)
            val target = backend.icons.importTargets().first()
            // A default and a night-mode variant of the same name, which the catalogue should fold together.
            writeRes(target, "drawable", "ic_thing.xml", VECTOR_XML)
            writeRes(target, "drawable-night", "ic_thing.xml", VECTOR_XML)
            writeRes(target, "mipmap-hdpi", "logo.png", "not really a png")

            runBlocking {
                val catalog = backend.icons.projectIcons()
                val thing = assertNotNull(catalog.firstOrNull { it.name == "ic_thing" })
                assertEquals("drawable", thing.resType)
                assertEquals(setOf("", "night"), thing.configurations.map { it.qualifier }.toSet())
                assertTrue(thing.configurations.none { it.isRaster })

                val logo = assertNotNull(catalog.firstOrNull { it.name == "logo" })
                assertEquals("mipmap", logo.resType)
                assertTrue(logo.configurations.single().isRaster, "a .png config is a raster")
            }
        }
        }
    }

    @Test
    fun aProjectDrawableRendersThroughTheResourcePipeline() {
        withTempDir("icons-res-art") { dir ->
        IdeServices.bootstrapDemo(dir).use { ide ->
            val backend = IdeServicesBackend(ide)
            val target = backend.icons.importTargets().first()
            val path = writeRes(target, "drawable", "ic_thing.xml", VECTOR_XML)

            runBlocking {
                val artwork = assertNotNull(backend.icons.resourceArtwork(path.toString()))
                val vector = artwork.drawable as UiDrawable.Vector
                assertEquals(0xFF6200EEL, (vector.nodes.single() as UiVectorPath).fillColor)
            }
        }
        }
    }

    @Test
    fun importingFromARepositoryWritesAVectorDrawableAndShowsUpInTheCatalogue() {
        withTempDir("icons-import") { dir ->
        IdeServices.bootstrapDemo(dir).use { ide ->
            val backend = IdeServicesBackend(ide)
            val icons = backend.icons
            val repo = icons.iconRepositories().first { !it.requiresNetwork }
            val target = icons.importTargets().first()

            runBlocking {
                val result = icons.importIcon(
                    repoId = repo.id,
                    name = "home",
                    variant = UiIconVariant(),
                    request = UiIconImport(target = target, name = "ic_home", sizeDp = 32f, colorArgb = 0xFF00FF00L),
                )
                assertTrue(result.ok, result.message)
                val written = Paths.get(assertNotNull(result.path))
                assertTrue(written.toString().replace('\\', '/').endsWith("res/drawable/ic_home.xml"), written.toString())

                val xml = Files.readString(written)
                assertTrue(xml.startsWith("<vector"), xml.take(80))
                assertTrue(xml.contains("android:width=\"32dp\""), "the requested size is written: $xml")
                assertTrue(xml.contains("#00FF00"), "the requested colour is written: $xml")

                assertTrue(icons.projectIcons().any { it.name == "ic_home" }, "the import shows in the catalogue")
            }
        }
        }
    }

    @Test
    fun anExistingNameIsReportedAsAConflictAndOnlyOverwrittenWhenAsked() {
        withTempDir("icons-conflict") { dir ->
        IdeServices.bootstrapDemo(dir).use { ide ->
            val icons = IdeServicesBackend(ide).icons
            val repo = icons.iconRepositories().first { !it.requiresNetwork }
            val target = icons.importTargets().first()
            val existing = writeRes(target, "drawable", "ic_taken.xml", VECTOR_XML)

            runBlocking {
                assertEquals(
                    existing.toString(),
                    icons.existingResource(target, "drawable", "ic_taken"),
                    "the taken name is reported before any write is attempted",
                )
                assertNull(icons.existingResource(target, "drawable", "ic_free"))

                val blocked = icons.importIcon(repo.id, "home", UiIconVariant(), request(target, "ic_taken"))
                assertFalse(blocked.ok)
                assertEquals(existing.toString(), blocked.conflictPath)
                assertEquals(VECTOR_XML, Files.readString(existing), "the existing file is untouched")

                val forced = icons.importIcon(
                    repo.id, "home", UiIconVariant(), request(target, "ic_taken").copy(overwrite = true),
                )
                assertTrue(forced.ok, forced.message)
                assertTrue(Files.readString(existing).contains("viewportWidth=\"960\""), "the file was replaced")
            }
        }
        }
    }

    @Test
    fun overwritingAcrossExtensionsLeavesOnlyOneDeclaration() {
        withTempDir("icons-ext") { dir ->
        IdeServices.bootstrapDemo(dir).use { ide ->
            val icons = IdeServicesBackend(ide).icons
            val repo = icons.iconRepositories().first { !it.requiresNetwork }
            val target = icons.importTargets().first()
            val raster = writeRes(target, "drawable", "ic_swap.png", "pretend png")

            runBlocking {
                val result = icons.importIcon(
                    repo.id, "home", UiIconVariant(), request(target, "ic_swap").copy(overwrite = true),
                )
                assertTrue(result.ok, result.message)
                // aapt would reject two files declaring the same resource, so the old one has to go.
                assertFalse(Files.exists(raster), "the replaced .png should be deleted")
                assertTrue(Files.exists(Paths.get(assertNotNull(result.path))))
            }
        }
        }
    }

    @Test
    fun anInvalidResourceNameIsRejectedWithAReason() {
        withTempDir("icons-name") { dir ->
        IdeServices.bootstrapDemo(dir).use { ide ->
            val icons = IdeServicesBackend(ide).icons
            val repo = icons.iconRepositories().first { !it.requiresNetwork }
            val target = icons.importTargets().first()

            runBlocking {
                for (bad in listOf("", "9lives", "IcHome", "ic home", "ic-home")) {
                    val result = icons.importIcon(repo.id, "home", UiIconVariant(), request(target, bad))
                    assertFalse(result.ok, "\"$bad\" should be rejected")
                    assertNotNull(result.message, "\"$bad\" should say why")
                }
            }
        }
        }
    }

    @Test
    fun importingAnSvgConvertsItOnTheWayIn() {
        withTempDir("icons-svg") { dir ->
        IdeServices.bootstrapDemo(dir).use { ide ->
            val icons = IdeServicesBackend(ide).icons
            val target = icons.importTargets().first()
            val svg = """
                <svg xmlns="http://www.w3.org/2000/svg" width="48" height="48" viewBox="0 0 48 48">
                  <circle cx="24" cy="24" r="20" fill="#123456"/>
                </svg>
            """.trimIndent()

            runBlocking {
                val preview = assertNotNull(icons.previewSvg(svg))
                assertTrue(preview.drawable is UiDrawable.Vector)

                val result = icons.importSvg(svg, UiIconImport(target = target, name = "ic_dot", sizeDp = 24f))
                assertTrue(result.ok, result.message)
                val xml = Files.readString(Paths.get(assertNotNull(result.path)))
                assertTrue(xml.contains("viewportWidth=\"48\""), xml)
                assertTrue(xml.contains("#123456"), xml)
                assertTrue(xml.contains("android:width=\"24dp\""), "the requested size overrides the SVG's own")
            }
        }
        }
    }

    @Test
    fun aFileThatIsNotAnSvgIsRefusedRatherThanWritten() {
        withTempDir("icons-notsvg") { dir ->
        IdeServices.bootstrapDemo(dir).use { ide ->
            val icons = IdeServicesBackend(ide).icons
            val target = icons.importTargets().first()
            runBlocking {
                val result = icons.importSvg("<html><body>nope</body></html>", UiIconImport(target = target, name = "ic_nope"))
                assertFalse(result.ok)
                assertNull(icons.existingResource(target, "drawable", "ic_nope"))
            }
        }
        }
    }

    @Test
    fun importingARasterKeepsItsBytesAndRejectsAnUnusableFormat() {
        withTempDir("icons-raster") { dir ->
        IdeServices.bootstrapDemo(dir).use { ide ->
            val icons = IdeServicesBackend(ide).icons
            val target = icons.importTargets().first()
            val bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)

            runBlocking {
                val ok = icons.importRaster(bytes, "png", UiIconImport(target = target, resType = "mipmap", name = "ic_shot"))
                assertTrue(ok.ok, ok.message)
                val written = Paths.get(assertNotNull(ok.path))
                assertTrue(written.toString().replace('\\', '/').endsWith("res/mipmap/ic_shot.png"), written.toString())
                assertBytesEqual(bytes, Files.readAllBytes(written))

                val bad = icons.importRaster(bytes, "bmp", UiIconImport(target = target, name = "ic_bmp"))
                assertFalse(bad.ok, "bmp is not a usable Android drawable format")
            }
        }
        }
    }

    @Test
    fun anUnknownTargetIsRefusedInsteadOfWritingOutsideTheProject() {
        withTempDir("icons-badtarget") { dir ->
        IdeServices.bootstrapDemo(dir).use { ide ->
            val icons = IdeServicesBackend(ide).icons
            val repo = icons.iconRepositories().first { !it.requiresNetwork }
            val bogus = UiIconTarget("nope", "main", dir.resolve("elsewhere/res").toString())

            runBlocking {
                val result = icons.importIcon(repo.id, "home", UiIconVariant(), request(bogus, "ic_stray"))
                assertFalse(result.ok)
                assertFalse(Files.exists(dir.resolve("elsewhere")), "nothing is created outside a known res/ dir")
            }
        }
        }
    }

    // --- helpers ---------------------------------------------------------------------------------------

    private fun request(target: UiIconTarget, name: String) =
        UiIconImport(target = target, name = name, sizeDp = 24f)

    private fun writeRes(target: UiIconTarget, folder: String, fileName: String, content: String) =
        Paths.get(target.resDirPath).resolve(folder).resolve(fileName).also {
            Files.createDirectories(it.parent)
            Files.writeString(it, content)
        }

    private fun assertBytesEqual(expected: ByteArray, actual: ByteArray) =
        assertEquals(expected.toList(), actual.toList())

    private companion object {
        val VECTOR_XML = """
            <vector xmlns:android="http://schemas.android.com/apk/res/android"
                android:width="24dp" android:height="24dp"
                android:viewportWidth="24" android:viewportHeight="24">
              <path android:pathData="M12,2L2,22h20z" android:fillColor="#FF6200EE"/>
            </vector>
        """.trimIndent()
    }
}
