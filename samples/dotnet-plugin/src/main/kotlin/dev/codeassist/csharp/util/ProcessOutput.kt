package dev.codeassist.csharp.util

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/**
 * Decodes UTF-8 data arriving in arbitrary chunks while preserving incomplete characters between chunks.
 *
 * Reading a pipe in fixed-size blocks can split a multi-byte character across two blocks.
 * Decoding each block independently would replace the incomplete bytes with replacement characters.
 */
internal class Utf8Chunks {

    private val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)

    /** Bytes from the previous chunk that do not yet form a complete character. */
    private var pending = ByteArray(0)

    /** Decodes the first [length] bytes of [bytes], including any bytes carried over from the previous chunk. */
    fun decode(bytes: ByteArray, length: Int): String {
        val input = ByteBuffer.allocate(pending.size + length).put(pending).put(bytes, 0, length)
        input.flip()
        val out = CharBuffer.allocate(input.remaining() + 1)
        decoder.decode(input, out, false)
        pending = ByteArray(input.remaining()).also { input.get(it) }
        out.flip()
        return out.toString()
    }

    /** Decodes the remaining bytes after the stream ends, replacing any unfinished character. */
    fun finish(): String {
        val input = ByteBuffer.wrap(pending)
        val out = CharBuffer.allocate(pending.size + 1)
        decoder.decode(input, out, true)
        decoder.flush(out)
        pending = ByteArray(0)
        out.flip()
        return out.toString()
    }
}

/** Splits raw program output into lines for the build log when there is no console to stream to. */
internal class LineSplitter(private val log: (String) -> Unit) {

    private val pending = StringBuilder()

    /** Logs complete lines and retains any unfinished line for the next chunk. */
    @Synchronized
    fun append(text: String) {
        pending.append(text)
        var newline = pending.indexOf("\n")
        while (newline >= 0) {
            log(pending.substring(0, newline).trimEnd('\r'))
            pending.delete(0, newline + 1)
            newline = pending.indexOf("\n")
        }
    }

    /** Logs the remaining line, if any, when the program ends without a final newline. */
    @Synchronized
    fun flush() {
        if (pending.isNotEmpty()) log(pending.toString().trimEnd('\r'))
        pending.setLength(0)
    }
}