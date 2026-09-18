package dev.ide.lang.kotlin.symbols

/**
 * Whether [e] is the RUN running out of stack or heap, rather than the analysed code being wrong.
 *
 * The distinction matters where a failure would otherwise be memoized: a body that legitimately fails to
 * type caches a null, while a transient overflow must propagate and leave the memo untouched, or one deep
 * chain leaves every declaration it unwound through permanently untyped for the rest of the session.
 *
 * Expect/actual because the two platforms do not agree that this is an exception at all: the JVM has
 * `VirtualMachineError`, and Kotlin/Native turns a stack overflow into a process-level trap that no `catch`
 * ever sees.
 */
internal expect fun isResourceExhaustion(e: Throwable): Boolean
