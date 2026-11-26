// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.platform

actual fun writeTempFile(name: String, bytes: ByteArray): String? {
    val path = "/tmp/$name.bin"
    return if (MemoryFileSystem.write(path, bytes)) path else null
}
