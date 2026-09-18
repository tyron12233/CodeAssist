@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package dev.ide.platform

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.BooleanVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.create
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeToFile

/**
 * Foundation, not POSIX, unlike [openFile].
 *
 * `openFile` is four calls that POSIX expresses directly. These are stat, a directory walk and an atomic
 * write, where POSIX means `struct stat`, `opendir`/`readdir`/`closedir` and a rename dance, and
 * `NSFileManager` is one call each with the same semantics. `NSData.writeToFile(atomically = true)` is
 * exactly the sibling-temporary-and-rename this needs.
 */
actual fun fileInfo(path: String): FileInfo? = memScoped {
    val manager = NSFileManager.defaultManager
    val isDirectory = alloc<BooleanVar>()
    if (!manager.fileExistsAtPath(path, isDirectory.ptr)) return null
    val attributes = manager.attributesOfItemAtPath(path, null)
    val size = (attributes?.get("NSFileSize") as? Number)?.toLong() ?: 0L
    // NSFileModificationDate is an NSDate; its epoch seconds are what every other platform reports in ms.
    val modified = (attributes?.get("NSFileModificationDate") as? NSDate)
        ?.timeIntervalSince1970
        ?.times(1000)
        ?.toLong()
        ?: 0L
    FileInfo(
        path = path,
        isDirectory = isDirectory.value,
        size = if (isDirectory.value) 0L else size,
        lastModified = modified,
    )
}

actual fun listDirectory(path: String): List<String> {
    val entries = NSFileManager.defaultManager.contentsOfDirectoryAtPath(path, null) ?: return emptyList()
    val separator = if (path.endsWith("/")) "" else "/"
    return entries.mapNotNull { it as? String }.map { "$path$separator$it" }.sorted()
}

actual fun readFile(path: String): ByteArray? {
    val source = openFile(path) ?: return null
    return try {
        source.read(0, source.size.toInt())
    } catch (_: Throwable) {
        null
    } finally {
        source.close()
    }
}

actual fun createDirectories(path: String): Boolean =
    NSFileManager.defaultManager.createDirectoryAtPath(path, true, null, null)

actual fun writeFileAtomically(path: String, bytes: ByteArray): Boolean {
    // `writeToFile` does not create intermediate directories; the JVM actual does, and the contract says so.
    val parent = path.trimEnd('/').substringBeforeLast('/', "")
    if (parent.isNotEmpty()) createDirectories(parent)
    val data = if (bytes.isEmpty()) {
        NSData()
    } else {
        bytes.usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = bytes.size.convert())
        }
    }
    // `atomically` is the whole point: Foundation writes a sibling temporary and renames it over the target.
    return data.writeToFile(path, atomically = true)
}

actual fun deleteFile(path: String): Boolean =
    NSFileManager.defaultManager.removeItemAtPath(path, null)
