package dev.ide.android.support

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.zip.CRC32

/**
 * Writes a copy of a zip (an aapt2 `resources.ap_`) with some existing entries replaced, without re-encoding the
 * rest. Every original byte is copied as-is, so untouched entries keep their offsets (and `resources.arsc` its
 * alignment for memory mapping); the replacements are appended as STORED entries and a new central directory
 * points their names at them, leaving the old copies as unreferenced bytes.
 *
 * Plain zips only: no zip64, no spanning, one central directory record per name. Anything else, or a
 * replacement for a name the zip lacks, returns false and writes nothing.
 */
internal object ApkEntryPatcher {

    private const val EOCD = 0x06054b50
    private const val CEN = 0x02014b50
    private const val LOC = 0x04034b50

    fun replace(src: Path, dst: Path, replacements: Map<String, ByteArray>): Boolean {
        FileChannel.open(src, StandardOpenOption.READ).use { ch ->
            val size = ch.size()
            val tailLen = minOf(size, 22L + 0xFFFF).toInt()
            val tail = ByteBuffer.allocate(tailLen).order(ByteOrder.LITTLE_ENDIAN)
            ch.read(tail, size - tailLen); tail.flip()
            var eocd = -1
            for (i in tailLen - 22 downTo 0) if (tail.getInt(i) == EOCD) { eocd = i; break }
            if (eocd < 0) return false
            val count = tail.getShort(eocd + 10).toInt() and 0xFFFF
            val cdSize = tail.getInt(eocd + 12).toLong() and 0xFFFFFFFFL
            val cdOffset = tail.getInt(eocd + 16).toLong() and 0xFFFFFFFFL
            if (count == 0xFFFF || cdOffset == 0xFFFFFFFFL || cdOffset + cdSize > size) return false

            val cd = ByteBuffer.allocate(cdSize.toInt()).order(ByteOrder.LITTLE_ENDIAN)
            ch.read(cd, cdOffset); cd.flip()
            class Record(val name: String, val start: Int, val end: Int)
            val records = ArrayList<Record>(count)
            var p = 0
            repeat(count) {
                if (p + 46 > cd.limit() || cd.getInt(p) != CEN) return false
                val n = cd.getShort(p + 28).toInt() and 0xFFFF
                val m = cd.getShort(p + 30).toInt() and 0xFFFF
                val k = cd.getShort(p + 32).toInt() and 0xFFFF
                val nameBytes = ByteArray(n).also { cd.position(p + 46); cd.get(it) }
                records += Record(String(nameBytes, Charsets.UTF_8), p, p + 46 + n + m + k)
                p += 46 + n + m + k
            }
            val names = records.map { it.name }
            if (names.toSet().size != names.size || !names.containsAll(replacements.keys)) return false

            Files.deleteIfExists(dst)
            FileChannel.open(dst, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { out ->
                // 1. Every original byte before the central directory, as-is.
                var copied = 0L
                while (copied < cdOffset) copied += ch.transferTo(copied, cdOffset - copied, out)
                // 2. The replacements, appended as stored entries.
                val newOffset = HashMap<String, Long>()
                val crcs = HashMap<String, Int>()
                for ((name, data) in replacements) {
                    newOffset[name] = out.position()
                    val crc = CRC32().apply { update(data) }.value.toInt().also { crcs[name] = it }
                    val nb = name.toByteArray(Charsets.UTF_8)
                    val h = ByteBuffer.allocate(30 + nb.size).order(ByteOrder.LITTLE_ENDIAN)
                    h.putInt(LOC).putShort(10).putShort(0).putShort(0).putShort(0).putShort(0x21)
                        .putInt(crc).putInt(data.size).putInt(data.size).putShort(nb.size.toShort()).putShort(0).put(nb)
                    h.flip(); while (h.hasRemaining()) out.write(h)
                    val d = ByteBuffer.wrap(data); while (d.hasRemaining()) out.write(d)
                }
                // 3. The central directory: originals verbatim, replaced names pointing at the appended copies.
                val cdStart = out.position()
                for (r in records) {
                    val rec = ByteBuffer.allocate(r.end - r.start).order(ByteOrder.LITTLE_ENDIAN)
                    cd.position(r.start); cd.limit(r.end); rec.put(cd); cd.limit(cd.capacity()); rec.flip()
                    val data = replacements[r.name]
                    if (data != null) {
                        rec.putShort(10, 0)                          // method: stored
                        rec.putShort(8, (rec.getShort(8).toInt() and 0x0800).toShort())   // flags: keep UTF-8 only
                        rec.putInt(16, crcs.getValue(r.name))
                        rec.putInt(20, data.size).putInt(24, data.size)
                        rec.putInt(42, newOffset.getValue(r.name).toInt())
                    }
                    while (rec.hasRemaining()) out.write(rec)
                }
                val cdLen = out.position() - cdStart
                val end = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN)
                end.putInt(EOCD).putShort(0).putShort(0).putShort(count.toShort()).putShort(count.toShort())
                    .putInt(cdLen.toInt()).putInt(cdStart.toInt()).putShort(0)
                end.flip(); while (end.hasRemaining()) out.write(end)
            }
            return true
        }
    }
}
