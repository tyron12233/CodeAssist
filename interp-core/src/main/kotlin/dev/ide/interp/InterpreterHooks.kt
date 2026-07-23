package dev.ide.interp

import dev.ide.lang.kotlin.interp.RNode

/**
 * The host's veto/rewrite seam over every point where interpreted code escapes into the real system. The
 * interpreter itself only walks the lowered tree; the moments it reaches OUT — a library call through the
 * [Dispatcher], a reflective property read/write, materializing a singleton (which runs a class's static
 * initializer) — are exactly the moments a host may want to mediate (the Compose preview's sandbox: file
 * access, network, Android system calls). Each hook runs BEFORE the underlying operation and decides it.
 *
 * This sees the boundary between interpreted code and the FIRST library call only: what an allowed library
 * call does internally is invisible (like the console run sandbox, which mediates the program's calls at the
 * bytecode VM's bridge). A curated call-boundary policy, not a hardened sandbox — same caveat as the run
 * sandbox's `Guards`.
 *
 * All hooks default to [HookDecision.Proceed]; a null hooks reference on the [Interpreter] costs nothing on
 * the hot path. Implementations run on the composition (UI) thread AND the suspend-bridge thread, so they
 * must be thread-safe and cheap (memoize per class+member — see [PreviewSandboxPolicy]).
 */
interface InterpreterHooks {
    /** Before every non-source call the interpreter hands to the [Dispatcher] — library/member/extension/
     *  constructor/function-object invokes. [receiver] is the evaluated dispatch (or extension) receiver,
     *  null for top-level/static/constructor; [args] are evaluated, in source order. The callee's owner and
     *  name ride on [call] (`call.callee`). */
    fun beforeCall(call: RNode.Call, receiver: Any?, args: List<Any?>): HookDecision = HookDecision.Proceed

    /** Before a reflective property read — an instance getter (`context.filesDir`), a static member
     *  (`System.out`), a top-level (`LocalTextStyle`) or extension property. [ownerFqn] is the declaring
     *  class/facade when the read is static (null otherwise); [receiver] the instance (null when static).
     *  Property reads are how an Android `Context` leaks, so a calls-only policy would miss them. */
    fun beforePropertyRead(ownerFqn: String?, name: String, receiver: Any?): HookDecision = HookDecision.Proceed

    /** Before a reflective property write (`receiver.name = value` on a non-source receiver). A [HookDecision.
     *  Replace] skips the write (its value is ignored — there is nothing to substitute). */
    fun beforePropertyWrite(name: String, receiver: Any?): HookDecision = HookDecision.Proceed

    /** Before the interpreter loads [fqn] WITH static initialization (materializing an `object`/`Companion`/
     *  enum holder or a top-level-property facade) — running `<clinit>` is arbitrary code execution at class
     *  granularity. Returning false makes the class behave as not loadable (the interpreter's existing honest
     *  "cannot load …" boundary), so there is no Replace shape here. */
    fun beforeClassInit(fqn: String): Boolean = true
}

/** The outcome of an [InterpreterHooks] check, decided before the underlying operation runs. */
sealed class HookDecision {
    /** Perform the operation normally. */
    object Proceed : HookDecision()

    /** Skip the operation and use [value] as its result — the stub path that keeps a gap-tolerant preview
     *  rendering (the policy records a finding; the operation never happens). */
    class Replace(val value: Any?) : HookDecision()

    /** Refuse the operation: [InterpreterSecurityException] with [reason] — the fail-loud path (lessons,
     *  console-style runs). */
    class Deny(val reason: String) : HookDecision()
}

/** A hook refused an operation ([HookDecision.Deny]). Subtypes [InterpreterException] so existing boundary
 *  handling (error views, partial-render capture) treats it like any other interpreter failure, while a host
 *  can still tell a sandbox refusal from a genuine gap. */
class InterpreterSecurityException(message: String) : InterpreterException(message)
