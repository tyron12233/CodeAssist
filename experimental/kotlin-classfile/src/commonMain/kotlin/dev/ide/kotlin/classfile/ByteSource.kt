package dev.ide.kotlin.classfile

/**
 * Random access to a run of bytes, which is what a zip file needs and a stream cannot give.
 *
 * A zip is read back to front: its index lives at the END of the file, and every entry is found by seeking
 * to an offset the index names. So the archive reader is defined against this rather than against a byte
 * array, and a platform that can seek a file supplies a file-backed one. A 40 MB `android.jar` does not
 * want to be resident in memory to have three classes read out of it.
 */
interface ByteSource {
    val size: Long

    /** Exactly [length] bytes starting at [offset]. Reading past the end is a programming error, not EOF. */
    fun read(offset: Long, length: Int): ByteArray
}

/** A [ByteSource] over bytes already in memory. */
class ByteArraySource(private val bytes: ByteArray) : ByteSource {
    override val size: Long get() = bytes.size.toLong()

    override fun read(offset: Long, length: Int): ByteArray {
        val start = offset.toInt()
        return bytes.copyOfRange(start, start + length)
    }
}
