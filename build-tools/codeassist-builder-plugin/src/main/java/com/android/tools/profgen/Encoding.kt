package com.android.tools.profgen

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.UTFDataFormatException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.Inflater

/** Base-128 bit mask.  */
private const val MASK: Int = 0x7f

/**
 * Decodes an unsigned LEB128 to an Int
 *
 * LEB128 ("Little-Endian Base 128") is a variable-length encoding for arbitrary signed or unsigned
 * integer quantities. The format was borrowed from the DWARF3 specification. In a .dex file, LEB128
 * is only ever used to encode 32-bit quantities.
 *
 *
 * Each LEB128 encoded value consists of one to five bytes, which together represent a single
 * 32-bit value. Each byte has its most significant bit set except for the final byte in the
 * sequence, which has its most significant bit clear. The remaining seven bits of each byte are
 * payload, with the least significant seven bits of the quantity in the first byte, the next seven
 * in the second byte and so on. In the case of a signed LEB128 (sleb128), the most significant
 * payload bit of the final byte in the sequence is sign-extended to produce the final value. In the
 * unsigned case (uleb128), any bits not explicitly represented are interpreted as 0.
 */
internal val ByteBuffer.leb128: Int
    get() {
        var value = 0
        var b: Int
        var idx = 0
        do {
            b = ubyte
            value = value or (b and MASK shl idx++ * 7)
        } while (b and MASK.inv() != 0)
        return value
    }

/**
 * Modified UTF-8 as described in the dex file format spec.
 *
 *
 * Derived from libcore's MUTF-8 encoder at java.nio.charset.ModifiedUtf8.
 *
 * Decodes bytes from the ByteBuffer until a delimiter 0x00 is
 * encountered. Returns a new string containing the decoded characters.
 */
internal fun ByteBuffer.mutf8(encodedSize: Int): String {
    val out = CharArray(encodedSize)
    var s = 0
    while (true) {
        val a = get().toInt().toChar()
        if (a.code == 0) {
            return String(out, 0, s)
        }
        out[s] = a
        if (a < '\u0080') {
            s++
        } else if (a.code and 0xe0 == 0xc0) {
            val b = ubyte
            if (b and 0xC0 != 0x80) {
                throw UTFDataFormatException("bad second byte")
            }
            out[s++] = (a.code and 0x1F shl 6 or (b and 0x3F)).toChar()
        } else if (a.code and 0xf0 == 0xe0) {
            val b = ubyte
            val c = ubyte
            if (b and 0xC0 != 0x80 || c and 0xC0 != 0x80) {
                throw UTFDataFormatException("bad second or third byte")
            }
            out[s++] = (a.code and 0x0F shl 12 or (b and 0x3F shl 6) or (c and 0x3F)).toChar()
        } else {
            throw UTFDataFormatException("bad byte")
        }
    }
}

internal fun Long.toIntSaturated(): Int = when {
    this > Int.MAX_VALUE -> Int.MAX_VALUE
    this < Int.MIN_VALUE -> Int.MIN_VALUE
    else -> toInt()
}

internal val ByteBuffer.ushort: Int
    get() = short.toUShort().toInt()

internal val ByteBuffer.ubyte: Int
    get() = get().toUByte().toInt()

internal enum class Endian(
    val number: Int,
    val order: ByteOrder
) {
    LITTLE(0x12345678, ByteOrder.LITTLE_ENDIAN),
    BIG(0x78563412, ByteOrder.BIG_ENDIAN);
    companion object {
        fun forNumber(value: Int) = when (value) {
            LITTLE.number -> LITTLE
            BIG.number -> BIG
            else -> error("No Endian for number $value")
        }
    }
}

internal const val UINT_8_SIZE = 1
internal const val UINT_16_SIZE = 2
internal const val UINT_32_SIZE = 4

internal fun byteArrayOf(vararg chars: Char) = ByteArray(chars.size) { chars[it].code.toByte() }

internal fun OutputStream.writeUInt(value: Long, numberOfBytes: Int) {
    val buffer = ByteArray(numberOfBytes)
    for (i in 0 until numberOfBytes) {
        buffer[i] = (value shr i * java.lang.Byte.SIZE and 0xff).toByte()
    }
    write(buffer)
}

/**
 * Writes the value as an 8 bit unsigned integer (uint8_t in c++).
 */
internal fun OutputStream.writeUInt8(value: Int) = writeUInt(value.toLong(), UINT_8_SIZE)

/**
 * Writes the value as a 16 bit unsigned integer (uint16_t in c++).
 */
internal fun OutputStream.writeUInt16(value: Int) = writeUInt(value.toLong(), UINT_16_SIZE)

