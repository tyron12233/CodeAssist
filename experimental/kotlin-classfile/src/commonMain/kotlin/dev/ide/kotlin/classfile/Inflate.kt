package dev.ide.kotlin.classfile

/**
 * DEFLATE decompression (RFC 1951), because a jar is deflated and `java.util.zip` does not exist off the JVM.
 *
 * This is the one piece here that is an algorithm rather than a format walk, and it is small for what it
 * does: a bit reader, canonical Huffman decoding, and a back-reference copy. The format has exactly three
 * block types and no extension points, which is why a complete implementation fits in a few hundred lines
 * and why it will not need revisiting.
 *
 * Decoding is bit-at-a-time against the canonical code counts rather than table-driven. That is the slower
 * of the two standard shapes and the one whose correctness can be read off the spec; the faster one builds
 * a lookup table whose construction is where the bugs live. Checked byte for byte against
 * `java.util.zip.Inflater` over every entry of real jars.
 */
object Inflate {

    /**
     * Expand [input] into exactly [expectedSize] bytes, or null when the stream is not valid DEFLATE.
     *
     * The size is required rather than discovered because a zip entry always states it, and knowing it up
     * front means one allocation instead of a doubling buffer. A stream that wants to write more than that
     * is corrupt, and saying so is better than growing to fit whatever a malformed jar claims.
     */
    fun inflate(input: ByteArray, expectedSize: Int): ByteArray? =
        runCatching { Stream(input, expectedSize).inflate() }.getOrNull()

    // RFC 1951 section 3.2.5: the length and distance codes, and how many extra bits each one reads.
    private val LENGTH_BASE = intArrayOf(
        3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31,
        35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258,
    )
    private val LENGTH_EXTRA = intArrayOf(
        0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2,
        3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0,
    )
    private val DISTANCE_BASE = intArrayOf(
        1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193,
        257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097, 6145, 8193, 12289, 16385, 24577,
    )
    private val DISTANCE_EXTRA = intArrayOf(
        0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6,
        7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13,
    )

    /** The order the code-length code lengths are written in. Not sorted, and not derivable: it is a constant. */
    private val CODE_LENGTH_ORDER = intArrayOf(16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15)

    private val FIXED_LITERALS: Huffman by lazy {
        val lengths = IntArray(288)
        for (i in 0..143) lengths[i] = 8
        for (i in 144..255) lengths[i] = 9
        for (i in 256..279) lengths[i] = 7
        for (i in 280..287) lengths[i] = 8
        Huffman(lengths)
    }

    private val FIXED_DISTANCES: Huffman by lazy { Huffman(IntArray(30) { 5 }) }

    /**
     * A canonical Huffman code, stored the way the spec defines one: how many codes of each bit length, and
     * the symbols in order.
     *
     * That is all a canonical code IS. The actual bit patterns are implied by the lengths, so they are never
     * materialised, and the decoder walks the counts instead of looking anything up.
     */
    private class Huffman(lengths: IntArray) {
        val counts = IntArray(MAX_BITS + 1)
        val symbols: IntArray

        init {
            for (length in lengths) counts[length]++
            counts[0] = 0
            val offsets = IntArray(MAX_BITS + 1)
            for (bits in 1..MAX_BITS) offsets[bits] = offsets[bits - 1] + counts[bits - 1]
            symbols = IntArray(lengths.size)
            for (symbol in lengths.indices) {
                if (lengths[symbol] != 0) symbols[offsets[lengths[symbol]]++] = symbol
            }
        }

        companion object {
            const val MAX_BITS = 15
        }
    }

    private class Stream(private val input: ByteArray, expectedSize: Int) {
        private var at = 0
        private var bitBuffer = 0
        private var bitCount = 0

        private val output = ByteArray(expectedSize)
        private var written = 0

        fun inflate(): ByteArray {
            while (true) {
                val last = bits(1)
                when (bits(2)) {
                    0 -> stored()
                    1 -> block(FIXED_LITERALS, FIXED_DISTANCES)
                    2 -> dynamicBlock()
                    else -> error("reserved block type at $at")
                }
                if (last == 1) break
            }
            check(written == output.size) { "inflated $written bytes, expected ${output.size}" }
            return output
        }

