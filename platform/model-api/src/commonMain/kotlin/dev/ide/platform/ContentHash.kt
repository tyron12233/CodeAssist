// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.platform

import kotlin.jvm.JvmInline

/** A stable digest of some content (file bytes, a classpath, a set of task inputs). String-backed (hex/base64). */
@JvmInline
value class ContentHash(val value: String) {
    companion object {
        /** The one canonical content digest: SHA-256 of [bytes] as lowercase hex. Both the VFS
         *  ([dev.ide.vfs.VirtualFile.contentHash]) and the event spine hash through this, so a file's hash
         *  is identical however it is computed. */
        fun of(bytes: ByteArray): ContentHash = ContentHash(sha256Hex(bytes))

        /** Digest of [text]'s UTF-8 bytes — the bytes a UTF-8 `writeText` persists — so it equals [of] over
         *  the resulting file's bytes. */
        fun of(text: String): ContentHash = of(text.encodeToByteArray())
    }
}

/**
 * SHA-256 of [bytes] as lowercase hex.
 *
 * The one thing in this module with a platform behind it. Every other type here is data or an interface,
 * which is why the module can be common at all; a digest needs an implementation, and the JVM and Apple
 * each already ship one.
 *
 * It must produce the same string everywhere: the value is a cache key, and a build whose hashes disagree
 * across platforms would rebuild everything or, worse, reuse the wrong output.
 */
internal expect fun sha256Hex(bytes: ByteArray): String
