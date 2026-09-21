package dev.ide.deps.impl

import dev.ide.kotlin.classfile.Crc32

/**
 * A zip archive built in memory, so a test can publish a real `.aar` on any platform.
 *
 * Every entry is STORED (no compression), which is what a real AAR does with its `classes.jar` anyway: a jar
 * is already compressed, and deflating it again buys nothing. Stored also keeps this to header arithmetic,
 * with no compressor on either side of the seam.
 */
internal fun storedZip(entries: List<Pair<String, ByteArray>>): ByteArray {
    val out = ArrayList<Byte>()
    fun u16(value: Int) {
        out.add((value and 0xFF).toByte())
        out.add(((value ushr 8) and 0xFF).toByte())
    }

    fun u32(value: Long) {
        u16((value and 0xFFFF).toInt())
        u16(((value ushr 16) and 0xFFFF).toInt())
    }

    fun raw(bytes: ByteArray) = bytes.forEach { out.add(it) }

    class Record(val name: ByteArray, val crc: Long, val size: Int, val offset: Int)

    val records = ArrayList<Record>()
    for ((name, content) in entries) {
        val nameBytes = name.encodeToByteArray()
        val offset = out.size
        val crc = Crc32.of(content)
        u32(LOCAL_HEADER)
        u16(20); u16(0); u16(0); u16(0); u16(0)                 // version, flags, method (stored), time, date
        u32(crc); u32(content.size.toLong()); u32(content.size.toLong())
        u16(nameBytes.size); u16(0)                             // name length, extra length
        raw(nameBytes); raw(content)
        records += Record(nameBytes, crc, content.size, offset)
    }

    val directoryOffset = out.size
    for (record in records) {
        u32(CENTRAL_HEADER)
        u16(20); u16(20); u16(0); u16(0); u16(0); u16(0)        // made by, needed, flags, method, time, date
        u32(record.crc); u32(record.size.toLong()); u32(record.size.toLong())
        u16(record.name.size); u16(0); u16(0)                   // name, extra, comment lengths
        u16(0); u16(0); u32(0)                                  // disk, internal attrs, external attrs
        u32(record.offset.toLong())
        raw(record.name)
    }

    val directorySize = out.size - directoryOffset
    u32(END_OF_DIRECTORY)
    u16(0); u16(0); u16(records.size); u16(records.size)
    u32(directorySize.toLong()); u32(directoryOffset.toLong()); u16(0)
    return out.toByteArray()
}

/**
 * A real, tiny jar: an archive that opens and has something in it.
 *
 * Arbitrary bytes will not do for an AAR's inner `classes.jar`: the JVM exploder validates what it copied
 * and replaces an unusable archive with a manifest-only one, because some published AARs really do ship a
 * zero-entry `classes.jar`.
 */
internal val TINY_JAR: ByteArray = storedZip(
    listOf(
        "META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\r\n\r\n".encodeToByteArray(),
        "com/example/Widget.class" to byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()),
    ),
)

/** An `.aar` the way one is actually shaped: code in `classes.jar`, plus the resources nothing here reads. */
internal fun aarBytes(classesJar: ByteArray): ByteArray = storedZip(
    listOf(
        "AndroidManifest.xml" to "<manifest package=\"com.example\" />".encodeToByteArray(),
        "classes.jar" to classesJar,
        "res/values/values.xml" to "<resources />".encodeToByteArray(),
    ),
)

private const val LOCAL_HEADER = 0x04034b50L
private const val CENTRAL_HEADER = 0x02014b50L
private const val END_OF_DIRECTORY = 0x06054b50L
