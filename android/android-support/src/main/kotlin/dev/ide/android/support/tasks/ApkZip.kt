package dev.ide.android.support.tasks

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.ZipEntry

/**
 * A zip's central directory, read without opening the archive as a [java.util.zip.ZipFile]: each entry's method,
 * CRC, sizes and where its compressed bytes start, so they can be copied into another zip AS THEY ARE ([ApkZipWriter.copyRaw]).
 * That is what lets packaging skip inflating and re-deflating what aapt2 already compressed, and reuse an unchanged
 * entry from the previous APK. Plain zips only: [read] returns null for zip64 or anything it can't parse, and the
 * caller packages the slow way.
 */
internal class ZipIndex private constructor(val path: Path, val entries: Map<String, Entry>) {
    class Entry(val name: String, val method: Int, val crc: Long, val compressedSize: Long, val size: Long, val dataOffset: Long)

    companion object {
        private const val EOCD_SIG = 0x06054b50
        private const val CEN_SIG = 0x02014b50
        private const val LOC_SIG = 0x04034b50

        fun read(path: Path): ZipIndex? = runCatching {
            RandomAccessFile(path.toFile(), "r").use { f ->
                val len = f.length()
                if (len < 22) return null
                val tailLen = minOf(len, 22L + 0xFFFF).toInt()
                val tail = ByteArray(tailLen)
                f.seek(len - tailLen); f.readFully(tail)
                val t = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN)
                var eocd = -1
                for (i in tailLen - 22 downTo 0) if (t.getInt(i) == EOCD_SIG) { eocd = i; break }
                if (eocd < 0) return null
                val count = t.getShort(eocd + 10).toInt() and 0xFFFF
                val cdSize = t.getInt(eocd + 12).toLong() and 0xFFFFFFFFL
                val cdOffset = t.getInt(eocd + 16).toLong() and 0xFFFFFFFFL
                if (count == 0xFFFF || cdOffset == 0xFFFFFFFFL || cdOffset + cdSize > len) return null   // zip64
                val cd = ByteArray(cdSize.toInt())
                f.seek(cdOffset); f.readFully(cd)
                val b = ByteBuffer.wrap(cd).order(ByteOrder.LITTLE_ENDIAN)
                val out = LinkedHashMap<String, Entry>(count * 2)
                val loc = ByteArray(30)
                var p = 0
                repeat(count) {
                    if (b.getInt(p) != CEN_SIG) return null
                    val method = b.getShort(p + 10).toInt() and 0xFFFF
                    val crc = b.getInt(p + 16).toLong() and 0xFFFFFFFFL
                    val csize = b.getInt(p + 20).toLong() and 0xFFFFFFFFL
                    val size = b.getInt(p + 24).toLong() and 0xFFFFFFFFL
                    val nameLen = b.getShort(p + 28).toInt() and 0xFFFF
                    val extraLen = b.getShort(p + 30).toInt() and 0xFFFF
                    val commentLen = b.getShort(p + 32).toInt() and 0xFFFF
                    val lho = b.getInt(p + 42).toLong() and 0xFFFFFFFFL
                    val name = String(cd, p + 46, nameLen, Charsets.UTF_8)
                    if (csize == 0xFFFFFFFFL || size == 0xFFFFFFFFL || lho == 0xFFFFFFFFL) return null
                    // The local header's extra field can differ from the central one: read its lengths there.
                    f.seek(lho); f.readFully(loc)
                    val l = ByteBuffer.wrap(loc).order(ByteOrder.LITTLE_ENDIAN)
                    if (l.getInt(0) != LOC_SIG) return null
                    val dataOffset = lho + 30 + (l.getShort(26).toInt() and 0xFFFF) + (l.getShort(28).toInt() and 0xFFFF)
                    if (!name.endsWith("/")) out[name] = Entry(name, method, crc, csize, size, dataOffset)
                    p += 46 + nameLen + extraLen + commentLen
                }
                ZipIndex(path, out)
            }
        }.getOrNull()
    }
}

/**
 * Writes a plain zip (no zip64) whose entries are either copied as raw compressed bytes from another zip
 * ([copyRaw]) or written from a file ([putFile], STORED or deflated at [level]). The APK packager's writer: it never
 * inflates what is already compressed. Alignment is not its job; the sign step zipaligns.
 */
