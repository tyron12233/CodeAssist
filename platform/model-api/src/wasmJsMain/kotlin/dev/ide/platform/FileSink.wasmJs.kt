// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.platform

actual fun openFileForWrite(path: String): FileSink? {
    // Fail at open, like a real file would, when the path cannot be written: an absent parent directory is a
    // null here (the contract), not something to create. The bytes land on flush/close.
    val parent = MemoryFileSystem.normalize(path).substringBeforeLast('/').ifEmpty { "/" }
    if (MemoryFileSystem.info(parent)?.isDirectory != true) return null
    if (MemoryFileSystem.info(path)?.isDirectory == true) return null
    if (!MemoryFileSystem.write(path, ByteArray(0))) return null
    return object : FileSink {
        private var buffer = ByteArray(256)
        private var used = 0

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            if (used + length > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, used + length))
            bytes.copyInto(buffer, used, offset, offset + length)
            used += length
        }

        override fun flush() {
            MemoryFileSystem.write(path, buffer.copyOf(used))
        }

        override fun close() = flush()
    }
}

actual fun moveFile(from: String, to: String): Boolean = MemoryFileSystem.move(from, to)
