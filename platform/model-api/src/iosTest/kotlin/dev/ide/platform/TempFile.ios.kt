@file:OptIn(ExperimentalForeignApi::class)

package dev.ide.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSTemporaryDirectory
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite

actual fun writeTempFile(name: String, bytes: ByteArray): String? {
    val path = NSTemporaryDirectory() + name + ".bin"
    val file = fopen(path, "wb") ?: return null
    if (bytes.isNotEmpty()) {
        bytes.usePinned { pinned ->
            fwrite(pinned.addressOf(0), 1.convert(), bytes.size.convert(), file)
        }
    }
    fclose(file)
    return path
}