internal class ApkZipWriter(path: Path, private val level: Int) : Closeable {
    private class Counting(private val out: OutputStream) : OutputStream() {
        var count = 0L
        override fun write(b: Int) { out.write(b); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); count += len }
        override fun flush() = out.flush()
        override fun close() = out.close()
    }

    private class Central(val name: ByteArray, val method: Int, val flags: Int, val crc: Long, val csize: Long, val size: Long, val offset: Long)

    private val out = Counting(BufferedOutputStream(Files.newOutputStream(path), 1 shl 17))
    private val central = ArrayList<Central>()
    private val deflater = Deflater(level, true)
    private val buf = ByteArray(1 shl 16)

    /** Copy [e] out of [from] without decompressing it. */
    fun copyRaw(from: ZipIndex, e: ZipIndex.Entry, name: String = e.name) {
        val n = name.toByteArray(Charsets.UTF_8)
        val offset = checkOffset()
        local(n, e.method, UTF8, e.crc, e.compressedSize, e.size)
        RandomAccessFile(from.path.toFile(), "r").use { f ->
            f.seek(e.dataOffset)
            var left = e.compressedSize
            while (left > 0) {
                val k = f.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (k < 0) throw IOException("${from.path.fileName} ended inside ${e.name}")
                out.write(buf, 0, k); left -= k
            }
        }
        central += Central(n, e.method, UTF8, e.crc, e.compressedSize, e.size, offset)
    }

    /** Write [file] as [name], STORED when [stored], else deflated at this writer's level. */
    fun putFile(name: String, file: Path, stored: Boolean) {
        val n = name.toByteArray(Charsets.UTF_8)
        val offset = checkOffset()
        if (stored) {
            val crc = crcOf(file)
            val size = Files.size(file)
            local(n, ZipEntry.STORED, UTF8, crc, size, size)
            Files.newInputStream(file).use { it.copyTo(out, buf.size) }
            central += Central(n, ZipEntry.STORED, UTF8, crc, size, size, offset)
            return
        }
        // Sizes and CRC aren't known until the data is written: a data descriptor follows it (flag bit 3).
        local(n, ZipEntry.DEFLATED, UTF8 or DESCRIPTOR, 0, 0, 0)
        val start = out.count
        val crc = CRC32()
        var size = 0L
        deflater.reset()
        val dos = DeflaterOutputStream(object : OutputStream() {
            override fun write(b: Int) = out.write(b)
            override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
        }, deflater, buf.size)
        Files.newInputStream(file).use { input ->
            while (true) {
                val k = input.read(buf)
                if (k < 0) break
                crc.update(buf, 0, k); size += k
                dos.write(buf, 0, k)
            }
        }
        dos.finish()
        val csize = out.count - start
        val d = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0x08074b50).putInt(crc.value.toInt()).putInt(csize.toInt()).putInt(size.toInt())
        out.write(d.array())
        central += Central(n, ZipEntry.DEFLATED, UTF8 or DESCRIPTOR, crc.value, csize, size, offset)
    }

    override fun close() {
        val cdStart = checkOffset()
        for (c in central) {
            val h = ByteBuffer.allocate(46).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0x02014b50).putShort(20).putShort(20).putShort(c.flags.toShort()).putShort(c.method.toShort())
                .putShort(0).putShort(0x21)   // time 00:00, date 1980-01-01: deterministic
                .putInt(c.crc.toInt()).putInt(c.csize.toInt()).putInt(c.size.toInt())
                .putShort(c.name.size.toShort()).putShort(0).putShort(0).putShort(0).putShort(0).putInt(0)
                .putInt(c.offset.toInt())
            out.write(h.array()); out.write(c.name)
        }
        val cdSize = out.count - cdStart
        if (central.size >= 0xFFFF || out.count >= 0xFFFFFFFFL) throw IOException("archive needs zip64")
        val e = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0x06054b50).putShort(0).putShort(0).putShort(central.size.toShort()).putShort(central.size.toShort())
            .putInt(cdSize.toInt()).putInt(cdStart.toInt()).putShort(0)
        out.write(e.array())
        out.close()
        deflater.end()
    }

    private fun local(name: ByteArray, method: Int, flags: Int, crc: Long, csize: Long, size: Long) {
        val h = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0x04034b50).putShort(20).putShort(flags.toShort()).putShort(method.toShort())
            .putShort(0).putShort(0x21)
            .putInt(crc.toInt()).putInt(csize.toInt()).putInt(size.toInt())
            .putShort(name.size.toShort()).putShort(0)
        out.write(h.array()); out.write(name)
    }

    private fun checkOffset(): Long = out.count.also { if (it >= 0xFFFFFFFFL) throw IOException("archive needs zip64") }

    companion object {
        private const val UTF8 = 0x0800
        private const val DESCRIPTOR = 0x0008

        fun crcOf(file: Path): Long {
            val crc = CRC32()
            val b = ByteArray(1 shl 16)
            Files.newInputStream(file).use { input -> while (true) { val k = input.read(b); if (k < 0) break; crc.update(b, 0, k) } }
            return crc.value
        }
    }
}

/**
 * The CRC of each file the packager put in the APK, by path, size and modified time, kept beside the APK: an edit's
 * packaging proves its unchanged dex unchanged from a stat instead of re-reading megabytes to checksum them.
 */
internal class CrcCache(private val file: Path) {
    private val entries = HashMap<String, String>()   // path -> "size:mtime:crc"
    private var dirty = false

    init {
        runCatching {
            if (Files.isRegularFile(file)) Files.readAllLines(file).forEach { l ->
                val t = l.indexOf('\t'); if (t > 0) entries[l.substring(0, t)] = l.substring(t + 1)
            }
        }
    }

    fun crcOf(f: Path): Long {
        val key = f.toAbsolutePath().toString()
        val sig = "${Files.size(f)}:${Files.getLastModifiedTime(f).toMillis()}"
        entries[key]?.let { v -> if (v.substringBeforeLast(':') == sig) v.substringAfterLast(':').toLongOrNull()?.let { return it } }
        val crc = ApkZipWriter.crcOf(f)
        entries[key] = "$sig:$crc"; dirty = true
        return crc
    }

    fun save() {
        if (!dirty) return
        runCatching { Files.write(file, entries.map { "${it.key}\t${it.value}" }) }
    }
}
