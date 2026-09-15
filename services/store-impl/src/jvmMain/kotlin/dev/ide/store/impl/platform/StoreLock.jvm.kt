package dev.ide.store.impl.platform

actual class StoreLock actual constructor() {
    actual fun <T> withLock(block: () -> T): T = synchronized(this) { block() }
}

actual fun nowMillis(): Long = System.currentTimeMillis()
