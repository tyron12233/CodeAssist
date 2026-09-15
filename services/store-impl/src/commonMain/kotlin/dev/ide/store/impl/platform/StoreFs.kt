package dev.ide.store.impl.platform

/**
 * The filesystem calls the store makes, as paths.
 *
 * Small on purpose: the store writes a cached feed, a downloaded archive and a media cache, and it unpacks
 * one zip. It does not need a virtual file system, and the framework's own (`:vfs-api`) is Kotlin/JVM, so
 * this is the seam instead. Paths are separated by `/` on every platform this runs on.
 *
 * Public because :store-bridge reaches it too: remembering where an install landed and writing the offline
 * feed cache are the same kind of call from the same stack, and a second seam for them would be a second
 * thing to keep in step.
 */
expect object StoreFs {

    /** True when something exists at [path], file or directory. */
    fun exists(path: String): Boolean

    fun isDirectory(path: String): Boolean

    fun isFile(path: String): Boolean

    /** The file's size in bytes, or 0 when it is not a readable file. */
    fun size(path: String): Long

    /** Create [path] and every missing directory above it. */
    fun mkdirs(path: String): Boolean

    /** Create the directory chain that would contain the file [path]. */
    fun mkdirsForFile(path: String)

    /** Delete a file or an empty directory. */
    fun delete(path: String): Boolean

    /** Delete a directory and everything under it. Succeeds when [path] does not exist. */
    fun deleteRecursively(path: String): Boolean

    /**
     * [path] with symlinks and `..` segments resolved, for the containment check the extractor makes
     * before it writes anything. Falls back to a lexically normalised path when the file does not exist
     * yet, which is the case the extractor asks about.
     */
    fun canonicalPath(path: String): String

    /** Rename [from] to [to] in one step; false when the platform cannot (a cross-device move). */
    fun rename(from: String, to: String): Boolean

    /** Copy a whole directory tree. Used only when [rename] could not. */
    fun copyRecursively(from: String, to: String): Boolean

    fun readBytes(path: String): ByteArray?

    fun writeBytes(path: String, bytes: ByteArray): Boolean

    fun readText(path: String): String?

    fun writeText(path: String, text: String): Boolean

    /** Names (not paths) of the entries directly under [path]. */
    fun list(path: String): List<String>

    /** A path in the platform's temporary directory that nothing else holds. Creates no file. */
    fun tempPath(prefix: String, suffix: String): String

    /** When [path] was last written, epoch milliseconds, or 0 when there is no such file. */
    fun modifiedMillis(path: String): Long
}

/** `parent/child`, with exactly one separator between them. */
fun joinPath(parent: String, child: String): String =
    if (parent.endsWith('/')) parent + child.removePrefix("/") else "$parent/${child.removePrefix("/")}"

/** Everything before the last separator, or null for a bare name. */
fun parentPath(path: String): String? =
    path.trimEnd('/').substringBeforeLast('/', "").takeIf { it.isNotEmpty() }
