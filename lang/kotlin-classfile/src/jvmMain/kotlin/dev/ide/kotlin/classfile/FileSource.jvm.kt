package dev.ide.kotlin.classfile

import java.io.RandomAccessFile

/**
 * The JVM's seek-and-read, which is exactly the shape [ByteSource] wants.
 *
 * `RandomAccessFile` rather than a memory map: a map would be faster for a jar read end to end, and this is
 * not that. An index reads an archive's directory and then a scattered handful of its entries, and a map of
 * a 43 MB file costs address space per jar for a benefit that only shows up on a full read.
 */
actual fun openFile(path: String): FileSource? = runCatching {
    val file = RandomAccessFile(path, "r")
    object : FileSource {
        override val size: Long = file.length()

        override fun read(offset: Long, length: Int): ByteArray {
            val bytes = ByteArray(length)
            file.seek(offset)
            file.readFully(bytes)
            return bytes
        }

        override fun close() = file.close()
    }
}.getOrNull()
