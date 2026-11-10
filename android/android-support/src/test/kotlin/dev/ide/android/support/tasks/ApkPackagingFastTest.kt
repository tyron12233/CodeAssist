package dev.ide.android.support.tasks

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The packager copies aapt2's compressed entries as they are and reuses unchanged entries from the previous APK.
 * The APK must read back exactly as one the old (inflate + re-deflate) packager wrote: same entries, same order,
 * same bytes once uncompressed, `resources.arsc` still STORED.
 */
class ApkPackagingFastTest {
    private fun tmp(): Path = Files.createTempDirectory("apk-fast")

    private fun resourcesAp(dir: Path): Path {
        val ap = dir.resolve("resources.ap_")
        ZipOutputStream(Files.newOutputStream(ap)).use { z ->
            val arsc = ByteArray(4096) { (it % 7).toByte() }
            z.putNextEntry(ZipEntry("resources.arsc").apply {
                method = ZipEntry.STORED; size = arsc.size.toLong(); compressedSize = arsc.size.toLong()
                crc = java.util.zip.CRC32().apply { update(arsc) }.value
            }); z.write(arsc); z.closeEntry()
            z.putNextEntry(ZipEntry("AndroidManifest.xml")); z.write("manifest".repeat(100).toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("res/layout/main.xml")); z.write("<layout/>".repeat(50).toByteArray()); z.closeEntry()
        }
        return ap
    }

    private fun contents(apk: Path): List<Triple<String, Int, String>> = ZipFile(apk.toFile()).use { z ->
        z.entries().toList().map { e -> Triple(e.name, e.method, z.getInputStream(e).use { String(it.readBytes(), Charsets.ISO_8859_1) }) }
    }

    @Test
    fun theFastApkReadsBackLikeTheOldOneAndAnEditRewritesOnlyWhatChanged() {
        val dir = tmp()
        val ap = resourcesAp(dir)
        val dex = Files.createDirectories(dir.resolve("dex/project"))
        val ext = Files.createDirectories(dir.resolve("dex/ext"))
        Files.write(dex.resolve("classes.dex"), ByteArray(50_000) { (it % 13).toByte() })
        Files.write(ext.resolve("classes.dex"), ByteArray(80_000) { (it % 17).toByte() })
        val assets = Files.createDirectories(dir.resolve("assets"))
        Files.write(assets.resolve("data.json"), "{\"a\":1}".toByteArray())
        val apk = dir.resolve("out/app.apk")
        val dexDirs = listOf(dir.resolve("dex/project"), dir.resolve("dex/ext"))
        fun pack() = ApkPackaging.assembleApk(ap, dexDirs, listOf(assets), emptyList(), apk, compressionLevel = Deflater.BEST_SPEED)

        pack()
        val first = contents(apk)
        assertEquals(listOf("AndroidManifest.xml", "res/layout/main.xml", "resources.arsc", "classes.dex", "classes2.dex", "assets/data.json"), first.map { it.first })
        assertEquals(ZipEntry.STORED, first.first { it.first == "resources.arsc" }.second, "resources.arsc stays STORED")
        assertEquals(String(Files.readAllBytes(ext.resolve("classes.dex")), Charsets.ISO_8859_1), first.first { it.first == "classes2.dex" }.third)

        // An edit changes the project's dex only.
        Files.write(dex.resolve("classes.dex"), ByteArray(50_000) { (it % 11).toByte() })
        pack()
        assertEquals(2 to 1, ApkPackaging.lastReuse, "the library dex and the asset reused, the project dex rewritten")
        val second = contents(apk)
        assertEquals(String(Files.readAllBytes(dex.resolve("classes.dex")), Charsets.ISO_8859_1), second.first { it.first == "classes.dex" }.third)
        assertEquals(first.filter { it.first != "classes.dex" }, second.filter { it.first != "classes.dex" })
        assertTrue(Files.size(apk) > 0)
    }
}