/**
 * Writes the value as a 32 bit unsigned integer (uint32_t in c++).
 */
internal fun OutputStream.writeUInt32(value: Long) = writeUInt(value, UINT_32_SIZE)

/**
 * Writes a string in the stream using UTF-8 encoding.
 *
 * @param s the string to be written
 * @throws IOException in case of IO errors
 */
internal fun OutputStream.writeString(s: String) {
    write(s.toByteArray(StandardCharsets.UTF_8))
}

/**
 * Compresses data the using [DeflaterOutputStream] before writing it to the stream.
 * The method will write the size of the compressed data (32 bits, [.writeUInt32]) before
 * the actual compressed bytes.
 *
 * @param data the data to be compressed and written.
 * @throws IOException in case of IO errors
 */
internal fun OutputStream.writeCompressed(data: ByteArray) {
    val compressed = data.compressed()
    writeUInt32(compressed.size.toLong())
    // TODO(calin): we can get rid of the multiple byte array copy using a custom stream.
    write(compressed)
}

/**
 * Compresses data using a [DeflaterOutputStream].
 */
internal fun ByteArray.compressed(): ByteArray {
    val compressor = Deflater(Deflater.BEST_SPEED)
    val out = ByteArrayOutputStream()
    val deflater = DeflaterOutputStream(out, compressor)
    deflater.use {
        deflater.write(this)
    }
    return out.toByteArray()
}

/**
 * Attempts to read {@param length} bytes from the input stream.
 * If not enough bytes are available it throws [IllegalStateException].
 */
internal fun InputStream.read(length: Int): ByteArray {
    val buffer = ByteArray(length)
    var offset = 0
    while (offset < length) {
        val result = read(buffer, offset, length - offset)
        if (result < 0) {
            error("Not enough bytes to read: $length")
        }
        offset += result
    }
    return buffer
}

/**
 * Reads the equivalent of an 8 bit unsigned integer (uint8_t in c++).
 */
internal fun InputStream.readUInt8(): Int = readUInt(UINT_8_SIZE).toInt()

/**
 * Reads the equivalent of an 16 bit unsigned integer (uint16_t in c++).
 */
internal fun InputStream.readUInt16(): Int = readUInt(UINT_16_SIZE).toInt()

/**
 * Reads the equivalent of an 32 bit unsigned integer (uint32_t in c++).
 */
internal fun InputStream.readUInt32(): Long = readUInt(UINT_32_SIZE)

/**
 * Reads an unsigned integer from the stream
 *
 * @param numberOfBytes the size of the integer in bytes
 */
internal fun InputStream.readUInt(numberOfBytes: Int): Long {
    val buffer = read(numberOfBytes)
    // We use a long to cover for unsigned integer.
    var value: Long = 0
    for (k in 0 until numberOfBytes) {
        val next = buffer[k].toUByte().toLong()
        value += next shl k * java.lang.Byte.SIZE
    }
    return value
}

/**
 * Reads bytes from the stream and coverts them to a string using UTF-8.
 *
 * @param size the number of bytes to read
 */
internal fun InputStream.readString(size: Int): String = String(read(size), StandardCharsets.UTF_8)

/**
 * Reads a compressed data region from the stream.
 *
 * @param compressedDataSize the size of the compressed data (bytes)
 * @param uncompressedDataSize the expected size of the uncompressed data (bytes)
 */
internal fun InputStream.readCompressed(compressedDataSize: Int, uncompressedDataSize: Int): ByteArray {
    // Read the expected compressed data size.
    val inf = Inflater()
    val result = ByteArray(uncompressedDataSize)
    var totalBytesRead = 0
    var totalBytesInflated = 0
    val input = ByteArray(2048) // 2KB read window size;
    while (!inf.finished() && !inf.needsDictionary() && totalBytesRead < compressedDataSize) {
        val bytesRead = read(input)
        if (bytesRead < 0) {
            error("Invalid zip data. Stream ended after $totalBytesRead bytes. Expected $compressedDataSize bytes")
        }
        inf.setInput(input, 0, bytesRead)
        totalBytesInflated += inf.inflate(result, totalBytesInflated, uncompressedDataSize - totalBytesInflated)
        totalBytesRead += bytesRead
    }
    if (totalBytesRead != compressedDataSize) {
        error("Didn't read enough bytes during decompression. expected=$compressedDataSize actual=$totalBytesRead")
    }
    if (!inf.finished()) {
        error("Inflater did not finish")
    }
    return result
}

/**
 * Computes the length of the string's UTF-8 encoding.
 */
internal val String.utf8Length: Int get() = toByteArray(StandardCharsets.UTF_8).size
