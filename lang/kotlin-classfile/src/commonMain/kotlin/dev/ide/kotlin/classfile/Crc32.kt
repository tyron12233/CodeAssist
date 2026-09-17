package dev.ide.kotlin.classfile

/**
 * CRC-32 as zip files record it.
 *
 * Present so that a decompressed entry can be checked against the number the archive itself stored. That
 * matters more here than it would on the JVM: this decompressor is ours, so the checksum is the one
 * independent statement in the file about whether the bytes came out right.
 */
object Crc32 {

    private val TABLE = IntArray(256) {
        var value = it
        repeat(8) { value = if (value and 1 != 0) (value ushr 1) xor 0xEDB88320.toInt() else value ushr 1 }
        value
    }

    fun of(bytes: ByteArray): Long {
        var crc = -1
        for (byte in bytes) crc = (crc ushr 8) xor TABLE[(crc xor byte.toInt()) and 0xFF]
        return (crc.inv()).toLong() and 0xFFFFFFFFL
    }
}
