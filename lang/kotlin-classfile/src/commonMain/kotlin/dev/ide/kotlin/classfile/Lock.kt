package dev.ide.kotlin.classfile

/**
 * Mutual exclusion, because common Kotlin has no `synchronized`.
 *
 * Here rather than somewhere more general for the same reason [openFile] is here: this module is where the
 * platform seams live, and the classpath reader above it is the only thing in the build that needs one. It
 * is reentrant, matching the JVM monitor it replaces, so a guarded section may call another.
 */
expect class Lock() {
    fun <T> withLock(block: () -> T): T
}
