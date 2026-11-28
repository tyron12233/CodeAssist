// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.platform

actual fun fileInfo(path: String): FileInfo? = MemoryFileSystem.info(path)

actual fun listDirectory(path: String): List<String> = MemoryFileSystem.list(path)

actual fun readFile(path: String): ByteArray? = MemoryFileSystem.read(path)

actual fun createDirectories(path: String): Boolean = MemoryFileSystem.mkdirs(path)

/** A single-threaded in-memory replace is already atomic: nobody can observe the half-written state. */
actual fun writeFileAtomically(path: String, bytes: ByteArray): Boolean = MemoryFileSystem.write(path, bytes)

actual fun deleteFile(path: String): Boolean = MemoryFileSystem.delete(path)
