package dev.ide.build.engine

import kotlin.test.Test
import kotlin.test.assertEquals

class StreamingTextDecoderTest {

    private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8)

    @Test
    fun decodesAsciiPerChunk() {
        val d = StreamingTextDecoder()
        val b = bytes("hello")
        assertEquals("hello", d.decode(b, 0, b.size))
        assertEquals("", d.flush())
    }

    @Test
    fun carriesAPartialMultiByteCharAcrossChunks() {
        // "é" is two UTF-8 bytes (0xC3 0xA9); "中" is three. Split them across reads — a per-chunk decode
        // would corrupt them, so the decoder must hold the partial sequence until the rest arrives.
        val full = bytes("aé中b")
        val d = StreamingTextDecoder()
        val sb = StringBuilder()
        // Feed one byte at a time — the worst case for multi-byte boundaries.
        for (byte in full) sb.append(d.decode(byteArrayOf(byte), 0, 1))
        sb.append(d.flush())
        assertEquals("aé中b", sb.toString())
    }

    @Test
    fun emitsCompleteCharsAndRetainsTheTail() {
        val d = StreamingTextDecoder()
        val b = bytes("中") // 3 bytes
        // First two bytes: incomplete char → nothing emitted yet.
        assertEquals("", d.decode(b, 0, 2))
        // Third byte completes it.
        assertEquals("中", d.decode(b, 2, 1))
    }

    @Test
    fun flushReplacesADanglingIncompleteSequence() {
        val d = StreamingTextDecoder()
        val b = bytes("中")
        assertEquals("", d.decode(b, 0, 1)) // a lone lead byte
        // At end-of-stream the incomplete byte is flushed as the replacement char (never lost / never blocks).
        assertEquals("�", d.flush())
    }
}
