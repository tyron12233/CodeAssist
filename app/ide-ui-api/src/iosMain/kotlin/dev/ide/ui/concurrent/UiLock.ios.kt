package dev.ide.ui.concurrent

import platform.Foundation.NSRecursiveLock

/** `NSRecursiveLock` rather than `NSLock`: a guarded block may re-enter the same lock, which is what the
 *  JVM monitor this replaces allows. */
actual class UiLock actual constructor() {
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
