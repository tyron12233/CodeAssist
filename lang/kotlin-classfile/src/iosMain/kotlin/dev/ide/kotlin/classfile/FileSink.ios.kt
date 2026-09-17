@file:OptIn(ExperimentalForeignApi::class)

package dev.ide.kotlin.classfile

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.posix.fclose
import platform.posix.fflush
import platform.posix.fopen
import platform.posix.fwrite
import platform.posix.remove
import platform.posix.rename

/**
 * POSIX again, matching [openFile]: `fopen` buffers, so the many small writes a segment is built from do not
 * each become a syscall, and `rename` is the atomic replace every file system provides.
 */
actual fun openFileForWrite(path: String): FileSink? {
    val file = fopen(path, "wb") ?: return null
    return object : FileSink {
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            if (length <= 0) return
            // `fwrite` reads out of the array directly, so it has to be pinned: without that the GC is free
            // to move it while C is holding the address.
            bytes.usePinned { pinned ->
                fwrite(pinned.addressOf(offset), 1.convert(), length.convert(), file)
            }
        }

        override fun flush() {
            fflush(file)
        }

        override fun close() {
            fclose(file)
        }
    }
}

actual fun moveFile(from: String, to: String): Boolean {
    // POSIX rename replaces an existing target, except on the platforms where it refuses a non-empty one;
    // removing first costs nothing and makes the two platforms agree.
    remove(to)
    return rename(from, to) == 0
}
