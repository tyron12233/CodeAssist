package dev.ide.kotlin.classfile

/**
 * Undoes the encoding `@kotlin.Metadata` uses to smuggle protobuf through a Java annotation.
 *
 * An annotation cannot hold a byte array usefully, so the compiler packs the bytes into `String[]`. There are
 * two schemes and a file can use either, distinguished by a marker character on the first string:
 *
 *  * **UTF-8 mode**, marked by a leading NUL, is what current compilers emit. Each char's low byte IS the
 *    byte. Trivial, once you know to drop the marker.
 *  * **8-to-7 mode**, marked by `(char) -1`, is the older scheme: bytes are re-packed seven bits at a time so
 *    every char stays inside the range that survives the class file's modified UTF-8. Still met in older
 *    libraries on a real classpath, so it is not optional.
 *
 * A third case has no marker at all and is also 8-to-7, the oldest form. Which is why the marker check
 * cannot be an `else`.
 */
object MetadataEncoding {

    private const val UTF8_MODE_MARKER = '\u0000'
    private const val EIGHT_TO_SEVEN_MODE_MARKER = '\uFFFF' // `(char) -1`

    /** The protobuf bytes carried by `@Metadata`'s `d1`. */
    fun decodeBytes(data: Array<String>): ByteArray {
        if (data.isNotEmpty() && data[0].isNotEmpty()) {
            when (data[0][0]) {
                UTF8_MODE_MARKER -> return concatenateLowBytes(dropMarker(data))
                EIGHT_TO_SEVEN_MODE_MARKER -> return decode7to8(
                    addModuloByte(concatenateLowBytes(dropMarker(data)), 0x7f),
                )
            }
        }
        // No marker: the oldest form, which is 8-to-7 too.
        return decode7to8(addModuloByte(concatenateLowBytes(data), 0x7f))
    }

    private fun dropMarker(data: Array<String>): Array<String> =
        Array(data.size) { if (it == 0) data[0].substring(1) else data[it] }

    /** Every char's low byte, concatenated. This IS the decoding in UTF-8 mode. */
    private fun concatenateLowBytes(data: Array<String>): ByteArray {
        val result = ByteArray(data.sumOf { it.length })
        var at = 0
        for (s in data) for (i in s.indices) result[at++] = s[i].code.toByte()
        return result
    }

    /**
     * Adding 0x7f modulo the byte range is subtracting one, which inverts what the encoder did.
     *
     * Stated the way the compiler's own comment states it, because the arithmetic reads as arbitrary
     * otherwise and the equivalence is the only reason it is correct.
     */
    private fun addModuloByte(data: ByteArray, increment: Int): ByteArray {
        for (i in data.indices) data[i] = ((data[i].toInt() + increment) and 0x7f).toByte()
        return data
    }

    /**
     * Re-joins seven-bit groups into bytes.
     *
     * Each input byte carries seven meaningful bits. Concatenate all of them into one bit string and cut it
     * into eights; the leftovers at the end are padding and are dropped, which is why the result is
     * `7 * length / 8` and not a rounded-up count.
     */
    private fun decode7to8(data: ByteArray): ByteArray {
        val resultLength = 7 * data.size / 8
        val result = ByteArray(resultLength)
        var byteIndex = 0
        var bit = 0
        for (i in 0 until resultLength) {
            val firstPart = (data[byteIndex].toInt() and 0xff) ushr bit
            byteIndex++
            val secondPart = (data[byteIndex].toInt() and ((1 shl (bit + 1)) - 1)) shl (7 - bit)
            result[i] = (firstPart + secondPart).toByte()
            if (bit == 6) {
                byteIndex++
                bit = 0
            } else {
                bit++
            }
        }
        return result
    }
}
