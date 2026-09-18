@file:OptIn(ExperimentalForeignApi::class)

package dev.ide.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell

/**
 * The same four operations through POSIX, which iOS has and which needs no framework.
 *
 * `fopen` and friends rather than `NSFileHandle`: the seam is open, size, seek and read, and Foundation adds
 * an object graph and an error protocol around exactly those. This is the whole of the platform-specific
 * code in the module.
 */
actual fun openFile(path: String): FileSource? {
    val file = fopen(path, "rb") ?: return null
    fseek(file, 0, SEEK_END)
    val length = ftell(file)
    if (length < 0) {
        fclose(file)
        return null
    }
    return object : FileSource {
        override val size: Long = length

        override fun read(offset: Long, length: Int): ByteArray {
            val bytes = ByteArray(length)
            if (length == 0) return bytes
            fseek(file, offset.convert(), SEEK_SET)
            // `fread` writes into the array directly, so it has to be pinned: without that the GC is free to
            // move it while C is holding the address.
            bytes.usePinned { pinned ->
                fread(pinned.addressOf(0), 1.convert(), length.convert(), file)
            }
            return bytes
        }

        override fun close() {
            fclose(file)
        }
    }
}
