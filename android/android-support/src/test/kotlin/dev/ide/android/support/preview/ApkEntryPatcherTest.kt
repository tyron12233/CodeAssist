package dev.ide.android.support.preview

import dev.ide.android.support.ApkEntryPatcher
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApkEntryPatcherTest {

    private val arsc = ByteArray(4096) { (it % 251).toByte() }
    private val layout = "<old layout/>".repeat(50).toByteArray()
    private val other = "other file".toByteArray()

    private fun apk(dir: java.nio.file.Path) = dir.resolve("full.ap_").also { p ->
        ZipOutputStream(Files.newOutputStream(p)).use { z ->
            z.putNextEntry(ZipEntry("AndroidManifest.xml")); z.write("<manifest/>".toByteArray()); z.closeEntry()
            // aapt2 stores the table uncompressed so it can be memory-mapped.
            z.putNextEntry(ZipEntry("resources.arsc").apply {
                method = ZipEntry.STORED; size = arsc.size.toLong(); compressedSize = size
                crc = CRC32().apply { update(arsc) }.value
            }); z.write(arsc); z.closeEntry()
            z.putNextEntry(ZipEntry("res/layout/activity_main.xml")); z.write(layout); z.closeEntry()
            z.putNextEntry(ZipEntry("res/layout/other.xml")); z.write(other); z.closeEntry()
        }
    }

    private fun offsetOf(zip: java.nio.file.Path, name: String): Long {
        // The local header offset, read from the central directory by scanning for the entry's name.
        val bytes = Files.readAllBytes(zip)
        val nb = name.toByteArray()
        var i = bytes.size - 22
        while (i > 0 && !(bytes[i] == 0x50.toByte() && bytes[i + 1] == 0x4b.toByte() && bytes[i + 2] == 5.toByte() && bytes[i + 3] == 6.toByte())) i--
        var p = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(i + 16)
        val bb = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        while (true) {
            val n = bb.getShort(p + 28).toInt(); val m = bb.getShort(p + 30).toInt(); val k = bb.getShort(p + 32).toInt()
            if (bytes.copyOfRange(p + 46, p + 46 + n).contentEquals(nb)) return bb.getInt(p + 42).toLong()
            p += 46 + n + m + k
        }
    }

    @Test
    fun replacesAnEntryAndKeepsTheRestInPlace() {
        val dir = Files.createTempDirectory("apk-patch")
        val src = apk(dir)
        val dst = dir.resolve("resources.ap_")
        val edited = "<new layout/>".toByteArray()

        assertTrue(ApkEntryPatcher.replace(src, dst, mapOf("res/layout/activity_main.xml" to edited)))
        ZipFile(dst.toFile()).use { z ->
            assertEquals(4, z.size())
            assertContentEquals(edited, z.getInputStream(z.getEntry("res/layout/activity_main.xml")).readBytes())
            assertContentEquals(arsc, z.getInputStream(z.getEntry("resources.arsc")).readBytes())
            assertContentEquals(other, z.getInputStream(z.getEntry("res/layout/other.xml")).readBytes())
        }
        assertEquals(offsetOf(src, "resources.arsc"), offsetOf(dst, "resources.arsc"), "the table must not move")

        // Patching the patched copy again works too (the edit after the next one).
        val again = dir.resolve("again.ap_")
        assertTrue(ApkEntryPatcher.replace(dst, again, mapOf("res/layout/activity_main.xml" to "<third/>".toByteArray())))
        ZipFile(again.toFile()).use { z ->
            assertContentEquals("<third/>".toByteArray(), z.getInputStream(z.getEntry("res/layout/activity_main.xml")).readBytes())
        }
    }

    @Test
    fun refusesANameTheZipLacks() {
        val dir = Files.createTempDirectory("apk-patch")
        val dst = dir.resolve("resources.ap_")
        assertFalse(ApkEntryPatcher.replace(apk(dir), dst, mapOf("res/layout/new.xml" to ByteArray(1))))
        assertFalse(Files.exists(dst))
    }
}
