package dev.ide.lang.jdt.analysis

import dev.ide.analysis.Codes
import org.eclipse.jdt.core.compiler.IProblem

/**
 * Maps ecj's [IProblem] ids onto stable, machine-readable diagnostic codes: the join key a
 * [dev.ide.analysis.QuickFixProvider] matches on. ecj reports ~1000 precise, locale-independent problem
 * ids; previously every diagnostic flowed through as `(range, severity, message)` with the id discarded,
 * and the code was reconstructed by regex over the (localized) message, which is lossy and locale-fragile. Carrying
 * the id through as a code lets a fix attach to exactly the problem it handles, regardless of the JVM locale.
 *
 * Only the families that have a fix (or a neutral meaning) are named; everything else stays `null` so the
 * caller's message-heuristic fallback still applies. The undefined-type family maps onto the neutral
 * [Codes.UNRESOLVED_REFERENCE] so the existing "Add import" fix keeps firing.
 */
object JavaProblemCodes {
    const val UNUSED_LOCAL = "java.unusedLocal"
    const val UNUSED_PRIVATE = "java.unusedPrivate"
    const val UNHANDLED_EXCEPTION = "java.unhandledException"
    const val UNDEFINED_METHOD = "java.undefinedMethod"

    /** Codes whose diagnostics render muted (the editor greys an UNUSED-tagged squiggle). */
    val UNUSED_CODES = setOf(UNUSED_LOCAL, UNUSED_PRIVATE)

    /** The stable code for an ecj problem [id], or null when there is no fix/neutral meaning for it. */
    fun codeFor(id: Int): String? = when (id) {
        IProblem.LocalVariableIsNeverUsed, IProblem.ArgumentIsNeverUsed -> UNUSED_LOCAL
        IProblem.UnusedPrivateMethod, IProblem.UnusedPrivateField,
        IProblem.UnusedPrivateType, IProblem.UnusedPrivateConstructor -> UNUSED_PRIVATE
        IProblem.UnhandledException,
        IProblem.UnhandledExceptionInDefaultConstructor -> UNHANDLED_EXCEPTION
        IProblem.UndefinedMethod -> UNDEFINED_METHOD
        IProblem.UndefinedType, IProblem.ImportNotFound -> Codes.UNRESOLVED_REFERENCE
        else -> null
    }
}
