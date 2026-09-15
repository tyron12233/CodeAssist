package dev.ide.store.impl.platform

import platform.Foundation.NSDate
import platform.Foundation.NSRecursiveLock
import platform.Foundation.timeIntervalSince1970

/** `NSRecursiveLock`, matching what the JVM monitor it replaces allows: a guarded block may re-enter. */
actual class StoreLock actual constructor() {
    private val lock = NSRecursiveLock()

    actual fun <T> withLock(block: () -> T): T {
        lock.lock()
        return try {
            block()
        } finally {
            lock.unlock()
        }
    }
}

actual fun nowMillis(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()
