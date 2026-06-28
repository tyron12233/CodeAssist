package dev.ide.platform.impl

import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * The single Workspace-level model lock: many concurrent readers, exactly one writer, the writer
 * excluding all readers. Read actions wrap any code that reads the model or a DOM; write actions
 * wrap structural mutations (modifiable-model commit, VFS event publication) and must be short.
 *
 * Reentrancy follows [ReentrantReadWriteLock]: a thread already holding the write lock may take read
 * (and nested write) actions, and nested read actions are fine. Upgrading (taking a write action
 * while holding a read action) is rejected up front, because the underlying lock would deadlock on
 * it. A write action cannot be started inside a read action.
 *
 * The action blocks are synchronous by contract, so a single action never hands the lock to another
 * thread mid-body. Used directly by the project model store (a commit wraps its mutation in [write]).
 */
class ModelReadWriteLock {
    private val lock = ReentrantReadWriteLock()

    /** True if the current thread holds the write lock (useful for assertions in mutating code). */
    val isWriteLockedByCurrentThread: Boolean get() = lock.isWriteLockedByCurrentThread

    /** Reentrant read holds for the current thread. */
    val readHoldCount: Int get() = lock.readHoldCount

    fun <T> read(block: () -> T): T {
        val r = lock.readLock()
        r.lock()
        try {
            return block()
        } finally {
            r.unlock()
        }
    }

    fun <T> write(block: () -> T): T {
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
