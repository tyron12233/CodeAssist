package dev.ide.kotlin.classfile

/**
 * One entry in an archive, as the central directory describes it.
 *
 * The sizes come from the central directory and NOT from the local header, which is allowed to say zero and
 * defer the real values to a data descriptor written after the data. Trusting the local header is how a
 * reader ends up inflating into a zero-length buffer on archives produced by anything that streams.
 */
class ZipEntry internal constructor(
    val name: String,
    val method: Int,
    val compressedSize: Long,
    val size: Long,
    val crc32: Long,
    internal val localHeaderOffset: Long,
) {
    val isDirectory: Boolean get() = name.endsWith("/")

    override fun toString(): String = name
}

/**
 * A zip archive, read without `java.util.zip`.
 *
 * The other half of reading a classpath off the JVM. A decoder that can make sense of a `.class` file is of
 * no use on iOS if the `.class` file is inside a jar and nothing can open the jar, and every dependency a
 * project has is a jar.
 *
 * A zip is read BACK TO FRONT. The index is at the end, which is what makes an archive appendable and what
 * makes a streaming read of one impossible: nothing at the front says where anything is. That is the whole
 * reason this is defined over [ByteSource] rather than over a byte array.
 *
 * Deliberately does not cover: encryption, multi-disk archives, and anything but the two compression
 * methods a jar actually uses. Those are not gaps to fill later; a jar that needs them is not a classpath
 * entry this can index, and pretending otherwise would be worse than returning null.
 */
