package dev.ide.kotlin.classfile

import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry as JvmZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The archive reader and the DEFLATE decoder, diffed against `java.util.zip`.
 *
 * This is the other half of reading a classpath with no JVM, and the half that is an algorithm rather than
 * a format walk. A decompressor cannot be spot-checked: it is either bit-exact over real input or it is
 * quietly wrong somewhere inside a Huffman table, so every entry of every jar on the test classpath is
 * inflated by both and compared byte for byte.
 */
class ZipOracleTest {

    private fun jars(): List<File> =
        System.getProperty("java.class.path").orEmpty()
            .split(File.pathSeparator)
            .map(::File)
            .filter { it.isFile && it.name.endsWith(".jar") }
            .sortedBy { it.name }

    private fun androidJar(): File? {
        val home = System.getenv("ANDROID_HOME")
            ?: System.getenv("ANDROID_SDK_ROOT")
            ?: "${System.getProperty("user.home")}/Library/Android/sdk"
        return File(home, "platforms").listFiles().orEmpty()
            .mapNotNull { File(it, "android.jar").takeIf(File::isFile) }
            .maxByOrNull { it.parentFile.name }
    }

    @Test
    fun everyEntryOfEveryJarInflatesToTheSameBytes() {
        val jars = jars() + listOfNotNull(androidJar())
        assertTrue(jars.size > 3, "expected a classpath of jars; found ${jars.size}")

        var archives = 0
        var entries = 0
        var bytes = 0L
        var deflated = 0
        var stored = 0

        for (jar in jars) {
            val ours = assertNotNull(ZipArchive.open(ByteArraySource(jar.readBytes())), "opening ${jar.name}")
            ZipFile(jar).use { theirs ->
                val expected = theirs.entries().asSequence().toList()
                assertEquals(
                    expected.map { it.name },
                    ours.entries.map { it.name },
                    "entry names and order in ${jar.name}",
                )

                for (theirEntry in expected) {
                    val mine = assertNotNull(ours.entry(theirEntry.name), "${theirEntry.name} in ${jar.name}")
                    val where = "${theirEntry.name} in ${jar.name}"
                    assertEquals(theirEntry.size, mine.size, "size of $where")
                    assertEquals(theirEntry.compressedSize, mine.compressedSize, "compressed size of $where")
                    assertEquals(theirEntry.crc, mine.crc32, "crc of $where")
                    assertEquals(theirEntry.method, mine.method, "method of $where")
                    assertEquals(theirEntry.isDirectory, mine.isDirectory, "directory flag of $where")

                    if (mine.isDirectory) continue
                    val expectedBytes = theirs.getInputStream(theirEntry).use { it.readBytes() }
                    val actualBytes = assertNotNull(ours.read(mine), "reading $where")
                    assertTrue(expectedBytes.contentEquals(actualBytes), "contents of $where")

                    entries++
                    bytes += actualBytes.size
                    when (mine.method) {
                        JvmZipEntry.DEFLATED -> deflated++
                        JvmZipEntry.STORED -> stored++
                    }
                }
            }
            archives++
        }

        assertTrue(entries > 5000, "expected a real corpus; read $entries entries")
        assertTrue(deflated > 5000, "expected mostly deflated entries; found $deflated")
        println(
            "zip: $archives archives, $entries entries ($deflated deflated, $stored stored), " +
                "${bytes / 1024 / 1024} MB inflated byte for byte",
        )
    }

    @Test
    fun storedAndDeflatedEntriesBothRoundTrip() {
        // A jar is almost entirely deflated, so the stored path can go untested by accident. Both methods
        // are written here on purpose, with content chosen so that one of them is incompressible: a stored
        // entry in the wild is usually something DEFLATE could not shrink.
        val file = File.createTempFile("kotlin-classfile-oracle", ".zip")
        file.deleteOnExit()
        val random = Random(20260917)
        val incompressible = ByteArray(64 * 1024) { random.nextInt().toByte() }
        val compressible = ByteArray(64 * 1024) { 'a'.code.toByte() }
        val empty = ByteArray(0)

        ZipOutputStream(file.outputStream()).use { out ->
            out.putNextEntry(JvmZipEntry("deflated.bin").also { it.method = JvmZipEntry.DEFLATED })
            out.write(compressible)
            out.closeEntry()
            // A STORED entry has to state its own size and crc up front: the writer will not compute them.
            out.putNextEntry(
                JvmZipEntry("stored.bin").apply {
                    method = JvmZipEntry.STORED
                    size = incompressible.size.toLong()
                    compressedSize = incompressible.size.toLong()
                    crc = CRC32().apply { update(incompressible) }.value
                },
            )
            out.write(incompressible)
            out.closeEntry()
            out.putNextEntry(JvmZipEntry("empty.bin"))
            out.write(empty)
            out.closeEntry()
            out.putNextEntry(JvmZipEntry("directory/"))
            out.closeEntry()
        }

        val archive = assertNotNull(ZipArchive.open(file.readBytes()))
        assertEquals(
            listOf("deflated.bin", "stored.bin", "empty.bin", "directory/"),
            archive.entries.map { it.name },
        )
        assertTrue(compressible.contentEquals(archive.read(archive.entry("deflated.bin")!!)))
        assertTrue(incompressible.contentEquals(archive.read(archive.entry("stored.bin")!!)))
        assertTrue(empty.contentEquals(archive.read(archive.entry("empty.bin")!!)))
        assertTrue(archive.entry("directory/")!!.isDirectory)
    }

