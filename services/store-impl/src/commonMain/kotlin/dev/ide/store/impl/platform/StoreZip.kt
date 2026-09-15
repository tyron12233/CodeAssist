package dev.ide.store.impl.platform

/** One entry in an archive, as the extractor needs to see it before deciding whether to write it. */
internal class ZipEntryInfo(
    /** The name exactly as the archive carries it, separators and all. */
    val name: String,
    val isDirectory: Boolean,
    /** The uncompressed size the archive claims, which is what the zip-bomb ceiling is measured against. */
    val size: Long,
    /** Where the actual finds this entry again. Opaque to the caller. */
    internal val index: Int,
)

/**
 * A zip opened for reading, with its directory parsed once.
 *
 * Handle-based rather than a pair of one-shot functions because a payload can hold four thousand entries,
 * and re-reading the archive's directory per entry would turn an install into a quadratic one.
 */
internal expect class ZipArchive {

    /** Every entry, in the order the archive lists them. */
    fun entries(): List<ZipEntryInfo>

    /** Write [entry]'s bytes to [destPath], creating nothing above it. Returns bytes written, or -1. */
    fun extractTo(entry: ZipEntryInfo, destPath: String): Long

    fun close()
}

/** Open [path] for reading, or null when it is not a readable archive. */
internal expect fun openZip(path: String): ZipArchive?
