package dev.ide.ui.concurrent

/** Both JVM hosts keep the monitor they always used: `synchronized` on the lock object itself. */
actual class UiLock actual constructor() {
    actual fun <T> withLock(block: () -> T): T = synchronized(this) { block() }
}
