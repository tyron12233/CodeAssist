package dev.ide.platform

/**
 * What a directory entry is, for a reader deciding whether to walk into it or open it.
 *
 * [lastModified] is milliseconds since the epoch, and together with [size] it is what a content-keyed cache
 * is keyed on, so both have to mean the same thing on every platform.
 */
class FileInfo(
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
)

/**
 * The file system, as much of it as reading and caching a classpath needs.
 *
 * Six operations, and not one more. A classpath reader stats a jar to key a cache, walks a directory of
 * class files, reads bytes, and persists its scan somewhere it can read back. Everything else it does is
 * format work that already runs anywhere.
 *
 * Deliberately not an interface with an injected implementation: there is exactly one file system per
 * platform, and threading a parameter for it through every caller would be ceremony around a constant. It
 * is `expect`/`actual` for the same reason [openFile] is.
 */
expect fun fileInfo(path: String): FileInfo?

/** The entries directly inside [path], as full paths. Empty when it is not a readable directory. */
expect fun listDirectory(path: String): List<String>

expect fun readFile(path: String): ByteArray?

/** Creates [path] and every missing parent. True when it exists as a directory afterwards. */
expect fun createDirectories(path: String): Boolean

/**
 * Writes [bytes] to [path] so that a reader sees either the old contents or the new ones, never a partial
 * write.
 *
 * A persisted cache is read by the next launch, and a half-written one is indistinguishable from a complete
 * one: it parses, and then it is wrong. Every platform has a rename that is atomic within a file system, so
 * the write goes to a sibling temporary and is renamed over the target.
 *
 * MISSING PARENTS ARE CREATED, unlike [openFileForWrite]. Stated here because it was not, and the two actuals
 * then disagreed about it: the JVM created them and Foundation did not, so a cache laid out in directories
 * (a Maven-layout one, say) wrote nothing at all on iOS while working perfectly on the JVM.
 */
expect fun writeFileAtomically(path: String, bytes: ByteArray): Boolean

expect fun deleteFile(path: String): Boolean
