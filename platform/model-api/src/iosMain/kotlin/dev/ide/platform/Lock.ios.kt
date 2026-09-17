package dev.ide.platform

import platform.Foundation.NSRecursiveLock

/** `NSRecursiveLock`, not `NSLock`: the JVM monitor this stands in for is reentrant. */
actual class Lock {
    private val lock = NSRecursiveLock()

    actual fun <T> withLock(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}