    @Test
    fun aZip64ArchiveIsReadLikeAnyOther() {
        // Over 65,535 entries and the 32-bit fields saturate, the real values moving into a zip64 record
        // that is only reachable through a locator before the end record. Nothing warns: a reader without
        // the zip64 path sees an archive of 65,535 entries starting at offset 0xFFFFFFFF. Big AARs and
        // shaded jars cross this, so the path is exercised rather than assumed.
        val file = File.createTempFile("kotlin-classfile-zip64", ".zip")
        file.deleteOnExit()
        val count = 70_000

        ZipOutputStream(file.outputStream().buffered()).use { out ->
            for (i in 0 until count) {
                out.putNextEntry(JvmZipEntry("entry-$i.txt"))
                out.write("content $i".encodeToByteArray())
                out.closeEntry()
            }
        }

        val archive = assertNotNull(ZipArchive.open(file.readBytes()), "opening a zip64 archive")
        assertEquals(count, archive.entries.size)
        assertEquals("entry-0.txt", archive.entries.first().name)
        assertEquals("entry-${count - 1}.txt", archive.entries.last().name)
        for (i in listOf(0, 1, 65_534, 65_535, 65_536, count - 1)) {
            val entry = assertNotNull(archive.entry("entry-$i.txt"), "entry $i")
            assertEquals("content $i", assertNotNull(archive.read(entry)).decodeToString())
        }
    }

    @Test
    fun corruptedContentIsRejectedRatherThanReturned() {
        // The checksum is the only independent statement in the archive about whether our own decompressor
        // got it right, which is exactly why it is verified rather than trusted. A silently wrong inflate
        // produces a plausible class file, and a plausible class file is worse than none.
        val file = File.createTempFile("kotlin-classfile-corrupt", ".zip")
        file.deleteOnExit()
        ZipOutputStream(file.outputStream()).use { out ->
            out.putNextEntry(JvmZipEntry("payload.bin"))
            out.write(ByteArray(8192) { (it % 251).toByte() })
            out.closeEntry()
        }

        val bytes = file.readBytes()
        val intact = assertNotNull(ZipArchive.open(bytes))
        assertNotNull(intact.read(intact.entry("payload.bin")!!))

        // Flip a byte inside the compressed data, which starts after the local header and the name.
        val dataStart = 30 + "payload.bin".length
        bytes[dataStart + 40] = (bytes[dataStart + 40].toInt() xor 0x5A).toByte()
        val damaged = assertNotNull(ZipArchive.open(bytes))
        assertNull(damaged.read(damaged.entry("payload.bin")!!), "a damaged entry must not be returned")
    }

    @Test
    fun garbageIsRejectedRatherThanThrown() {
        assertNull(ZipArchive.open(ByteArray(0)))
        assertNull(ZipArchive.open(byteArrayOf(1, 2, 3, 4)))
        assertNull(ZipArchive.open(ByteArray(500) { it.toByte() }))
    }

    @Test
    fun inflateSpeedIsMeasuredRatherThanAssumed() {
        // Prints, asserts nothing. An index inflates thousands of entries per jar, so the cost of decoding
        // bit by bit instead of through a lookup table is a number worth having written down rather than
        // guessed at; a timing assertion in CI would only be flaky. If this ratio ever matters, the fix is
        // a table-driven decoder, and the correctness oracle above is what makes that change safe to try.
        val jar = jars().firstOrNull { it.name.startsWith("kotlin-stdlib-") } ?: return
        val bytes = jar.readBytes()
        val archive = assertNotNull(ZipArchive.open(ByteArraySource(bytes)))
        val entries = archive.entries.filter { !it.isDirectory }

        repeat(2) { for (entry in entries) archive.read(entry) } // warm up the JIT
        val ourStart = System.nanoTime()
        var inflated = 0L
        for (entry in entries) inflated += archive.read(entry)?.size?.toLong() ?: 0
        val ourMillis = (System.nanoTime() - ourStart) / 1_000_000.0

        ZipFile(jar).use { theirs ->
            repeat(2) {
                theirs.entries().asSequence().forEach { e ->
                    if (!e.isDirectory) theirs.getInputStream(e).use { it.readBytes() }
                }
            }
        }
        val theirStart = System.nanoTime()
        ZipFile(jar).use { theirs ->
            theirs.entries().asSequence().forEach { e ->
                if (!e.isDirectory) theirs.getInputStream(e).use { it.readBytes() }
            }
        }
        val theirMillis = (System.nanoTime() - theirStart) / 1_000_000.0

        println(
            "inflate speed on ${jar.name}: ${entries.size} entries, ${inflated / 1024} KB; " +
                "ours ${"%.0f".format(ourMillis)} ms vs java.util.zip ${"%.0f".format(theirMillis)} ms " +
                "(${"%.1f".format(ourMillis / theirMillis)}x)",
        )
    }

    @Test
    fun aClassIsReadStraightOutOfAJar() {
        // The end the two halves exist for: a jar goes in, a decoded class comes out, with nothing from the
        // JVM in between.
        val jar = jars().firstOrNull { it.name.startsWith("kotlin-stdlib-") }
        if (jar == null) {
            println("kotlin-stdlib is not on the test classpath; skipping")
            return
        }
        val archive = assertNotNull(ZipArchive.open(ByteArraySource(jar.readBytes())))
        val entry = assertNotNull(archive.entry("kotlin/text/StringsKt.class"))
        val parts = assertNotNull(
            KotlinMetadata.readMultiFileParts(assertNotNull(ClassFile.read(assertNotNull(archive.read(entry)))!!.metadata)),
        )
        assertTrue(parts.isNotEmpty(), "StringsKt is a multi-file facade")

        val part = assertNotNull(archive.entry("${parts.first()}.class"))
        val decoded = assertNotNull(KotlinMetadata.read(ClassFile.read(archive.read(part)!!)!!.metadata!!))
        assertTrue(decoded.declarations.isNotEmpty())
    }
}
