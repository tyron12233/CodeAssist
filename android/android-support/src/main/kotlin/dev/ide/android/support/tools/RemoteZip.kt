package dev.ide.android.support.tools

import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.CRC32
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * Extracts chosen entries of a zip that lives on a server, without downloading the rest of it: the central
 * directory is read from the end of the file with HTTP Range requests, then each chosen entry's bytes are
 * fetched and inflated on their own. An Android platform zip is ~65 MB, of which the IDE needs `android.jar`
 * plus a few small files (~26 MB).
 *
 * Every entry is CRC-checked. An entry already extracted by an earlier, interrupted run (same size and CRC)
 * is kept, so a retry only fetches what is missing. Plain zips only: no zip64, no encryption, stored or
 * deflated entries.
 */
object RemoteZip {

    /** One entry of the central directory. Its bytes (local header, data, any descriptor) span
     *  [localHeaderOffset] up to [spanEnd], where the next entry or the central directory begins. */
    data class Entry(
        val name: String,
        val method: Int,
        val crc: Long,
        val compressedSize: Long,
        val size: Long,
        val localHeaderOffset: Long,
        val spanEnd: Long = Long.MAX_VALUE,
    )

    /**
     * Lists the entries of the [totalSize]-byte zip at [url]. Null when the server ignores Range requests or
     * the bytes are not a zip [RemoteZip] can read, so the caller falls back to downloading the whole file.
     */
    fun entries(url: String, totalSize: Long, fetcher: SdkNetFetcher): List<Entry>? {
        if (totalSize < EOCD_SIZE) return null
        val tailStart = (totalSize - EOCD_SIZE - MAX_COMMENT).coerceAtLeast(0)
        val tail = fetcher.readRange(url, tailStart, totalSize - 1) ?: return null
        var eocd = -1
        for (i in tail.size - EOCD_SIZE downTo 0) {
            if (le32(tail, i) == EOCD_SIG) { eocd = i; break }
        }
        if (eocd < 0) return null
        val count = le16(tail, eocd + 10)
        val cdSize = le32(tail, eocd + 12)
        val cdOffset = le32(tail, eocd + 16)
        if (cdOffset == 0xFFFFFFFFL || cdOffset + cdSize > totalSize) return null // zip64 or a wrong size
        val cd = fetcher.readRange(url, cdOffset, cdOffset + cdSize - 1) ?: return null
        val out = ArrayList<Entry>(count)
        var p = 0
        while (p + 46 <= cd.size && le32(cd, p) == CEN_SIG) {
            val nameLen = le16(cd, p + 28)
            val extraLen = le16(cd, p + 30)
            val commentLen = le16(cd, p + 32)
            out += Entry(
                name = String(cd, p + 46, nameLen, Charsets.UTF_8),
                method = le16(cd, p + 10),
                crc = le32(cd, p + 16),
                compressedSize = le32(cd, p + 20),
                size = le32(cd, p + 24),
                localHeaderOffset = le32(cd, p + 42),
            )
            p += 46 + nameLen + extraLen + commentLen
        }
        if (out.size != count) return null
        // Each entry ends where the next one (by offset) starts, so a fetch asks for exactly its bytes.
        val starts = (out.map { it.localHeaderOffset } + cdOffset).sorted()
        return out.map { e ->
            val i = starts.binarySearch(e.localHeaderOffset)
            e.copy(spanEnd = starts.drop(i + 1).firstOrNull { it > e.localHeaderOffset } ?: cdOffset)
        }
    }

