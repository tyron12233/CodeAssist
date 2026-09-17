// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.vfs

import dev.ide.platform.ContentHash

/**
 * A file, as everything above the VFS sees one.
 *
 * Lives here rather than in `:vfs-api` because it is the only part of that API with nothing platform-
 * specific behind it, and because it appears in a [dev.ide.lang.resolve.Symbol]'s origin: a symbol model
 * that cannot be named from common code cannot be built from common code. The rest of `:vfs-api` (the
 * file system, the watch, the event stream) stays where it is, since its message bus is typed by
 * `java.lang.Class`.
 */
interface VirtualFile {
    val path: String
    val name: String
    val isDirectory: Boolean
    val exists: Boolean
    val length: Long

    fun parent(): VirtualFile?
    fun children(): List<VirtualFile>

    /** Cheap, cached digest of the file's bytes; used as a cache key by builds and analysis. */
    fun contentHash(): ContentHash

    fun readBytes(): ByteArray
    fun readText(): CharSequence
}
