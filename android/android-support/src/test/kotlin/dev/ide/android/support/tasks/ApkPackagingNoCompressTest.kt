package dev.ide.android.support.tasks

import dev.ide.android.support.AndroidPackaging
import dev.ide.testkit.withTempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `androidResources.noCompress` in the packager: a matching entry is STORED (so `AssetManager.openFd()` and
 * memory-mapping work), everything else is still deflated, and AGP's built-in defaults apply with no
 * configuration at all.
 */
class ApkPackagingNoCompressTest {

    private val payload = ByteArray(64 * 1024) { (it % 7).toByte() }

    private fun writeZip(path: Path, entries: Map<String, ByteArray>) {
        path.parent?.let { Files.createDirectories(it) }
        ZipOutputStream(Files.newOutputStream(path)).use { zos ->
            for ((name, bytes) in entries) {
                zos.putNextEntry(ZipEntry(name)); zos.write(bytes); zos.closeEntry()
            }
        }
    }

    private fun write(path: Path, bytes: ByteArray): Path {
        path.parent?.let { Files.createDirectories(it) }
        return Files.write(path, bytes)
    }

    private fun methods(apk: Path): Map<String, Int> =
        ZipFile(apk.toFile()).use { z -> z.entries().toList().associate { it.name to it.method } }

    @Test
    fun noCompressStoresMatchingEntriesEverywhere() {
        withTempDir("apk-nocompress") { dir ->
            val ap = dir.resolve("resources.ap_").also {
                writeZip(it, mapOf("AndroidManifest.xml" to payload, "res/raw/weights.bin" to payload))
            }
            val assets = dir.resolve("assets")
            write(assets.resolve("model.tflite"), payload)
            write(assets.resolve("data/table.BIN"), payload)
            write(assets.resolve("song.mp3"), payload)
            write(assets.resolve("notes.txt"), payload)
            val dex = dir.resolve("dex")
            write(dex.resolve("classes.dex"), payload)
            val javaRes = dir.resolve("java-res.jar").also { writeZip(it, mapOf("lookup.bin" to payload, "x.properties" to payload)) }
            val outApk = dir.resolve("out/app.apk")

            ApkPackaging.assembleApk(
                resourcesAp = ap,
                dexDirs = listOf(dex),
                assetsDirs = listOf(assets),
                jniLibDirs = emptyList(),
                outApk = outApk,
                javaResJars = listOf(javaRes),
                noCompress = AndroidPackaging(noCompress = setOf("bin"))::isNoCompressed,
            )

            val m = methods(outApk)
            // The module's suffix, case-insensitively, whichever input the entry came from.
            assertEquals(ZipEntry.STORED, m["assets/data/table.BIN"])
            assertEquals(ZipEntry.STORED, m["res/raw/weights.bin"], "aapt2's deflated entry re-stored")
            assertEquals(ZipEntry.STORED, m["lookup.bin"], "Java resource stored")
            // AGP's defaults, with nothing configured for them.
            assertEquals(ZipEntry.STORED, m["assets/model.tflite"])
            assertEquals(ZipEntry.STORED, m["assets/song.mp3"])
            // Everything else stays deflated.
            assertEquals(ZipEntry.DEFLATED, m["assets/notes.txt"])
            assertEquals(ZipEntry.DEFLATED, m["classes.dex"])
            assertEquals(ZipEntry.DEFLATED, m["x.properties"])
            assertEquals(ZipEntry.DEFLATED, m["AndroidManifest.xml"])

            // A stored entry reads back byte for byte (its CRC and size were written correctly).
            ZipFile(outApk.toFile()).use { z ->
                for (name in listOf("assets/data/table.BIN", "res/raw/weights.bin", "lookup.bin")) {
                    assertContentEquals(payload, z.getInputStream(z.getEntry(name)).readBytes(), name)
                }
            }
        }
    }

    @Test
    fun emptySuffixStoresEverything() {
        withTempDir("apk-nocompress-all") { dir ->
            val ap = dir.resolve("resources.ap_").also { writeZip(it, mapOf("AndroidManifest.xml" to payload)) }
            val assets = dir.resolve("assets").also { write(it.resolve("notes.txt"), payload) }
            val dex = dir.resolve("dex").also { write(it.resolve("classes.dex"), payload) }
            val outApk = dir.resolve("app.apk")

            ApkPackaging.assembleApk(
                ap, listOf(dex), listOf(assets), emptyList(), outApk,
                noCompress = AndroidPackaging(noCompress = setOf(""))::isNoCompressed,
            )

            assertTrue(methods(outApk).values.all { it == ZipEntry.STORED }, "AGP's \"\" means compress nothing: ${methods(outApk)}")
        }
    }

    @Test
    fun bundleGlobsMatchCaseInsensitively() {
        val globs = AndroidPackaging.bundleGlobs(listOf(".bin", "tflite"))
        assertEquals(listOf("**[.][bB][iI][nN]", "**[tT][fF][lL][iI][tT][eE]"), globs)
        assertFalse(AndroidPackaging().isNoCompressed("assets/data.bin"), "bin is not a default")
        assertTrue(AndroidPackaging().isNoCompressed("assets/PHOTO.JPG"), "defaults ignore case")
    }
}
