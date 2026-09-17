package dev.ide.kotlin.classfile

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.UTFDataFormatException
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The persistence format, diffed against `java.io`.
 *
 * Round-tripping with itself would prove nothing here. Every index this build persists was written by a
 * `DataOutputStream`, so the requirement is not "readable by us" but "the same bytes", in both directions:
 * an index built on the desktop has to open on the phone, and one built on the phone has to open on the
 * desktop.
 *
 * `writeUTF` is the part that can be subtly wrong. Modified UTF-8 differs from UTF-8 in exactly two places,
 * NUL and the supplementary plane, and a string containing neither encodes identically under both.
 */
class DataStreamOracleTest {

    private fun jvmBytes(write: DataOutputStream.() -> Unit): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { it.write() }
        return out.toByteArray()
    }

    @Test
    fun everyStringEncodesToTheSameBytesAsTheJvm() {
        for (value in AWKWARD_STRINGS) {
            if (value.length > 20_000) continue // the over-long case has its own test
            val theirs = jvmBytes { writeUTF(value) }
            val ours = DataWriter().apply { writeUTF(value) }.toByteArray()
            assertTrue(
                theirs.contentEquals(ours),
                "encoding of ${value.take(24)}: expected ${theirs.toHex()} but was ${ours.toHex()}",
            )
        }
    }

    @Test
    fun theJvmReadsWhatWeWriteAndTheOtherWayRound() {
        // One sequence in the shape a real index segment has: a count, then a record per entry mixing every
        // primitive the externalizers use.
        val names = AWKWARD_STRINGS.filter { it.length <= 20_000 }

        val ours = DataWriter().apply {
            writeInt(names.size)
            for ((index, name) in names.withIndex()) {
                writeUTF(name)
                writeBoolean(index % 2 == 0)
                writeInt(index * 7 - 3)
                writeByte(index)
                writeLong(index.toLong() shl 40)
            }
        }.toByteArray()

        val theirs = jvmBytes {
            writeInt(names.size)
            for ((index, name) in names.withIndex()) {
                writeUTF(name)
                writeBoolean(index % 2 == 0)
                writeInt(index * 7 - 3)
                writeByte(index)
                writeLong(index.toLong() shl 40)
            }
        }
        assertTrue(theirs.contentEquals(ours), "the whole segment must be byte for byte the same")

        // Their reader over our bytes.
        DataInputStream(ByteArrayInputStream(ours)).use { input ->
            assertEquals(names.size, input.readInt())
            for ((index, name) in names.withIndex()) {
                assertEquals(name, input.readUTF())
                assertEquals(index % 2 == 0, input.readBoolean())
                assertEquals(index * 7 - 3, input.readInt())
                assertEquals(index.toByte(), input.readByte())
                assertEquals(index.toLong() shl 40, input.readLong())
            }
            assertEquals(-1, input.read(), "their reader consumed exactly what we wrote")
        }

        // Our reader over theirs.
        val reader = DataReader(theirs)
        assertEquals(names.size, reader.readInt())
        for ((index, name) in names.withIndex()) {
            assertEquals(name, reader.readUTF())
            assertEquals(index % 2 == 0, reader.readBoolean())
            assertEquals(index * 7 - 3, reader.readInt())
            assertEquals(index, reader.readByte())
            assertEquals(index.toLong() shl 40, reader.readLong())
        }
        assertTrue(!reader.hasMore, "our reader consumed exactly what they wrote")
    }

    @Test
    fun bothRefuseAStringThatDoesNotFitTheLengthPrefix() {
        // Parity of FAILURE matters as much as parity of success: if one side wrote a wrapped length the
        // other would read a shorter, plausible string and carry on.
        val tooLong = "x".repeat(70_000)
        assertFailsWith<UTFDataFormatException> { jvmBytes { writeUTF(tooLong) } }
        assertFailsWith<IllegalStateException> { DataWriter().writeUTF(tooLong) }
    }

    @Test
    fun aJarIsIndexedThroughTheFileSeamWithoutBeingLoaded() {
        // The whole stack, in the shape an index would use it: a path goes in, symbols come out, and the
        // 43 MB never becomes a byte array.
        val jar = System.getProperty("java.class.path").orEmpty()
            .split(File.pathSeparator)
            .map(::File)
            .firstOrNull { it.isFile && it.name.startsWith("kotlin-stdlib-") && it.name.endsWith(".jar") }
        if (jar == null) {
            println("kotlin-stdlib is not on the test classpath; skipping")
            return
        }

        val source = assertNotNull(openFile(jar.absolutePath), "opening ${jar.name}")
        var classes = 0
        var declarations = 0
        try {
            val archive = assertNotNull(ZipArchive.open(source))
            ZipFile(jar).use { theirs ->
                assertEquals(
                    theirs.entries().asSequence().map { it.name }.toList(),
                    archive.entries.map { it.name },
                    "the same entries as java.util.zip sees",
                )
            }
            for (entry in archive.entries) {
                if (!entry.name.endsWith(".class")) continue
                val metadata = ClassFile.read(archive.read(entry) ?: continue)?.metadata ?: continue
                val decoded = KotlinMetadata.read(metadata) ?: continue
                classes++
                declarations += decoded.declarations.size
            }
        } finally {
            source.close()
        }

        assertTrue(classes > 500, "expected the stdlib's Kotlin classes; decoded $classes")
        assertTrue(declarations > 5000, "expected real declarations; decoded $declarations")
        println("file seam: $classes classes and $declarations declarations read out of ${jar.name} by path")
    }

    private fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it) }
}
