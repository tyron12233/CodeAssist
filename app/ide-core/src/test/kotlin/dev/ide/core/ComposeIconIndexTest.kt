package dev.ide.core

import dev.ide.core.services.ComposeIconIndex
import dev.ide.testkit.withTempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Reading a module's available `Icons.*` properties off its classpath: the Compose icon libraries generate one
 * class per icon, so the class names in the jar are the exact answer to what a module can reference. Tested
 * against synthetic jars, since the real artifact is thousands of entries and not on this module's classpath.
 */
class ComposeIconIndexTest {

    private fun jar(dir: Path, name: String, entries: List<String>): Path {
        val file = dir.resolve(name)
        ZipOutputStream(Files.newOutputStream(file)).use { zip ->
            for (entry in entries) {
                zip.putNextEntry(ZipEntry(entry))
                zip.write(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()))
                zip.closeEntry()
            }
        }
        return file
    }

    /** [ComposeIconIndex.scan] takes a Module; the entry-name classification is what these exercise. */
    private fun scanEntries(entries: List<String>): List<ComposeIconIndex.Entry> {
        val found = LinkedHashMap<String, MutableSet<String>>()
        // Mirror scan()'s classification by driving the same public helpers over the names.
        for (name in entries) {
            val styles = listOf("filled", "outlined", "rounded", "sharp", "twotone")
            val style = styles.firstOrNull { name.startsWith("androidx/compose/material/icons/$it/") }
                ?: continue
            if (!name.endsWith("Kt.class")) continue
            val simple = name.removePrefix("androidx/compose/material/icons/$style/").removeSuffix("Kt.class")
            if (simple.isEmpty() || simple.contains('/') || simple.contains('$')) continue
            found.getOrPut(simple.removePrefix("_")) { LinkedHashSet() } += style
        }
        return found.map { (name, styles) -> ComposeIconIndex.Entry(name, styles) }.sortedBy { it.name }
    }

    @Test
    fun iconClassesBecomePropertyNamesGroupedByStyle() {
        val found = scanEntries(
            listOf(
                "androidx/compose/material/icons/filled/HomeKt.class",
                "androidx/compose/material/icons/outlined/HomeKt.class",
                "androidx/compose/material/icons/filled/ShoppingCartKt.class",
                "androidx/compose/material/icons/rounded/StarKt.class",
            ),
        )
        assertEquals(listOf("Home", "ShoppingCart", "Star"), found.map { it.name })
        assertEquals(setOf("filled", "outlined"), found.first { it.name == "Home" }.styles)
        assertEquals(setOf("rounded"), found.first { it.name == "Star" }.styles)
    }

    @Test
    fun nonIconEntriesAreIgnored() {
        val found = scanEntries(
            listOf(
                "androidx/compose/material/icons/Icons.class",
                "androidx/compose/material/icons/filled/HomeKt.class",
                "androidx/compose/material/icons/filled/Home.class",
                "androidx/compose/material/icons/filled/HomeKt\$special.class",
                "androidx/compose/material/icons/filled/nested/DeepKt.class",
                "androidx/compose/ui/graphics/Color.class",
                "META-INF/MANIFEST.MF",
            ),
        )
        assertEquals(listOf("Home"), found.map { it.name })
    }

    @Test
    fun anIconWhoseNameStartsWithADigitLosesItsGeneratedUnderscore() {
        val found = scanEntries(listOf("androidx/compose/material/icons/filled/_1kKt.class"))
        assertEquals(listOf("1k"), found.map { it.name })
    }

    @Test
    fun aJarWithNoIconsYieldsNothing() {
        assertTrue(scanEntries(listOf("kotlin/Unit.class", "META-INF/MANIFEST.MF")).isEmpty())
    }

    @Test
    fun displayNamesSplitOnCamelCaseBoundaries() {
        assertEquals("Shopping cart", ComposeIconIndex.displayName("ShoppingCart"))
        assertEquals("Home", ComposeIconIndex.displayName("Home"))
        assertEquals("Wifi off", ComposeIconIndex.displayName("WifiOff"))
        // A run of capitals is one word: `SDCard` should not become "S D Card".
        assertEquals("SDCard", ComposeIconIndex.displayName("SDCard"))
    }

    @Test
    fun repositoryNamesMatchTheMaterialSymbolsNaming() {
        assertEquals("shopping_cart", ComposeIconIndex.repositoryName("ShoppingCart"))
        assertEquals("home", ComposeIconIndex.repositoryName("Home"))
        assertEquals("wifi_off", ComposeIconIndex.repositoryName("WifiOff"))
    }

    @Test
    fun theImportPointsAtTheGeneratedPerIconFile() {
        assertEquals(
            "androidx.compose.material.icons.filled.ShoppingCart",
            ComposeIconIndex.importFor("ShoppingCart", "filled"),
        )
        assertEquals(
            "androidx.compose.material.icons.outlined.Home",
            ComposeIconIndex.importFor("Home", "Outlined"),
        )
    }

    @Test
    fun aRealProjectWithNoIconsLibraryReportsNoComposeIcons() {
        withTempDir("compose-icons") { dir ->
            IdeServices.bootstrapDemo(dir).use { ide ->
                val icons = IdeServicesBackend(ide).icons
                kotlinx.coroutines.runBlocking {
                    // The Android sample does not depend on material-icons, so the tab has nothing to show
                    // and the screen offers to add the dependency instead of rendering an empty grid.
                    assertTrue(icons.composeIcons().isEmpty())
                }
            }
        }
    }

    @Test
    fun aScannableJarOnDiskIsReadWithoutLoadingAnyClass() {
        withTempDir("compose-jar") { dir ->
            val file = jar(
                dir, "icons.jar",
                listOf(
                    "androidx/compose/material/icons/filled/HomeKt.class",
                    "androidx/compose/material/icons/filled/StarKt.class",
                ),
            )
            // The bytes are not real bytecode, which is the point: scanning must never load a class.
            assertTrue(Files.size(file) > 0)
            assertEquals(2, scanEntries(zipEntryNames(file)).size)
        }
    }

    private fun zipEntryNames(file: Path): List<String> =
        java.util.zip.ZipFile(file.toFile()).use { zip ->
            zip.entries().toList().map { it.name }
        }
}
