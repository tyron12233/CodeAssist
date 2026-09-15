@file:OptIn(ExperimentalForeignApi::class)

package dev.ide.ios

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.usePinned
import platform.Foundation.NSDate
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSData
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSFileType
import platform.Foundation.NSFileTypeDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeToFile

/**
 * Filesystem access for the iOS host, over Foundation.
 *
 * Everything lives under the app's own Documents container. iOS sandboxes an app to that container plus
 * whatever the user hands it through `UIDocumentPicker` (which needs security-scoped bookmarks to survive a
 * relaunch), so unlike the Android host there is no "open any folder on the device" to port. Documents is
 * also what iTunes/Finder file sharing and the Files app expose, so a project created here is reachable from
 * a Mac without any extra plumbing.
 */
internal object IosFiles {
    private val fm: NSFileManager get() = NSFileManager.defaultManager

    /** `~/Documents` inside the app container. */
    fun documentsDir(): String =
        NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true)
            .filterIsInstance<String>().first()

    /**
     * `~/Library/Application Support` inside the app container, created on first use.
     *
     * Where the store keeps its working state: the offline copy of the feed, the cached screenshots, and
     * the record of what this device installed. Not Documents, because none of it is the user's to see —
     * Documents is what the Files app and Finder sharing expose. Not Caches either: iOS may purge that
     * under storage pressure, and the install record is what turns an installed project's button into an
     * Open rather than a second download.
     */
    fun supportDir(): String {
        val dir = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true)
            .filterIsInstance<String>().first()
        if (!exists(dir)) mkdirs(dir)
        return dir
    }

    fun exists(path: String): Boolean = fm.fileExistsAtPath(path)

    /** The file's bytes, or null when it is not readable. Used to decode an image for the UI. */
    fun readBytes(path: String): ByteArray? = NSData.dataWithContentsOfFile(path)?.toByteArray()

    /** Write [bytes] to [path], creating nothing above it. */
    fun writeBytes(path: String, bytes: ByteArray): Boolean = bytes.toNSData().writeToFile(path, true)

    /** The file's size in bytes, or 0 when there is no such file. */
    fun size(path: String): Long {
        val attributes = fm.attributesOfItemAtPath(path, null) ?: return 0
        return (attributes[NSFileSize] as? NSNumber)?.longLongValue ?: 0
    }

    fun isDirectory(path: String): Boolean =
        fm.attributesOfItemAtPath(path, null)?.get(NSFileType) == NSFileTypeDirectory

    /** Entry NAMES directly under [path] (not paths), empty when it is not a readable directory. */
    fun list(path: String): List<String> =
        fm.contentsOfDirectoryAtPath(path, null)?.filterIsInstance<String>().orEmpty()

    fun readText(path: String): String =
        NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null) ?: ""

    fun writeText(path: String, text: String): Boolean {
        // The parent may not exist yet (a scaffolded nested source dir), and writeToFile will not create it.
        parentOf(path)?.let { if (!exists(it)) mkdirs(it) }
        return (text as NSString).writeToFile(path, atomically = true, encoding = NSUTF8StringEncoding, error = null)
    }

    fun mkdirs(path: String): Boolean =
        fm.createDirectoryAtPath(path, withIntermediateDirectories = true, attributes = null, error = null)

    fun delete(path: String): Boolean = fm.removeItemAtPath(path, error = null)

    fun move(from: String, to: String): Boolean = fm.moveItemAtPath(from, toPath = to, error = null)

    fun copy(from: String, to: String): Boolean = fm.copyItemAtPath(from, toPath = to, error = null)

    /** Epoch-ms of the last modification, 0 when unknown. Orders the project picker's "most recent" list. */
    fun modifiedMs(path: String): Long {
        val date = fm.attributesOfItemAtPath(path, null)?.get(NSFileModificationDate) as? NSDate ?: return 0L
        return (date.timeIntervalSince1970 * 1000.0).toLong()
    }

    /** The containing directory of [path], or null at the root. Path arithmetic, no filesystem access. */
    fun parentOf(path: String): String? {
        val trimmed = path.trimEnd('/')
        val cut = trimmed.lastIndexOf('/')
        return if (cut <= 0) null else trimmed.substring(0, cut)
    }

    fun nameOf(path: String): String = path.trimEnd('/').substringAfterLast('/')

    fun join(dir: String, name: String): String = "${dir.trimEnd('/')}/$name"

    /** A filename-safe form of [name], so a project title with spaces or slashes still makes a valid folder. */
    fun sanitize(name: String): String =
        name.trim().map { if (it.isLetterOrDigit() || it == '-' || it == '_' || it == '.') it else '_' }
            .joinToString("").trim('_', '.').ifEmpty { "project" }

}

/** `NSData` to `ByteArray`. Foundation reads a file as the former and Skia decodes the latter. */
@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    val out = ByteArray(size)
    out.usePinned { pinned -> platform.posix.memcpy(pinned.addressOf(0), bytes, length) }
    return out
}

/** `ByteArray` to `NSData`, the inverse of the read above. */
@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toNSData(): NSData = memScoped {
    if (isEmpty()) return NSData()
    NSData.create(bytes = allocArrayOf(this@toNSData), length = size.toULong())
}
