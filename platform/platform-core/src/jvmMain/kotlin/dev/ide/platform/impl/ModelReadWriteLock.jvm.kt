// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.platform.impl

import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * [ReentrantReadWriteLock]: many concurrent readers, exactly one writer, the writer excluding all readers.
 *
 * Upgrading is rejected rather than attempted, because this lock deadlocks on it — a thread holding the read
 * lock and asking for the write lock waits for itself.
 */
actual class ModelReadWriteLock {
    private val lock = ReentrantReadWriteLock()

    actual val isWriteLockedByCurrentThread: Boolean get() = lock.isWriteLockedByCurrentThread

    actual val readHoldCount: Int get() = lock.readHoldCount

    actual fun <T> read(block: () -> T): T {
        val r = lock.readLock()
        r.lock()
        try {
            return block()
        } finally {
            r.unlock()
        }
    }

    actual fun <T> write(block: () -> T): T {
        require(lock.readHoldCount == 0 || lock.isWriteLockedByCurrentThread) {
            "Cannot start a write action inside a read action (lock upgrade would deadlock)"
        }
        val w = lock.writeLock()
        w.lock()
        try {
            return block()
        } finally {
            w.unlock()
        }
    }
}
