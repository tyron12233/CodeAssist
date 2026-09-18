package dev.ide.platform

/**
 * A file being written a piece at a time, which [writeFileAtomically] cannot do.
 *
 * The whole-file write is the right shape for a cache: it is small, it is built in memory, and a reader must
 * never see half of it. It is the wrong shape for building an index segment, which is the reason this exists.
 * A segment is written by an external merge sort that spills sorted runs to temporary files precisely so the
 * whole thing is never in memory at once, and handing it a `ByteArray` would undo the one property it was
 * built for.
 *
 * So this is the write half of [ByteSource]: append-only, no seeking, and the caller decides when it is done.
 * There is no atomicity here — a caller that needs it writes to a temporary and calls [moveFile], which is
 * what the atomic whole-file write does internally anyway.
 */
interface FileSink {
    /** Append [length] bytes of [bytes] starting at [offset]. */
    fun write(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset)

    /**
     * Push everything written so far out to the file.
     *
     * Both implementations buffer, so until this returns a reader that opens the same path sees a SHORT file
     * rather than an empty one — which does not fail, it truncates. The index's spill buffer writes a region
     * and then reads it back to concatenate it, so it is the caller that needs this and the reason it exists.
     */
    fun flush()

    /** Flush and release the handle. Writing after this is an error. */
    fun close()
}

/**
 * Open [path] for writing, replacing anything already there, creating the file if it does not exist.
 *
 * Null when the path cannot be opened: a missing parent directory, or no permission. Parents are NOT created
 * — a caller that wants that calls [createDirectories] first, as the atomic write does.
 */
expect fun openFileForWrite(path: String): FileSink?

/**
 * Rename [from] over [to], replacing it.
 *
 * Within one file system this is atomic on every platform, which is what makes it the last step of building
 * anything a reader might open while it is being written: the reader sees the old file or the new one.
 */
expect fun moveFile(from: String, to: String): Boolean
