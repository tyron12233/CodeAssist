// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.kotlin.classfile

/**
 * No synchronous DEFLATE in the browser (`DecompressionStream` is asynchronous), so there is no platform
 * decoder to hand the stream to; the portable [Inflate] decodes it itself, which is the contract for null.
 */
internal actual fun platformInflate(input: ByteArray, expectedSize: Int): ByteArray? = null
