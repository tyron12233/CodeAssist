package dev.ide.android.support.tools

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * The Android framework's resource table in the form `aapt2 link` reads fastest, derived once from an
 * `android.jar` and kept in a cache.
 *
 * Two things about the SDK's jar cost every link:
 *  - Its 18 MB `resources.arsc` is deflated, so each link inflates it first.
 *  - The table's NAME pool is unsorted. aapt2 finds `android:textColor` by name, and in an unsorted pool that is
 *    a linear scan that decodes every name on the way: 14,000 of them per lookup, for the thousands of framework
 *    attributes a Material app's styles and layouts mention. On a phone that scan was 44% of a 4 s link. A pool
 *    marked sorted is binary-searched instead ([ArscKeyPools]).
 *
 * The derived jar holds the manifest and the table, stored. A link against it produces the same bytes as a link
 * against the SDK's jar: nothing about a resource changes, only the order its name sits in.
 */
object FrameworkTable {

    /**
     * The table to pass as aapt2's `-I` for [androidJar], under [cacheDir]. [androidJar] itself when the derived
     * one cannot be made (an unreadable jar, a table this code does not understand): slower, never wrong.
     */
    fun forLinking(androidJar: Path, cacheDir: Path): Path = runCatching {
        val key = signature(androidJar)
        val derived = cacheDir.resolve("$PREFIX$FORMAT-$key.jar")
        if (Files.isRegularFile(derived)) return@runCatching derived
        Files.createDirectories(cacheDir)
        dropOutdated(cacheDir)
        val tmp = Files.createTempFile(cacheDir, "framework-res", ".tmp")
        try {
            ZipFile(androidJar.toFile()).use { jar ->
                ZipOutputStream(Files.newOutputStream(tmp).buffered(1 shl 16)).use { out ->
                    for (name in listOf("AndroidManifest.xml", "resources.arsc")) {
                        val entry = jar.getEntry(name) ?: continue
                        var bytes = jar.getInputStream(entry).use { it.readBytes() }
                        // A table that cannot be re-sorted is still worth storing uncompressed.
                        if (name == "resources.arsc") bytes = runCatching { ArscKeyPools.sorted(bytes) }.getOrDefault(bytes)
                        out.putNextEntry(ZipEntry(name).apply {
                            method = ZipEntry.STORED
                            size = bytes.size.toLong(); compressedSize = size
                            crc = CRC32().apply { update(bytes) }.value
                        })
                        out.write(bytes)
                        out.closeEntry()
                    }
                }
            }
            Files.move(tmp, derived, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(tmp)
        }
        derived
    }.getOrDefault(androidJar)

    /** Bumped when what [forLinking] writes, or how it is named, changes, so an older derived jar is not reused. */
    private const val FORMAT = "s2"
    private const val PREFIX = "framework-res-"
    private const val TAIL_BYTES = 64 * 1024

    /**
     * What the jar holds, not where or when it was written: its size and the end of the file, which is the zip's
     * directory with the checksum of every entry. The app unpacks its `android.jar` again on each update, to a
     * file with a new date, and a signature that included the date made an 18 MB table again each time.
     */
    private fun signature(jar: Path): String {
        val size = Files.size(jar)
        val tail = ByteArray(minOf(size, TAIL_BYTES.toLong()).toInt())
        java.io.RandomAccessFile(jar.toFile(), "r").use { it.seek(size - tail.size); it.readFully(tail) }
        val md = MessageDigest.getInstance("SHA-256")
        md.update(size.toString().toByteArray())
        return md.digest(tail).take(8).joinToString("") { "%02x".format(it) }
    }

    /** Tables an older version of this code left behind: nothing reads them again. */
    private fun dropOutdated(cacheDir: Path) {
        runCatching {
            Files.newDirectoryStream(cacheDir, "$PREFIX*.jar").use { old ->
                for (f in old) if (!f.fileName.toString().startsWith("$PREFIX$FORMAT-")) runCatching { Files.deleteIfExists(f) }
            }
        }
    }
}

/**
 * Rewrites a `resources.arsc` so that each package's key string pool (the resource NAMES) is sorted and flagged
 * so, with every entry pointed at its name's new position. `ResStringPool::indexOfString` binary-searches a pool
 * with the sorted flag and scans one without it.
 *
 * Everything else is copied through byte for byte: the global value pool, the type pool, every configuration and
 * value. Throws on anything unexpected rather than guess, and the caller keeps the original table.
 */
internal object ArscKeyPools {
    private const val RES_STRING_POOL = 0x0001
    private const val RES_TABLE = 0x0002
    private const val RES_TABLE_PACKAGE = 0x0200
    private const val RES_TABLE_TYPE = 0x0201
    private const val SORTED_FLAG = 1
    private const val UTF8_FLAG = 1 shl 8
    private const val ENTRY_COMPACT = 0x0008
    private const val TYPE_SPARSE = 0x01
    private const val TYPE_OFFSET16 = 0x02
    /** `ResTable_package`: chunk header, id, then a 128-char name; the pool offsets follow. */
    private const val PACKAGE_KEY_STRINGS_AT = 8 + 4 + 256 + 8
    private const val PACKAGE_LAST_PUBLIC_KEY_AT = PACKAGE_KEY_STRINGS_AT + 4

