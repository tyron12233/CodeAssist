package dev.ide.android.support

import dev.ide.android.support.tools.AarExtractor
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Two different `.aar` files that share a file name explode into separate directories, so the second one's
 *  classes and resources are not replaced by the first one's already-exploded directory. */
class AarExplodeDirTest {

    @Test
    fun sameNamedAarsFromDifferentFoldersExplodeSeparately() {
        val root = createTempDirectory("aar-explode-dir")
        val a = aar(root.resolve("x/libs/core.aar"), "a.txt")
        val b = aar(root.resolve("y/libs/core.aar"), "b.txt")

        assertNotEquals(AarExtractor.explodeDirName(a), AarExtractor.explodeDirName(b))
        assertEquals(AarExtractor.explodeDirName(a), AarExtractor.explodeDirName(a), "stable for one file")
        assertTrue(AarExtractor.explodeDirName(a).startsWith("core-"), "keeps the file stem readable")

        val out = root.resolve("out")
        val ea = AarExtractor.explode(a, out.resolve(AarExtractor.explodeDirName(a)))
        val eb = AarExtractor.explode(b, out.resolve(AarExtractor.explodeDirName(b)))
        val dirA = ea.classesJars.single().parent
        val dirB = eb.classesJars.single().parent
        assertTrue(Files.isRegularFile(dirA.resolve("a.txt")))
        assertTrue(Files.isRegularFile(dirB.resolve("b.txt")), "the second AAR's own contents, not the first's")
    }

    private fun aar(path: Path, marker: String): Path {
        Files.createDirectories(path.parent)
        ZipOutputStream(Files.newOutputStream(path)).use { z ->
            z.putNextEntry(ZipEntry("classes.jar")); z.write(emptyJar()); z.closeEntry()
            z.putNextEntry(ZipEntry(marker)); z.write(byteArrayOf(1)); z.closeEntry()
        }
        return path
    }

    private fun emptyJar(): ByteArray {
        val bytes = java.io.ByteArrayOutputStream()
        ZipOutputStream(bytes).use { z ->
            z.putNextEntry(ZipEntry("META-INF/MANIFEST.MF")); z.write("Manifest-Version: 1.0\r\n\r\n".toByteArray()); z.closeEntry()
        }
        return bytes.toByteArray()
    }
}
