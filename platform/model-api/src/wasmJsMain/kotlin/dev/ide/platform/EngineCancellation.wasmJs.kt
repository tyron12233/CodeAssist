// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.platform

private var current: CancelToken? = null

internal actual fun currentCancelToken(): CancelToken? = current

internal actual suspend fun <T> withCancelToken(token: CancelToken, block: suspend () -> T): T {
    val previous = current
    current = token
    try {
        return block()
    } finally {
        current = previous
    }
}
