package dev.ide.core

import dev.ide.model.LibraryKind
import dev.ide.testkit.withTempDir
import dev.ide.ui.backend.UiDepKind
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A local `.aar`/`.jar` dependency survives a project arriving somewhere without its `libraries.json`.
 *
 * A Projects Store download ships the module's dependency on `widget.aar` and the `libs/widget.aar` file, but not
 * `.platform/libraries.json` (the submitter's resolved state) nor the exploded copy under `.platform/libs`. Maven
 * coordinates re-resolve from their names; a local library's name is only a file name, so it was left with no
 * library behind it: listed as a "local jar", contributing no classes, and every view it provides unresolved in
 * layouts. A backup restored under another path has the same hole, with roots pointing into the old location.
 */
class LocalLibraryHealTest {

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    @Test
    fun aLocalAarAndJarAreReRegisteredAfterAStoreStyleInstall() {
        withTempDir("local-lib-heal") { dir ->
            val submitted = dir.resolve("submitted")
            IdeServices.bootstrapDemo(submitted).use { ide ->
                val libs = Path.of(assertNotNull(ide.dependencies.localLibraryDropDir("app")))
                val aar = aar(libs.resolve("widget.aar"))
                val jar = jar(libs.resolve("helper.jar"), "com/example/Helper.class")
                for (f in listOf(aar, jar)) {
                    val r = runBlocking { ide.dependencies.addLocalLibrary("app", f.toString(), "implementation") }
                    assertTrue(r.success, r.message)
                }
            }

            // What the recipient's device gets: another path, no resolved-library table, no exploded AARs.
            val installed = dir.resolve("installed")
            Files.move(submitted, installed)
            Files.delete(installed.resolve(".platform/libraries.json"))
            installed.resolve(".platform/libs").deleteRecursively()

            IdeServices.open(installed).use { ide ->
                assertTrue(ide.dependencies.healLocalLibraries(), "nothing was healed")

                val widget = assertNotNull(ide.store.workspace.libraryTable.byName("widget.aar"))
                assertEquals(LibraryKind.AAR, widget.kind)
                assertTrue(widget.classesRoots.isNotEmpty())
                for (r in widget.classesRoots) {
                    val p = Path.of(r.path).toAbsolutePath().normalize()
                    assertTrue(p.startsWith(installed.toAbsolutePath().normalize()), "root outside the install: $p")
                    assertTrue(Files.isRegularFile(p), "missing root $p")
                }

                val helper = assertNotNull(ide.store.workspace.libraryTable.byName("helper.jar"))
                assertEquals(LibraryKind.JAR, helper.kind)
                assertEquals(
                    installed.resolve("app/libs/helper.jar").toAbsolutePath().normalize(),
                    Path.of(helper.classesRoots.single().path).toAbsolutePath().normalize(),
                )

                // The row the user saw: an AAR, not a "local jar".
                val nodes = assertNotNull(runBlocking { ide.dependencies.moduleDependencies("app") }).declared
                assertEquals(UiDepKind.Aar, nodes.single { it.name == "widget.aar" }.kind)
                assertEquals(UiDepKind.Jar, nodes.single { it.name == "helper.jar" }.kind)

                // A second pass finds everything healthy.
                assertTrue(!ide.dependencies.healLocalLibraries(), "a healthy library was re-registered")
            }
        }
    }

    private fun aar(path: Path): Path {
        val classes = java.io.ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { z ->
                z.putNextEntry(ZipEntry("com/example/Widget.class")); z.write(byteArrayOf(0xCA.toByte(), 0xFE.toByte())); z.closeEntry()
            }
        }.toByteArray()
        Files.createDirectories(path.parent)
        ZipOutputStream(Files.newOutputStream(path)).use { z ->
            z.putNextEntry(ZipEntry("AndroidManifest.xml"))
            z.write("""<manifest package="com.example.widget"/>""".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("classes.jar")); z.write(classes); z.closeEntry()
            z.putNextEntry(ZipEntry("res/values/attrs.xml"))
            z.write("""<resources><declare-styleable name="Widget"><attr name="title" format="string"/></declare-styleable></resources>""".toByteArray())
            z.closeEntry()
        }
        return path.toAbsolutePath().normalize()
    }

    private fun jar(path: Path, entry: String): Path {
        Files.createDirectories(path.parent)
        ZipOutputStream(Files.newOutputStream(path)).use { z ->
            z.putNextEntry(ZipEntry(entry)); z.write(byteArrayOf(0xCA.toByte(), 0xFE.toByte())); z.closeEntry()
        }
        return path.toAbsolutePath().normalize()
    }
}
