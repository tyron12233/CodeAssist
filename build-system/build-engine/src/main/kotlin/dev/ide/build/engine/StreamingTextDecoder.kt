package dev.ide.build.engine

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction

/**
 * Decodes a byte stream to text incrementally, carrying a partial multi-byte sequence across calls. A
 * running program's stdout arrives in arbitrary chunks (a 3-byte UTF-8 char can straddle two reads), so
 * decoding each chunk independently would corrupt non-ASCII output. [decode] returns the text for the
 * complete characters seen so far and retains any trailing partial sequence for the next call; [flush]
 * drains the tail when the stream ends. Not thread-safe — use one instance per stream.
 */
class StreamingTextDecoder(charset: Charset = Charsets.UTF_8) {
    private val decoder: CharsetDecoder = charset.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    private var carry = ByteArray(0)

    fun decode(bytes: ByteArray, off: Int, len: Int): String {
        if (len == 0 && carry.isEmpty()) return ""
        val input: ByteBuffer = if (carry.isEmpty()) {
            ByteBuffer.wrap(bytes, off, len)
        } else {
            val combined = ByteArray(carry.size + len)
            System.arraycopy(carry, 0, combined, 0, carry.size)
            System.arraycopy(bytes, off, combined, carry.size, len)
            ByteBuffer.wrap(combined)
        }
        val out = CharBuffer.allocate(input.remaining() + 1)
        decoder.decode(input, out, false)
        carry = ByteArray(input.remaining()).also { input.get(it) }
        out.flip()
        return out.toString()
    }

    /** Drain any retained partial bytes at end-of-stream (replaced if still incomplete). */
    fun flush(): String {
        val input = ByteBuffer.wrap(carry)
        val out = CharBuffer.allocate(carry.size + 1)
        decoder.decode(input, out, true)
        decoder.flush(out)
        carry = ByteArray(0)
        out.flip()
        return out.toString()
    }
}
