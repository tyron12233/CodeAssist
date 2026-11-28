// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.platform

actual fun openFile(path: String): FileSource? {
    val bytes = MemoryFileSystem.read(path) ?: return null
    return object : FileSource {
        override val size: Long = bytes.size.toLong()

        override fun read(offset: Long, length: Int): ByteArray {
            val start = offset.toInt()
            return bytes.copyOfRange(start, start + length)
        }

        override fun close() = Unit
    }
}
