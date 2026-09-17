package dev.ide.index.impl

/**
 * Kotlin/Native has no catchable equivalent: a stack overflow terminates the process rather than unwinding,
 * and an allocation failure raises `OutOfMemoryError`, which is an `Error` like any other. So nothing is
 * rethrown here, and a corrupt payload is reported exactly as on the JVM.
 */
internal actual fun rethrowIfFatal(t: Throwable) {
}
