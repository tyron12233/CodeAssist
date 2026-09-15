package dev.ide.store.impl.platform

/**
 * A mutual-exclusion lock for the session state the account service serialises refreshes with.
 *
 * `kotlin.synchronized` is a JVM intrinsic and does not exist in common code, so the guard is a seam,
 * exactly as [dev.ide.ui.concurrent.UiLock] is for the UI layer: the JVM hosts resolve it straight back
 * to `synchronized` and keep their locking behaviour identical, iOS to an `NSRecursiveLock`. Re-entrant
 * on both, because a guarded block here can call back into the same object.
 */
expect class StoreLock() {
    fun <T> withLock(block: () -> T): T
}

/** Epoch milliseconds. Public alongside [StoreFs]: the layer above times its feed cache with it. */
expect fun nowMillis(): Long
