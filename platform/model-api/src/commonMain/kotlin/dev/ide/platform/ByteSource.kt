package dev.ide.platform

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

/**
 * A [ByteSource] backed by a file, holding a handle open until [close].
 *
 * The reason [ByteSource] is an interface at all. A jar is read back to front and then in scattered pieces,
 * so the natural implementation seeks rather than buffers, and `android.jar` is 43 MB that nothing wants
 * resident to have three classes read out of it.
 */
interface FileSource : ByteSource {
    fun close()
}

/**
 * Open [path] for reading, or null when it cannot be opened.
 *
 * The only platform-specific line in the module. Everything else is format work that runs anywhere; this is
 * the one thing a Kotlin/Native target genuinely has to say for itself, and it is an `expect` rather than a
 * dependency because the whole surface is open, size, seek and read.
 *
 * The caller owns the handle. A [ZipArchive] built over one keeps reading from it for as long as entries are
 * being read, so closing it early truncates the archive rather than failing loudly.
 */
expect fun openFile(path: String): FileSource?
