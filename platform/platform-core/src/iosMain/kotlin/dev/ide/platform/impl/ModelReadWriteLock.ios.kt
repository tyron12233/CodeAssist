// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.platform.impl

import dev.ide.platform.Lock

/**
 * One reentrant MUTEX standing in for a read/write lock: readers and writers take the same lock.
 *
 * Kotlin/Native has no reentrant read/write lock, and reentrancy is the part that cannot be given up — the
 * model's own code takes nested read actions, and a commit takes read actions inside its write action.
 * Building a reentrant RW lock over `pthread_rwlock_t` (which is not reentrant) means tracking per-thread
 * hold counts by hand: a lot of subtle code to buy read parallelism.
 *
 * Read parallelism is exactly what this gives up, and on this host it is not a loss. The model is read by
 * the editor's analysis, which runs on one engine thread; there is no second thread to read in parallel
 * WITH. Should that change, the thing to build is the hold-count RW lock, not a second [Lock].
 *
 * Because there is one lock, a write inside a read cannot deadlock and is therefore not rejected. That is a
 * real behaviour difference from the JVM actual, in the permissive direction: code refused there runs here.
 * The refusal guards against a JVM deadlock, so the strict actual is the one that has to hold, and it does.
 *
 * The depth counters are plain `var`s read and written under the lock, except by the two properties, which
 * are advisory (assertions in mutating code) and would need an atomic only to report a number that is
 * meaningless off the holding thread anyway.
 */
actual class ModelReadWriteLock {
    private val lock = Lock()

    private var writeDepth = 0
    private var readDepth = 0

    actual val isWriteLockedByCurrentThread: Boolean get() = writeDepth > 0

    actual val readHoldCount: Int get() = readDepth

    actual fun <T> read(block: () -> T): T = lock.withLock {
        readDepth++
        try {
            block()
        } finally {
            readDepth--
        }
    }

    actual fun <T> write(block: () -> T): T = lock.withLock {
        writeDepth++
        try {
            block()
        } finally {
            writeDepth--
        }
    }
}
