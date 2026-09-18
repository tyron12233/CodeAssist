// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.platform.impl

/**
 * The single Workspace-level model lock: read actions wrap any code that reads the model or a DOM, write
 * actions wrap structural mutations (modifiable-model commit, VFS event publication) and must be short.
 *
 * Reentrant: a thread already holding the write lock may take read (and nested write) actions, and nested
 * read actions are fine. UPGRADING — taking a write action while holding a read action — is rejected up
 * front on any platform whose underlying lock would deadlock on it.
 *
 * The action blocks are synchronous by contract, so a single action never hands the lock to another thread
 * mid-body.
 *
 * Expect/actual because the JVM has exactly this primitive and Kotlin/Native has none. What the two actuals
 * do NOT share is read PARALLELISM: see the iOS one for why serializing readers costs nothing there.
 */
expect class ModelReadWriteLock() {
    /** True if the current thread holds the write lock (useful for assertions in mutating code). */
    val isWriteLockedByCurrentThread: Boolean

    /** Reentrant read holds for the current thread. */
    val readHoldCount: Int

    fun <T> read(block: () -> T): T

    fun <T> write(block: () -> T): T
}
