package dev.ide.kotlin.classfile

/**
 * The wire format `java.io.DataOutput` writes, without `java.io`.
 *
 * Every persisted index in this build is a run of `writeInt` / `writeBoolean` / `writeByte` / `writeUTF`
 * against a `DataOutputStream`, so the format is not ours to choose: an index written on the desktop has to
 * be readable on the phone and the other way round. This produces the same bytes, which is a stronger
 * requirement than producing a format that merely round-trips with itself, and the only way to know it holds
 * is to diff against the real thing.
 *
 * `writeUTF` is where the difficulty is. It is modified UTF-8, not UTF-8: a NUL is written as two bytes so
 * that no encoded byte is ever zero, and a character outside the basic plane is written as its two
 * surrogates at three bytes each rather than as one four-byte sequence. The length prefix counts BYTES, not
 * characters, and it is sixteen bits, which is why an over-long string is an error rather than a longer
 * prefix.
 */
class DataWriter {

    private var bytes = ByteArray(256)
    private var at = 0

    val size: Int get() = at

    fun toByteArray(): ByteArray = bytes.copyOf(at)

    fun writeByte(value: Int) {
        ensure(1)
        bytes[at++] = value.toByte()
    }

    fun writeBoolean(value: Boolean) {
        writeByte(if (value) 1 else 0)
    }

    fun writeShort(value: Int) {
        ensure(2)
        bytes[at++] = (value ushr 8).toByte()
        bytes[at++] = value.toByte()
    }

    fun writeInt(value: Int) {
        ensure(4)
        bytes[at++] = (value ushr 24).toByte()
        bytes[at++] = (value ushr 16).toByte()
        bytes[at++] = (value ushr 8).toByte()
        bytes[at++] = value.toByte()
    }

    fun writeLong(value: Long) {
        writeInt((value ushr 32).toInt())
        writeInt(value.toInt())
    }

    fun writeBytes(value: ByteArray) {
        ensure(value.size)
        value.copyInto(bytes, at)
        at += value.size
    }

    /** A string in modified UTF-8, length-prefixed with the BYTE count in two bytes. */
    fun writeUTF(value: String) {
        var length = 0
        for (char in value) {
            val code = char.code
            length += when {
                code in 1..0x7F -> 1
                code <= 0x7FF -> 2
                else -> 3
            }
        }
        check(length <= 0xFFFF) { "a string of $length encoded bytes does not fit a 16-bit length" }

        ensure(2 + length)
        writeShort(length)
        for (char in value) {
            val code = char.code
            when {
                code in 1..0x7F -> bytes[at++] = code.toByte()
                // Zero is deliberately in the two-byte branch. That is the whole point of the "modified" in
                // modified UTF-8: it keeps a NUL byte out of the encoded form so the result can sit in a
                // NUL-terminated string.
                code <= 0x7FF -> {
                    bytes[at++] = (0xC0 or (code shr 6)).toByte()
                    bytes[at++] = (0x80 or (code and 0x3F)).toByte()
                }

                else -> {
                    bytes[at++] = (0xE0 or (code shr 12)).toByte()
                    bytes[at++] = (0x80 or ((code shr 6) and 0x3F)).toByte()
                    bytes[at++] = (0x80 or (code and 0x3F)).toByte()
                }
            }
        }
    }

    private fun ensure(count: Int) {
        if (at + count <= bytes.size) return
        var capacity = bytes.size
        while (capacity < at + count) capacity *= 2
        bytes = bytes.copyOf(capacity)
    }
}

/**
 * Reads back what [DataWriter] and `java.io.DataOutputStream` write.
 *
 * Big-endian, like everything the JVM serialises and unlike the zip container two files over. The two
 * readers are kept apart rather than given a byte-order flag, so neither can be pointed at the wrong format.
 */
class DataReader(private val bytes: ByteArray, private var at: Int = 0) {

    val hasMore: Boolean get() = at < bytes.size
    val position: Int get() = at

    fun readByte(): Int = bytes[at++].toInt()

    fun readUnsignedByte(): Int = bytes[at++].toInt() and 0xFF

    fun readBoolean(): Boolean = readUnsignedByte() != 0

    fun readShort(): Int = (readUnsignedByte() shl 8) or readUnsignedByte()

    fun readInt(): Int = (readShort() shl 16) or readShort()

    fun readLong(): Long = (readInt().toLong() shl 32) or (readInt().toLong() and 0xFFFFFFFFL)

    fun readBytes(count: Int): ByteArray {
        val slice = bytes.copyOfRange(at, at + count)
        at += count
        return slice
    }

    /**
     * A modified-UTF-8 string.
     *
     * The three-byte form yields a single UTF-16 unit, which is how a character outside the basic plane
     * comes back: as the two surrogates it was written as, which Kotlin's string already stores that way.
     * So nothing here recombines them, and nothing should.
     */
    fun readUTF(): String {
        val length = readShort()
        val end = at + length
        val out = StringBuilder(length)
        while (at < end) {
            val first = readUnsignedByte()
            when {
                first < 0x80 -> out.append(first.toChar())
                first and 0xE0 == 0xC0 -> {
                    val second = readUnsignedByte() and 0x3F
                    out.append((((first and 0x1F) shl 6) or second).toChar())
                }

                else -> {
                    val second = readUnsignedByte() and 0x3F
                    val third = readUnsignedByte() and 0x3F
                    out.append((((first and 0x0F) shl 12) or (second shl 6) or third).toChar())
                }
            }
        }
        return out.toString()
    }
}