    fun sorted(arsc: ByteArray): ByteArray {
        val src = ByteBuffer.wrap(arsc).order(ByteOrder.LITTLE_ENDIAN)
        require(src.u16(0) == RES_TABLE) { "not a resource table" }
        val out = java.io.ByteArrayOutputStream(arsc.size)
        val headerSize = src.u16(2)
        out.write(arsc, 0, headerSize)
        var at = headerSize
        while (at < arsc.size) {
            val type = src.u16(at)
            val size = src.getInt(at + 4)
            require(size >= 8 && at + size <= arsc.size) { "chunk at $at overruns the table" }
            if (type == RES_TABLE_PACKAGE) out.write(sortedPackage(arsc, src, at, size)) else out.write(arsc, at, size)
            at += size
        }
        val result = out.toByteArray()
        ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN).putInt(4, result.size)
        return result
    }

    private fun sortedPackage(arsc: ByteArray, src: ByteBuffer, start: Int, size: Int): ByteArray {
        val poolAt = start + src.getInt(start + PACKAGE_KEY_STRINGS_AT)
        require(src.u16(poolAt) == RES_STRING_POOL) { "no key pool where the package says" }
        val poolSize = src.getInt(poolAt + 4)
        val count = src.getInt(poolAt + 8)
        val styles = src.getInt(poolAt + 12)
        val flags = src.getInt(poolAt + 16)
        val stringsStart = src.getInt(poolAt + 20)
        require(styles == 0) { "a key pool with styles" }
        val utf8 = flags and UTF8_FLAG != 0
        val poolHeader = src.u16(poolAt + 2)

        // Each string as it is stored (lengths, data, terminator), and its text to sort by.
        val raw = arrayOfNulls<ByteArray>(count)
        val text = arrayOfNulls<String>(count)
        for (i in 0 until count) {
            val p = poolAt + stringsStart + src.getInt(poolAt + poolHeader + 4 * i)
            if (utf8) {
                var q = p
                q += if (arsc[q].toInt() and 0x80 != 0) 2 else 1          // length in UTF-16 units
                val byteLen: Int
                if (arsc[q].toInt() and 0x80 != 0) { byteLen = ((arsc[q].toInt() and 0x7f) shl 8) or (arsc[q + 1].toInt() and 0xff); q += 2 }
                else { byteLen = arsc[q].toInt() and 0xff; q += 1 }
                raw[i] = arsc.copyOfRange(p, q + byteLen + 1)
                text[i] = String(arsc, q, byteLen, Charsets.UTF_8)
            } else {
                var q = p
                var len = src.u16(q); q += 2
                if (len and 0x8000 != 0) { len = ((len and 0x7fff) shl 16) or src.u16(q); q += 2 }
                raw[i] = arsc.copyOfRange(p, q + len * 2 + 2)
                text[i] = String(arsc, q, len * 2, Charsets.UTF_16LE)
            }
        }
        // Sorted as `strzcmp16` orders them, by UTF-16 code unit, which is how a Kotlin String compares. A name
        // stored twice becomes one: an entry is matched by the index the search returns.
        val first = LinkedHashMap<String, Int>(count * 2)
        for (i in 0 until count) first.putIfAbsent(text[i]!!, i)
        val order = first.values.sortedWith { a, b -> text[a]!!.compareTo(text[b]!!) }
        val newIndexOf = HashMap<String, Int>(order.size * 2)
        order.forEachIndexed { new, old -> newIndexOf[text[old]!!] = new }
        val remap = IntArray(count) { newIndexOf.getValue(text[it]!!) }

        val out = java.io.ByteArrayOutputStream(size)
        out.write(arsc, start, poolAt - start)                           // package header and type pool, unchanged
        out.write(pool(order, raw, flags or SORTED_FLAG))
        var at = poolAt + poolSize
        val end = start + size
        while (at < end) {
            val type = src.u16(at)
            val chunkSize = src.getInt(at + 4)
            require(chunkSize >= 8 && at + chunkSize <= end) { "chunk at $at overruns its package" }
            val chunk = arsc.copyOfRange(at, at + chunkSize)
            if (type == RES_TABLE_TYPE) remapEntries(chunk, remap)
            out.write(chunk)
            at += chunkSize
        }
        val result = out.toByteArray()
        val buf = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(4, result.size)
        if (buf.getInt(PACKAGE_LAST_PUBLIC_KEY_AT) != 0) buf.putInt(PACKAGE_LAST_PUBLIC_KEY_AT, order.size)
        return result
    }

