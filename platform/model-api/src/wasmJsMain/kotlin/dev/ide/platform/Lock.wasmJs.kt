// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.platform

/** The browser runs the IDE on one thread, so there is nothing to exclude: a lock is the block itself. */
actual class Lock {
    actual fun <T> withLock(block: () -> T): T = block()
}
