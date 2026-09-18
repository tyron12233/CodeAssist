package dev.ide.lang.kotlin.symbols

/**
 * Nothing to recognise: Kotlin/Native does not deliver stack or heap exhaustion as a `Throwable` — a deep
 * recursion traps the process. So every exception that reaches here really is a body that failed to type.
 */
internal actual fun isResourceExhaustion(e: Throwable): Boolean = false
