package dev.ide.interp.api

/**
 * Mediates every escape from interpreted code into real code: a call, a property read or write, the
 * initialization of a singleton. It is the seam the IDE's own preview sandbox is built on, and a plugin can
 * use it for the same purposes: refusing what a preview has no business doing, or standing in for something
 * a preview cannot have.
 *
 * The second use is the more interesting one for a framework plugin. [HookDecision.Replace] answers a call
 * with a value instead of letting it happen, which is how a preview hands the user's code a stub asset loader,
 * a fixed clock, or a canvas of its own rather than the real one.
 *
 * Called on the interpreting thread, in the middle of a run, for every boundary crossing. Keep the body cheap
 * and allocation-free on the common path; memoize any classification by owner and member, since the same
 * members are re-checked on every pass.
 *
 * This is a call-boundary guard, not a hardened sandbox: what an allowed call does internally is invisible to
 * it. Do not present it to a user as a guarantee.
 */
interface InterpretHooks {

    /**
     * A call into real code is about to happen. [ownerFqn] is the declaring class as the interpreter resolved
     * it (null when it could not name one), [member] the member's own name, [receiver] the instance for an
     * instance call and null for a static one, and [args] the arguments already evaluated.
     */
    fun beforeCall(
        ownerFqn: String?,
        member: String,
        receiver: Any?,
        args: List<Any?>,
    ): HookDecision = HookDecision.Proceed

    /**
     * A property of real code is about to be read. This is not a detail to skip: a property read is how an
     * Android `Context` or a `System` stream is reached, so a calls-only policy has a hole in it.
     */
    fun beforePropertyRead(ownerFqn: String?, name: String, receiver: Any?): HookDecision =
        HookDecision.Proceed

    /**
     * A property of real code is about to be written. There is no owner here: a write always has a receiver,
     * and the interpreter reaches it without naming a declaring class.
     *
     * [HookDecision.Replace] skips the write; its value is ignored, since there is nothing to substitute.
     */
    fun beforePropertyWrite(name: String, receiver: Any?): HookDecision = HookDecision.Proceed

    /**
     * A real class is about to be initialized (its static initializer run). Returning false skips the
     * initialization, leaving the class untouched, for a singleton whose construction a preview must not
     * trigger.
     */
    fun beforeClassInit(fqn: String): Boolean = true
}

/** What an [InterpretHooks] callback decides about the operation it was asked about. */
sealed class HookDecision {

    /** Let it happen. */
    object Proceed : HookDecision()

    /** Do not let it happen; answer with [value] instead. The interpreted code cannot tell the difference. */
    class Replace(val value: Any?) : HookDecision()

    /**
     * Refuse it: the run fails with an [InterpretException] carrying [reason]. To refuse without failing
     * (the shape a preview usually wants, where one blocked operation costs that operation), answer
     * [Replace] with a harmless value and record the reason instead.
     */
    class Deny(val reason: String) : HookDecision()
}

/**
 * Something that went wrong during a run without stopping it: a statement that could not be interpreted and
 * was skipped, or an operation the sandbox refused.
 *
 * [message] is phrased for display. [detail] names the member or statement it concerns, for a plugin that
 * wants to show where.
 */
class InterpretProblem(
    val severity: Severity,
    val message: String,
    val detail: String? = null,
) {
    enum class Severity {
        /** The run continued, but something was skipped or refused; the result is incomplete. */
        WARNING,

        /** The run continued, but this part of it is wrong rather than merely missing. */
        ERROR,
    }

    override fun toString(): String = if (detail == null) message else "$message ($detail)"
}
