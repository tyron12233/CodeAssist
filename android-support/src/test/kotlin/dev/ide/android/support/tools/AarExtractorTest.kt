package dev.ide.android.support.tools

import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [AarExtractor] must extract every part of an AAR byte-for-byte and, crucially, treat an extraction as
 * complete ONLY when the `.exploded` marker is present — so an interrupted extraction (which can leave a
 * mid-stream `classes.jar` but no `res/`/`assets/`) is corrected on the next run rather than reused.
 */
class AarExtractorTest {

    // A real 1x1 PNG — a binary asset whose bytes must round-trip through extraction unchanged.
    private val PNG_1X1 = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAC0lEQVR4nGNgYGAAAAAEAAH2FzhVAAAAAElFTkSuQmCC"
    )
    private val ASSET_BYTES = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7)

    /** A minimal AAR: a (empty-but-valid) classes.jar first, then manifest, a res PNG, and a binary asset. */
    private fun makeAar(dir: Path): Path {
        val aar = dir.resolve("lib.aar")
        ZipOutputStream(Files.newOutputStream(aar)).use { zos ->
            fun put(name: String, bytes: ByteArray) {
                zos.putNextEntry(ZipEntry(name)); zos.write(bytes); zos.closeEntry()
            }
            // 22-byte End-Of-Central-Directory = a valid archive with no entries.
            put("classes.jar", byteArrayOf(0x50, 0x4B, 0x05, 0x06, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0))
            put("AndroidManifest.xml", "<manifest/>".toByteArray())
            put("res/drawable/icon.png", PNG_1X1)
            put("assets/data.bin", ASSET_BYTES)
        }
        return aar
    }

    @Test
    fun explodesEveryPartByteForByte() {
        val work = Files.createTempDirectory("aar-explode")
        val aar = makeAar(work)
        val into = work.resolve("exploded")

        val out = AarExtractor.explode(aar, into)
        assertNotNull(out.resDir, "res/ surfaced")
        assertNotNull(out.assetsDir, "assets/ surfaced")
        assertNotNull(out.manifest, "manifest surfaced")
        assertTrue(out.classesJars.isNotEmpty(), "classes.jar surfaced")
        assertContentEquals(PNG_1X1, Files.readAllBytes(into.resolve("res/drawable/icon.png")), "PNG bytes intact")
        assertContentEquals(ASSET_BYTES, Files.readAllBytes(into.resolve("assets/data.bin")), "asset bytes intact")
        assertTrue(Files.isRegularFile(into.resolve(".exploded")), "completion marker written")

        work.toFile().deleteRecursively()
    }

    @Test
    fun reExtractsWhenAPreviousExtractionWasInterrupted() {
        val work = Files.createTempDirectory("aar-partial")
        val aar = makeAar(work)
        val into = work.resolve("exploded")
        // Simulate a crash mid-extract: classes.jar landed (truncated), but no marker and no res/assets.
        Files.createDirectories(into)
        Files.write(into.resolve("classes.jar"), byteArrayOf(9))

        val out = AarExtractor.explode(aar, into)
        assertNotNull(out.resDir, "res/ must be present after the corrective re-extract")
        assertContentEquals(PNG_1X1, Files.readAllBytes(into.resolve("res/drawable/icon.png")), "PNG re-extracted intact")
        assertTrue(Files.exists(into.resolve("assets/data.bin")), "assets/ re-extracted")
        assertTrue(Files.isRegularFile(into.resolve(".exploded")), "completion marker written")

        work.toFile().deleteRecursively()
    }

    @Test
    fun reusesACompleteExtraction() {
        val work = Files.createTempDirectory("aar-reuse")
        val aar = makeAar(work)
        val into = work.resolve("exploded")

        AarExtractor.explode(aar, into) // full extraction → writes .exploded
        val sentinel = into.resolve("sentinel.marker")
        Files.write(sentinel, byteArrayOf(1))

        AarExtractor.explode(aar, into) // marker present → reuse, no re-extract
        assertTrue(Files.exists(sentinel), "a complete extraction is reused, not swapped out from under callers")

        work.toFile().deleteRecursively()
    }
}