    private fun pool(order: List<Int>, raw: Array<ByteArray?>, flags: Int): ByteArray {
        val data = java.io.ByteArrayOutputStream()
        val offsets = IntArray(order.size)
        order.forEachIndexed { i, old -> offsets[i] = data.size(); data.write(raw[old]!!) }
        while (data.size() % 4 != 0) data.write(0)
        val header = 28
        val stringsStart = header + 4 * order.size
        val buf = ByteBuffer.allocate(stringsStart + data.size()).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(RES_STRING_POOL.toShort()).putShort(header.toShort()).putInt(buf.capacity())
        buf.putInt(order.size).putInt(0).putInt(flags).putInt(stringsStart).putInt(0)
        offsets.forEach { buf.putInt(it) }
        buf.put(data.toByteArray())
        return buf.array()
    }

    /** Point every entry of one `ResTable_type` chunk at its key's new index. */
    private fun remapEntries(chunk: ByteArray, remap: IntArray) {
        val buf = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN)
        val headerSize = buf.u16(2)
        val typeFlags = chunk[9].toInt() and 0xff
        val count = buf.getInt(12)
        val entriesStart = buf.getInt(16)
        val done = HashSet<Int>()
        fun entry(offset: Int) {
            val at = entriesStart + offset
            if (!done.add(at)) return                                    // two offsets may share one entry
            val entryFlags = buf.u16(at + 2)
            if (entryFlags and ENTRY_COMPACT != 0) {
                val key = buf.u16(at)
                buf.putShort(at, remap[key].also { require(it <= 0xffff) { "compact key out of range" } }.toShort())
            } else {
                buf.putInt(at + 4, remap[buf.getInt(at + 4)])
            }
        }
        when {
            typeFlags and TYPE_SPARSE != 0 -> for (i in 0 until count) entry(buf.u16(headerSize + 4 * i + 2) * 4)
            typeFlags and TYPE_OFFSET16 != 0 -> for (i in 0 until count) {
                val offset = buf.u16(headerSize + 2 * i)
                if (offset != 0xffff) entry(offset * 4)
            }
            else -> for (i in 0 until count) {
                val offset = buf.getInt(headerSize + 4 * i)
                if (offset != -1) entry(offset)
            }
        }
    }

    private fun ByteBuffer.u16(at: Int): Int = getShort(at).toInt() and 0xffff
}