    /**
     * Extracts each of [entries] into [destDir] under its own name. [onProgress] gets (bytes fetched, total
     * compressed bytes of [entries]). Throws on a network failure, a CRC mismatch, or an entry path that
     * escapes [destDir].
     */
    fun extract(
        url: String,
        entries: List<Entry>,
        destDir: Path,
        fetcher: SdkNetFetcher,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ) {
        val total = entries.sumOf { it.compressedSize }
        var done = 0L
        Files.createDirectories(destDir)
        for (e in entries) {
            val target = destDir.resolve(e.name).normalize()
            if (!target.startsWith(destDir.normalize())) throw IOException("Zip entry escapes target: ${e.name}")
            if (e.name.endsWith("/")) { Files.createDirectories(target); continue }
            if (Files.isRegularFile(target) && Files.size(target) == e.size && crcOf(target) == e.crc) {
                done += e.compressedSize
                onProgress(done, total)
                continue
            }
            Files.createDirectories(target.parent)
            val part = target.resolveSibling(target.fileName.toString() + ".part")
            val base = done
            // The local header's extra field can differ from the central one, so fetch the entry's whole span.
            val end = minOf(e.spanEnd, e.localHeaderOffset + LOC_SIZE + MAX_NAME + MAX_EXTRA + e.compressedSize) - 1
            val stream = fetcher.openRange(url, e.localHeaderOffset, end)
                ?: throw IOException("The server stopped answering range requests.")
            stream.use { raw ->
                val input = DataInputStream(raw)
                val header = ByteArray(LOC_SIZE)
                input.readFully(header)
                if (le32(header, 0) != LOC_SIG) throw IOException("Bad local header for ${e.name}")
                input.skipFully(le16(header, 26).toLong() + le16(header, 28))
                val body = CountingStream(BoundedStream(input, e.compressedSize)) { n -> onProgress(base + n, total) }
                val data: InputStream = when (e.method) {
                    STORED -> body
                    DEFLATED -> InflaterInputStream(body, Inflater(true), 64 * 1024)
                    else -> throw IOException("Unsupported compression method ${e.method} for ${e.name}")
                }
                val crc = CRC32()
                var written = 0L
                Files.newOutputStream(part).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = data.read(buf)
                        if (n < 0) break
                        crc.update(buf, 0, n)
                        out.write(buf, 0, n)
                        written += n
                    }
                }
                if (written != e.size || crc.value != e.crc) {
                    Files.deleteIfExists(part)
                    throw IOException("Checksum mismatch for ${e.name}")
                }
            }
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING)
            done += e.compressedSize
            onProgress(done, total)
        }
    }

    private fun crcOf(file: Path): Long {
        val crc = CRC32()
        Files.newInputStream(file).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                crc.update(buf, 0, n)
            }
        }
        return crc.value
    }

    private fun DataInputStream.skipFully(n: Long) {
        var left = n
        while (left > 0) {
            val s = skip(left)
            if (s <= 0) { if (read() < 0) throw IOException("Unexpected end of zip data"); left-- } else left -= s
        }
    }

    /** Reads at most [limit] bytes of [input], then reports end of stream. */
    private class BoundedStream(private val input: InputStream, private var limit: Long) : InputStream() {
        override fun read(): Int = if (limit <= 0) -1 else input.read().also { if (it >= 0) limit-- }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (limit <= 0) return -1
            val n = input.read(b, off, minOf(len.toLong(), limit).toInt())
            if (n > 0) limit -= n
            return n
        }
    }

    private class CountingStream(private val input: InputStream, private val onRead: (Long) -> Unit) : InputStream() {
        private var count = 0L
        override fun read(): Int = input.read().also { if (it >= 0) onRead(++count) }
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            input.read(b, off, len).also { if (it > 0) { count += it; onRead(count) } }
    }

    private fun le16(b: ByteArray, at: Int): Int = ByteBuffer.wrap(b, at, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
    private fun le32(b: ByteArray, at: Int): Long = ByteBuffer.wrap(b, at, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL

    private const val EOCD_SIG = 0x06054b50L
    private const val CEN_SIG = 0x02014b50L
    private const val LOC_SIG = 0x04034b50L
    private const val EOCD_SIZE = 22
    private const val LOC_SIZE = 30
    private const val MAX_COMMENT = 0xFFFF
    private const val MAX_NAME = 0xFFFF
    private const val MAX_EXTRA = 0xFFFF
    private const val STORED = 0
    private const val DEFLATED = 8
}
