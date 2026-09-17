package dev.ide.platform

/**
 * Mutual exclusion, because common Kotlin has no `synchronized`.
 *
 * It lives in the lowest module in the build rather than beside the first thing that needed one: a class-file
 * reader, a block cache and the log hub all want the same three lines, and two implementations of a lock is
 * two things to get wrong. It is reentrant, matching the JVM monitor it replaces, so a guarded section may
 * call another.
 */
expect class Lock() {
    fun <T> withLock(block: () -> T): T
}
