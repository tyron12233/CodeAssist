package dev.ide.platform

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.UTFDataFormatException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
            val ours = ByteArrayDataWriter().apply { writeUTF(value) }.toByteArray()
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

        val ours = ByteArrayDataWriter().apply {
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
        val reader = ByteArrayDataReader(theirs)
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
        assertFailsWith<IllegalStateException> { ByteArrayDataWriter().writeUTF(tooLong) }
    }

    private fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it) }
}
