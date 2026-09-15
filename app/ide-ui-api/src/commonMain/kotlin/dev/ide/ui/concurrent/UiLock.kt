package dev.ide.ui.concurrent

/**
 * A mutual-exclusion lock for the few pieces of shared mutable state the UI layer keeps outside a
 * composition: the process-global registries a plugin can add to, and the render-path memo caches.
 *
 * `kotlin.synchronized` is a JVM intrinsic and does not exist in common code, so the guard is a seam:
 * the JVM hosts resolve it straight back to `synchronized`, keeping their locking behaviour identical,
 * and iOS resolves it to an `NSRecursiveLock`. Re-entrant on every host, matching what `synchronized`
 * guarantees, since [withLock] blocks here can call back into the same guarded object.
 */
expect class UiLock() {
    fun <T> withLock(block: () -> T): T
}
