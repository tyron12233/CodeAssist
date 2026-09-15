package dev.ide.store.impl.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.zlib.Z_FINISH
import platform.zlib.Z_NO_FLUSH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.inflate
import platform.zlib.inflateEnd
import platform.zlib.inflateInit2
import platform.zlib.uByteVar
import platform.zlib.z_stream

/**
 * A ZIP reader, because iOS has no `java.util.zip` and installing a store project means unpacking one.
 *
 * It reads the **central directory** rather than scanning for local headers, which is what the format
 * says is authoritative: a local header may carry zeroed sizes with the real ones in a trailing data
 * descriptor, so a reader that trusted local headers would mis-measure exactly the archives a streaming
 * writer produces. Only the two methods any real archive uses are supported — stored (0) and deflate (8);
 * anything else is refused by name rather than silently producing wrong bytes.
 *
 * The decompressor is the system zlib (`platform.zlib`), which iOS ships and Kotlin/Native already binds.
 *
 * The whole file is read into memory once. A store payload is capped at 5 MB by the bucket it came from,
 * and the extractor's own ceilings cap what comes out of it, so this trades a bounded allocation for not
 * having to write a seeking reader.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual class ZipArchive(private val bytes: ByteArray, private val records: List<Record>) {

    /** One central-directory record, as far as extraction cares. */
    internal class Record(
        val name: String,
        val isDirectory: Boolean,
        val compressedSize: Int,
        val uncompressedSize: Int,
        val method: Int,
        val localHeaderOffset: Int,
    )

    actual fun entries(): List<ZipEntryInfo> = records.mapIndexed { i, r ->
        ZipEntryInfo(r.name, r.isDirectory, r.uncompressedSize.toLong(), i)
    }

    actual fun extractTo(entry: ZipEntryInfo, destPath: String): Long {
        val record = records.getOrNull(entry.index) ?: return -1
        val data = bytesOf(record) ?: return -1
        return if (StoreFs.writeBytes(destPath, data)) data.size.toLong() else -1
    }

    actual fun close() = Unit

    private fun bytesOf(record: Record): ByteArray? {
        // The local header repeats the name and extra fields, and its extra-field length may differ from
        // the central one's; the data starts after whatever this copy declares.
        val header = record.localHeaderOffset
        if (header < 0 || header + LOCAL_HEADER_SIZE > bytes.size) return null
        if (readInt(bytes, header) != LOCAL_SIGNATURE) return null
        val nameLength = readShort(bytes, header + 26)
        val extraLength = readShort(bytes, header + 28)
        val start = header + LOCAL_HEADER_SIZE + nameLength + extraLength
        val end = start + record.compressedSize
        if (start < 0 || end > bytes.size || end < start) return null
        val raw = bytes.copyOfRange(start, end)
        return when (record.method) {
            METHOD_STORED -> raw
            METHOD_DEFLATED -> inflateRaw(raw, record.uncompressedSize)
            else -> null
        }
    }

    internal companion object {
        private const val LOCAL_SIGNATURE = 0x04034b50
        private const val CENTRAL_SIGNATURE = 0x02014b50
        private const val END_SIGNATURE = 0x06054b50
        private const val LOCAL_HEADER_SIZE = 30
        private const val CENTRAL_HEADER_SIZE = 46
        private const val METHOD_STORED = 0
        private const val METHOD_DEFLATED = 8

        fun parse(bytes: ByteArray): ZipArchive? {
            val end = findEndOfCentralDirectory(bytes) ?: return null
            val count = readShort(bytes, end + 10)
            var offset = readInt(bytes, end + 16)
            val records = ArrayList<Record>(count)
            repeat(count) {
                if (offset + CENTRAL_HEADER_SIZE > bytes.size) return null
                if (readInt(bytes, offset) != CENTRAL_SIGNATURE) return null
                val method = readShort(bytes, offset + 10)
                val compressed = readInt(bytes, offset + 20)
                val uncompressed = readInt(bytes, offset + 24)
                val nameLength = readShort(bytes, offset + 28)
                val extraLength = readShort(bytes, offset + 30)
                val commentLength = readShort(bytes, offset + 32)
                val localOffset = readInt(bytes, offset + 42)
                val nameStart = offset + CENTRAL_HEADER_SIZE
                if (nameStart + nameLength > bytes.size) return null
                val name = bytes.decodeToString(nameStart, nameStart + nameLength)
                records.add(
                    Record(
                        name = name,
                        // The format has no directory flag a reader can rely on beyond this: a trailing
                        // separator is what every writer uses to mean one.
                        isDirectory = name.endsWith("/") || name.endsWith("\\"),
                        compressedSize = compressed,
                        uncompressedSize = uncompressed,
                        method = method,
                        localHeaderOffset = localOffset,
                    ),
                )
                offset = nameStart + nameLength + extraLength + commentLength
            }
            return ZipArchive(bytes, records)
        }

        /**
         * The end-of-central-directory record, searched for backwards.
         *
         * It is last in the file but its position is not fixed, because it carries a comment of up to
         * 64 KB after it. Scanning back from the end is what the format requires.
         */
        private fun findEndOfCentralDirectory(bytes: ByteArray): Int? {
            val minimum = 22
            if (bytes.size < minimum) return null
            val floor = maxOf(0, bytes.size - minimum - 0xFFFF)
            for (i in bytes.size - minimum downTo floor) {
                if (readInt(bytes, i) == END_SIGNATURE) return i
            }
            return null
        }

        /** Little-endian, as every field in a zip is. */
        private fun readInt(bytes: ByteArray, at: Int): Int {
            if (at < 0 || at + 4 > bytes.size) return -1
            return (bytes[at].toInt() and 0xFF) or
                ((bytes[at + 1].toInt() and 0xFF) shl 8) or
                ((bytes[at + 2].toInt() and 0xFF) shl 16) or
                ((bytes[at + 3].toInt() and 0xFF) shl 24)
        }

        private fun readShort(bytes: ByteArray, at: Int): Int {
            if (at < 0 || at + 2 > bytes.size) return -1
            return (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)
        }

        /**
         * Raw DEFLATE (no zlib or gzip wrapper), which is what a zip entry holds — hence the negative
         * window bits, zlib's way of being told the stream has no header of its own.
         *
         * [expectedSize] comes from the central directory and sizes the output buffer; the loop still
         * grows past it rather than trusting it, because that number is written by whoever made the
         * archive.
         */
        private fun inflateRaw(data: ByteArray, expectedSize: Int): ByteArray? = memScoped {
            if (data.isEmpty()) return if (expectedSize == 0) ByteArray(0) else null
            val stream = alloc<z_stream>()
            if (inflateInit2(stream.ptr, -15) != Z_OK) return null
            try {
                var out = ByteArray(maxOf(expectedSize, 1024))
                var produced = 0
                data.usePinned { input ->
                    stream.next_in = input.addressOf(0).reinterpret()
                    stream.avail_in = data.size.toUInt()
                    while (true) {
                        if (produced == out.size) out = out.copyOf(out.size * 2)
                        val status = out.usePinned { sink ->
                            stream.next_out = sink.addressOf(produced).reinterpret()
                            stream.avail_out = (out.size - produced).toUInt()
                            val code = inflate(stream.ptr, Z_NO_FLUSH)
                            produced = out.size - stream.avail_out.toInt()
                            code
                        }
                        if (status == Z_STREAM_END) return@usePinned
                        if (status != Z_OK) return null
                    }
                }
                out.copyOf(produced)
            } finally {
                inflateEnd(stream.ptr)
            }
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun openZip(path: String): ZipArchive? =
    StoreFs.readBytes(path)?.let { ZipArchive.parse(it) }