        /** The next [count] bits, least significant first, which is the order DEFLATE packs them in. */
        private fun bits(count: Int): Int {
            while (bitCount < count) {
                bitBuffer = bitBuffer or ((input[at++].toInt() and 0xFF) shl bitCount)
                bitCount += 8
            }
            val value = bitBuffer and ((1 shl count) - 1)
            bitBuffer = bitBuffer ushr count
            bitCount -= count
            return value
        }

        /**
         * An uncompressed block: the bit buffer is discarded back to a byte boundary and the bytes are copied.
         *
         * The stored length is written twice, the second time inverted. It is checked because a mismatch is
         * the earliest sign that the bit position drifted, and drift otherwise shows up as plausible garbage
         * much further on.
         */
        private fun stored() {
            bitBuffer = 0
            bitCount = 0
            val length = (input[at].toInt() and 0xFF) or ((input[at + 1].toInt() and 0xFF) shl 8)
            val inverse = (input[at + 2].toInt() and 0xFF) or ((input[at + 3].toInt() and 0xFF) shl 8)
            check(length == inverse.inv() and 0xFFFF) { "stored block length mismatch at $at" }
            at += 4
            input.copyInto(output, written, at, at + length)
            at += length
            written += length
        }

        private fun dynamicBlock() {
            val literalCount = bits(5) + 257
            val distanceCount = bits(5) + 1
            val codeLengthCount = bits(4) + 4

            // The code lengths for the code that encodes the code lengths. The indirection is the point: it
            // is what lets a block describe its own alphabet in a few dozen bits.
            val codeLengths = IntArray(19)
            for (i in 0 until codeLengthCount) codeLengths[CODE_LENGTH_ORDER[i]] = bits(3)
            val codeLengthCode = Huffman(codeLengths)

            val lengths = IntArray(literalCount + distanceCount)
            var i = 0
            while (i < lengths.size) {
                when (val symbol = decode(codeLengthCode)) {
                    // 16, 17 and 18 are run-length escapes, and 16 repeats the PREVIOUS length, so it cannot
                    // be the first symbol. The literal and distance lengths are one run here, not two, which
                    // is why a run is allowed to straddle the boundary between them.
                    16 -> {
                        val previous = lengths[i - 1]
                        repeat(3 + bits(2)) { lengths[i++] = previous }
                    }

                    17 -> repeat(3 + bits(3)) { lengths[i++] = 0 }
                    18 -> repeat(11 + bits(7)) { lengths[i++] = 0 }
                    else -> lengths[i++] = symbol
                }
            }

            block(
                Huffman(lengths.copyOfRange(0, literalCount)),
                Huffman(lengths.copyOfRange(literalCount, lengths.size)),
            )
        }

        private fun block(literals: Huffman, distances: Huffman) {
            while (true) {
                val symbol = decode(literals)
                when {
                    symbol < 256 -> output[written++] = symbol.toByte()
                    symbol == 256 -> return
                    else -> {
                        val lengthCode = symbol - 257
                        val length = LENGTH_BASE[lengthCode] + bits(LENGTH_EXTRA[lengthCode])
                        val distanceCode = decode(distances)
                        val distance = DISTANCE_BASE[distanceCode] + bits(DISTANCE_EXTRA[distanceCode])
                        // Copied byte by byte on purpose: a back-reference may overlap what it is writing
                        // (distance 1, length 100 is a run of one byte), so a block move would read bytes
                        // that this very copy is supposed to produce.
                        var from = written - distance
                        check(from >= 0) { "back-reference before the start of the output" }
                        repeat(length) { output[written++] = output[from++] }
                    }
                }
            }
        }

        /**
         * One symbol, walked bit by bit.
         *
         * At each length the accumulated code is compared against how many codes of that length exist. A
         * canonical code assigns the shortest codes the smallest values, so "is this code within the range
         * for this length" is the whole decision, and no table is needed to make it.
         */
        private fun decode(huffman: Huffman): Int {
            var code = 0
            var first = 0
            var index = 0
            for (length in 1..Huffman.MAX_BITS) {
                code = code or bits(1)
                val count = huffman.counts[length]
                if (code - first < count) return huffman.symbols[index + (code - first)]
                index += count
                first = (first + count) shl 1
                code = code shl 1
            }
            error("no code of any length matched")
        }
    }
}
