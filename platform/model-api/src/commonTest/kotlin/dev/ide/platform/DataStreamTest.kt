package dev.ide.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Strings that exercise every branch of modified UTF-8, and one that exercises none of them. */
internal val AWKWARD_STRINGS = listOf(
    "",
    "a",
    "dev.ide.lang.kotlin.symbols.KotlinSymbol",
    // NUL is the whole reason the encoding is "modified": it takes two bytes so that no encoded byte is
    // zero. A one-byte NUL would terminate the string in every C consumer of the format.
    "before\u0000after",
    "café", // two-byte
    "20€", // three-byte
    "😀", // outside the basic plane: two surrogates, three bytes each, never one four-byte run
    "mixed \u0000 \u0001 é € 😀 end",
    (1..0x7F).joinToString("") { it.toChar().toString() },
    "x".repeat(20_000),
)

/**
 * The persisted index format, checked on every platform.
 *
 * Its bytes are not ours to choose: an index written by the desktop build's `DataOutputStream` has to be
 * readable here and the other way round. `DataStreamOracleTest` is what pins that against the real
 * `java.io`; this is the half that has to hold on the phone too.
 */
class DataStreamTest {

    @Test
    fun everyScalarRoundTrips() {
        val writer = ByteArrayDataWriter()
        writer.writeBoolean(true)
        writer.writeBoolean(false)
        writer.writeByte(-1)
        writer.writeByte(127)
        writer.writeShort(0)
        writer.writeShort(0xFFFF)
        writer.writeInt(Int.MIN_VALUE)
        writer.writeInt(Int.MAX_VALUE)
        writer.writeInt(0)
        writer.writeLong(Long.MIN_VALUE)
        writer.writeLong(Long.MAX_VALUE)

        val reader = ByteArrayDataReader(writer.toByteArray())
        assertEquals(true, reader.readBoolean())
        assertEquals(false, reader.readBoolean())
        assertEquals(-1, reader.readByte())
        assertEquals(127, reader.readByte())
        assertEquals(0, reader.readShort())
        assertEquals(0xFFFF, reader.readShort())
        assertEquals(Int.MIN_VALUE, reader.readInt())
        assertEquals(Int.MAX_VALUE, reader.readInt())
        assertEquals(0, reader.readInt())
        assertEquals(Long.MIN_VALUE, reader.readLong())
        assertEquals(Long.MAX_VALUE, reader.readLong())
        assertTrue(!reader.hasMore, "the reader consumed exactly what was written")
    }

    @Test
    fun everyAwkwardStringRoundTrips() {
        val writer = ByteArrayDataWriter()
        for (value in AWKWARD_STRINGS) writer.writeUTF(value)
        val reader = ByteArrayDataReader(writer.toByteArray())
        for (value in AWKWARD_STRINGS) assertEquals(value, reader.readUTF())
        assertTrue(!reader.hasMore)
    }

    @Test
    fun anOverLongStringIsRefusedRatherThanTruncated() {
        // The length prefix is sixteen bits, so there is no encoding for a longer string. Writing one
        // anyway would wrap the prefix and produce a file that reads back as something shorter and
        // plausible, which is worse than refusing.
        assertFailsWith<IllegalStateException> { ByteArrayDataWriter().writeUTF("x".repeat(70_000)) }
        // Counted in BYTES, not characters: a three-byte character reaches the limit three times sooner.
        assertFailsWith<IllegalStateException> { ByteArrayDataWriter().writeUTF("€".repeat(22_000)) }
    }
}
