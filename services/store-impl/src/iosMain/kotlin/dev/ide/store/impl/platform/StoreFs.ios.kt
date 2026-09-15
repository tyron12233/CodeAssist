package dev.ide.store.impl.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile
import platform.posix.realpath
import kotlinx.cinterop.BooleanVar
import kotlinx.cinterop.toKString

/**
 * `NSFileManager`, plus one `realpath` for the containment check.
 *
 * Everything here is a path, because that is what the store deals in: a cache directory it was handed, an
 * archive it downloaded, a project directory it unpacked. The app's own container is the only place any
 * of it can write (see IosBackend for why projects live under `Documents/Projects`).
 */
@OptIn(ExperimentalForeignApi::class)
actual object StoreFs {

    private val fm: NSFileManager get() = NSFileManager.defaultManager

    actual fun exists(path: String): Boolean = fm.fileExistsAtPath(path)

    actual fun isDirectory(path: String): Boolean = memScoped {
        val isDir = alloc<BooleanVar>()
        fm.fileExistsAtPath(path, isDirectory = isDir.ptr) && isDir.value
    }

    actual fun isFile(path: String): Boolean = memScoped {
        val isDir = alloc<BooleanVar>()
        fm.fileExistsAtPath(path, isDirectory = isDir.ptr) && !isDir.value
    }

    actual fun size(path: String): Long {
        val attributes = fm.attributesOfItemAtPath(path, error = null) ?: return 0
        return (attributes["NSFileSize"] as? platform.Foundation.NSNumber)?.longLongValue ?: 0
    }

    actual fun mkdirs(path: String): Boolean =
        isDirectory(path) ||
            fm.createDirectoryAtPath(path, withIntermediateDirectories = true, attributes = null, error = null)

    actual fun mkdirsForFile(path: String) {
        parentPath(path)?.let { mkdirs(it) }
    }

    actual fun delete(path: String): Boolean = fm.removeItemAtPath(path, error = null)

    actual fun deleteRecursively(path: String): Boolean =
        !exists(path) || fm.removeItemAtPath(path, error = null)

    /**
     * `realpath` when the path exists, and a lexical normalisation when it does not.
     *
     * The extractor asks about a path it has not created yet, which is the whole point — the containment
     * check has to happen before anything is written. So the fallback resolves the deepest existing
     * ancestor and folds the remaining `.`/`..` segments itself.
     */
    actual fun canonicalPath(path: String): String {
        resolve(path)?.let { return it }
        val normalized = normalize(path)
        val parent = parentPath(normalized) ?: return normalized
        val resolvedParent = canonicalPath(parent)
        return joinPath(resolvedParent, normalized.substringAfterLast('/'))
    }

    actual fun rename(from: String, to: String): Boolean =
        fm.moveItemAtPath(from, toPath = to, error = null)

    actual fun copyRecursively(from: String, to: String): Boolean =
        fm.copyItemAtPath(from, toPath = to, error = null)

    actual fun readBytes(path: String): ByteArray? =
        NSData.dataWithContentsOfFile(path)?.toByteArray()

    actual fun writeBytes(path: String, bytes: ByteArray): Boolean =
        bytes.toNSData().writeToFile(path, atomically = true)

    actual fun readText(path: String): String? =
        NSString.stringWithContentsOfFile(path, encoding = NSUTF8StringEncoding, error = null)

    actual fun writeText(path: String, text: String): Boolean =
        writeBytes(path, text.encodeToByteArray())

    actual fun list(path: String): List<String> =
        fm.contentsOfDirectoryAtPath(path, error = null)?.mapNotNull { it as? String }.orEmpty()

    actual fun modifiedMillis(path: String): Long {
        val attributes = fm.attributesOfItemAtPath(path, error = null) ?: return 0
        val date = attributes["NSFileModificationDate"] as? platform.Foundation.NSDate ?: return 0
        return (date.timeIntervalSince1970 * 1000.0).toLong()
    }

    actual fun tempPath(prefix: String, suffix: String): String {
        var candidate: String
        var n = 0
        do {
            candidate = joinPath(NSTemporaryDirectory(), "$prefix${nowMillis()}-${n++}$suffix")
        } while (exists(candidate))
        return candidate
    }

    /** The path with symlinks resolved, or null when it does not exist. */
    private fun resolve(path: String): String? = memScoped {
        realpath(path, null)?.let {
            val resolved = it.toKString()
            platform.posix.free(it)
            resolved
        }
    }

    /** Fold `.` and `..` segments without touching the filesystem. */
    private fun normalize(path: String): String {
        val absolute = path.startsWith('/')
        val out = ArrayList<String>()
        for (segment in path.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (out.isNotEmpty() && out.last() != "..") out.removeAt(out.size - 1) else out.add("..")
                else -> out.add(segment)
            }
        }
        return (if (absolute) "/" else "") + out.joinToString("/")
    }
}
