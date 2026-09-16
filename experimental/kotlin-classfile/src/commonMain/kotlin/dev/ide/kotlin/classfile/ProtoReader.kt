package dev.ide.kotlin.classfile

/**
 * A protobuf wire-format reader.
 *
 * Kotlin's metadata is protobuf, and the generated reader for it is 34,545 lines of Java that only runs on
 * the JVM. Porting that is not the job. The wire format is self-describing enough to walk without a schema —
 * every field is a tag carrying a number and one of four encodings — so what is needed is this, plus a
 * handful of field numbers read off `metadata.proto`, which is 709 lines and the actual source of truth.
 *
 * Deliberately small and non-validating: it decodes what it is asked for and SKIPS whatever it is not, which
 * is also what makes it forward-compatible. A future Kotlin adding fields is a no-op here rather than a
 * parse failure, and that is the property that matters for a decoder pinned to someone else's schema.
 */
class ProtoReader(private val bytes: ByteArray, private var position: Int = 0, private val limit: Int = bytes.size) {

    /** Wire types, from the protobuf spec. Only these four appear in Kotlin metadata. */
    private companion object {
        const val VARINT = 0
        const val FIXED64 = 1
        const val LENGTH_DELIMITED = 2
        const val FIXED32 = 5
    }

    val hasMore: Boolean get() = position < limit

    /**
     * Read the next field's tag. The low three bits are the wire type; the rest is the field number.
     *
     * Returns the pair because a caller needs both: the number to decide whether it wants the field, and the
     * type to skip it correctly when it does not.
     */
    fun readTag(): Pair<Int, Int> {
        val tag = readVarint().toInt()
        return (tag ushr 3) to (tag and 0x7)
    }

    fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (shift < 64) {
            val byte = bytes[position++].toInt()
            result = result or ((byte.toLong() and 0x7F) shl shift)
            if (byte and 0x80 == 0) return result
            shift += 7
        }
        error("varint longer than 64 bits at ${position - 1}")
    }

    fun readInt(): Int = readVarint().toInt()

    /** A length-delimited field's bytes: a nested message, a string, or packed repeated scalars. */
    fun readBytes(): ByteArray {
        val length = readInt()
        val slice = bytes.copyOfRange(position, position + length)
        position += length
        return slice
    }

    /** A reader over the next length-delimited field, for descending into a nested message. */
    fun readMessage(): ProtoReader {
        val length = readInt()
        val nested = ProtoReader(bytes, position, position + length)
        position += length
        return nested
    }

    fun readString(): String = readBytes().decodeToString()

    /**
     * A packed repeated int field: one length-delimited run of varints.
     *
     * Only the packed encoding is read here. A caller that may also meet the unpacked form has to check the
     * wire type itself, because the two are indistinguishable from the field number alone.
     */
    fun readPackedInts(): IntArray {
        val nested = ProtoReader(readBytes())
        val values = ArrayList<Int>()
        while (nested.hasMore) values.add(nested.readInt())
        return values.toIntArray()
    }

    /** Step over a field this decoder does not care about. */
    fun skip(wireType: Int) {
        when (wireType) {
            VARINT -> readVarint()
            FIXED64 -> position += 8
            LENGTH_DELIMITED -> {
                // NOT `position += readInt()`. Kotlin reads the left operand of `+=` BEFORE evaluating the
                // right, so the position captured is the one from before the length varint was consumed, and
                // the assignment then throws that advance away. It lands one byte short, which is not an
                // error — it resumes mid-field and reads plausible nonsense from then on.
                val length = readInt()
                position += length
            }

            FIXED32 -> position += 4
            else -> error("unknown wire type $wireType at $position")
        }
    }

    /**
     * Walk every field, handing each to [onField], which returns true when it consumed the field and false
     * to have it skipped.
     *
     * The shape exists so a message decoder reads as a list of the fields it wants rather than as a loop with
     * a `when` and an easily forgotten `else`. Forgetting to skip is the one way to corrupt a protobuf read,
     * and it corrupts everything after it rather than failing where the mistake is.
     */
    inline fun forEachField(onField: (number: Int, wireType: Int) -> Boolean) {
        while (hasMore) {
            val (number, wireType) = readTag()
            if (!onField(number, wireType)) skip(wireType)
        }
    }
}