class ZipArchive private constructor(
    private val source: ByteSource,
    val entries: List<ZipEntry>,
) {

    private val byName: Map<String, ZipEntry> = entries.associateBy { it.name }

    fun entry(name: String): ZipEntry? = byName[name]

    /**
     * The decompressed contents of [entry], or null when they cannot be produced.
     *
     * Null covers a corrupt entry, an unsupported compression method, and a checksum that does not match.
     * An index reads jars written by every tool that ever emitted one, and one bad entry must cost that
     * entry rather than the archive.
     */
    fun read(entry: ZipEntry): ByteArray? = runCatching { readOrThrow(entry) }.getOrNull()

    private fun readOrThrow(entry: ZipEntry): ByteArray? {
        check(entry.size <= Int.MAX_VALUE && entry.compressedSize <= Int.MAX_VALUE) {
            "${entry.name} is too large to read into one array"
        }

        // The local header repeats the name and may carry a DIFFERENT extra field from the central
        // directory's, so the data offset cannot be computed from the central directory alone: the two
        // extra-field lengths genuinely differ in archives produced by common tools.
        val header = source.read(entry.localHeaderOffset, LOCAL_HEADER_SIZE)
        val reader = LittleEndian(header)
        if (reader.u32() != LOCAL_HEADER_SIGNATURE) return null
        reader.skip(22)
        val nameLength = reader.u16()
        val extraLength = reader.u16()

        val dataOffset = entry.localHeaderOffset + LOCAL_HEADER_SIZE + nameLength + extraLength
        val compressed = source.read(dataOffset, entry.compressedSize.toInt())

        val bytes = when (entry.method) {
            METHOD_STORED -> compressed
            METHOD_DEFLATED -> Inflate.inflate(compressed, entry.size.toInt()) ?: return null
            else -> return null
        }
        // The one independent check on our own decompressor. Worth its cost: without it a subtly wrong
        // inflate produces a plausible class file rather than a failure.
        if (Crc32.of(bytes) != entry.crc32) return null
        return bytes
    }

    companion object {
        private const val LOCAL_HEADER_SIGNATURE = 0x04034B50L
        private const val CENTRAL_HEADER_SIGNATURE = 0x02014B50L
        private const val END_SIGNATURE = 0x06054B50L
        private const val ZIP64_LOCATOR_SIGNATURE = 0x07064B50L
        private const val ZIP64_END_SIGNATURE = 0x06064B50L

        private const val LOCAL_HEADER_SIZE = 30
        private const val CENTRAL_HEADER_SIZE = 46
        private const val END_SIZE = 22
        private const val ZIP64_LOCATOR_SIZE = 20

        /** The marker a 32-bit field carries when the real value is in the zip64 extra field instead. */
        private const val NEEDS_ZIP64 = 0xFFFFFFFFL

        private const val METHOD_STORED = 0
        private const val METHOD_DEFLATED = 8

        /** The zip64 extra field's header id. */
        private const val EXTRA_ZIP64 = 0x0001

        fun open(bytes: ByteArray): ZipArchive? = open(ByteArraySource(bytes))

        fun open(source: ByteSource): ZipArchive? = runCatching { read(source) }.getOrNull()

        private fun read(source: ByteSource): ZipArchive? {
            // The end record is last, but a trailing comment of up to 64 KB may follow it, so its position
            // is not fixed and it has to be searched for. Scanning backwards finds the real one first when a
            // comment happens to contain the signature.
            val tailLength = minOf(source.size, (END_SIZE + 0xFFFF).toLong()).toInt()
            val tailStart = source.size - tailLength
            val tail = source.read(tailStart, tailLength)

            var endAt = -1
            for (i in tailLength - END_SIZE downTo 0) {
                if (readU32(tail, i) == END_SIGNATURE) {
                    endAt = i
                    break
                }
            }
            if (endAt < 0) return null

            val end = LittleEndian(tail, endAt + 8)
            var entryCount = end.u16().toLong()
            end.skip(2)
            var directorySize = end.u32()
            var directoryOffset = end.u32()

            // Zip64. The 32-bit fields are saturated rather than wrong, so the only way to know the real
            // values is the locator sitting immediately before the end record.
            if (entryCount == 0xFFFFL || directorySize == NEEDS_ZIP64 || directoryOffset == NEEDS_ZIP64) {
                val locatorAt = endAt - ZIP64_LOCATOR_SIZE
                if (locatorAt < 0 || readU32(tail, locatorAt) != ZIP64_LOCATOR_SIGNATURE) return null
                val zip64EndOffset = LittleEndian(tail, locatorAt + 8).u64()
                val record = LittleEndian(source.read(zip64EndOffset, 56))
                if (record.u32() != ZIP64_END_SIGNATURE) return null
                record.skip(28) // record size, versions, disk numbers, entries on this disk
                entryCount = record.u64()
                directorySize = record.u64()
                directoryOffset = record.u64()
            }

            val directory = LittleEndian(source.read(directoryOffset, directorySize.toInt()))
            val entries = ArrayList<ZipEntry>(entryCount.toInt())
            // A record that does not start with the signature means the position has drifted, and every
            // byte after it is being read at the wrong offset. Stopping keeps whatever was read correctly;
            // carrying on would invent entries out of the middle of file names.
            repeat(entryCount.toInt()) {
                entries.add(readCentralEntry(directory) ?: return ZipArchive(source, entries))
            }
            return ZipArchive(source, entries)
        }

        private fun readCentralEntry(reader: LittleEndian): ZipEntry? {
            if (reader.u32() != CENTRAL_HEADER_SIGNATURE) return null
            reader.skip(6) // versions and general-purpose flags
            val method = reader.u16()
            reader.skip(4) // modification time and date
            val crc = reader.u32()
            var compressedSize = reader.u32()
            var size = reader.u32()
            val nameLength = reader.u16()
            val extraLength = reader.u16()
            val commentLength = reader.u16()
            reader.skip(8) // disk number, attributes
            var localHeaderOffset = reader.u32()

            // Names are UTF-8 when general-purpose flag 11 says so and CP437 otherwise. Every jar writes
            // ASCII, where the two agree, so this decodes as UTF-8 and the difference stays theoretical.
            val name = reader.bytes(nameLength).decodeToString()
            val extra = reader.bytes(extraLength)
            reader.skip(commentLength)

            // The zip64 extra field lists ONLY the values that were saturated, in a fixed order and with no
            // markers. So which ones are present is inferred from which 32-bit fields said 0xFFFFFFFF, and
            // reading them unconditionally reads someone else's bytes.
            if (size == NEEDS_ZIP64 || compressedSize == NEEDS_ZIP64 || localHeaderOffset == NEEDS_ZIP64) {
                val fields = LittleEndian(extra)
                while (fields.remaining >= 4) {
                    val id = fields.u16()
                    val length = fields.u16()
                    if (id != EXTRA_ZIP64) {
                        fields.skip(length)
                        continue
                    }
                    if (size == NEEDS_ZIP64) size = fields.u64()
                    if (compressedSize == NEEDS_ZIP64) compressedSize = fields.u64()
                    if (localHeaderOffset == NEEDS_ZIP64) localHeaderOffset = fields.u64()
                    break
                }
            }

            return ZipEntry(name, method, compressedSize, size, crc, localHeaderOffset)
        }

        private fun readU32(bytes: ByteArray, at: Int): Long =
            (bytes[at].toLong() and 0xFF) or
                ((bytes[at + 1].toLong() and 0xFF) shl 8) or
                ((bytes[at + 2].toLong() and 0xFF) shl 16) or
                ((bytes[at + 3].toLong() and 0xFF) shl 24)
    }
}

/**
 * Little-endian reading, which is what zip uses and class files do not.
 *
 * The two formats in this module disagree about byte order, and the readers are separate so that neither
 * has a flag to get wrong.
 */
internal class LittleEndian(private val bytes: ByteArray, private var at: Int = 0) {

    val remaining: Int get() = bytes.size - at

    fun u16(): Int = (bytes[at++].toInt() and 0xFF) or ((bytes[at++].toInt() and 0xFF) shl 8)

    fun u32(): Long = (u16().toLong() and 0xFFFF) or ((u16().toLong() and 0xFFFF) shl 16)

    fun u64(): Long = (u32() and 0xFFFFFFFFL) or (u32() shl 32)

    fun skip(count: Int) {
        at += count
    }

    fun bytes(count: Int): ByteArray {
        val slice = bytes.copyOfRange(at, at + count)
        at += count
        return slice
    }
}
