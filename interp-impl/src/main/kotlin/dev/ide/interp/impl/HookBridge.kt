package dev.ide.interp.impl

import dev.ide.interp.HookDecision as CoreDecision
import dev.ide.interp.InterpreterHooks
import dev.ide.interp.PreviewSandboxPolicy
import dev.ide.interp.SandboxFinding
import dev.ide.interp.api.HookDecision
import dev.ide.interp.api.InterpretHooks
import dev.ide.interp.api.InterpretProblem
import dev.ide.lang.kotlin.interp.DispatchKind
import dev.ide.lang.kotlin.interp.RNode
import dev.ide.lang.kotlin.interp.ResolvedCallable

/**
 * Adapts a plugin's [InterpretHooks] onto [InterpreterHooks].
 *
 * The adaptation exists for one reason: `InterpreterHooks.beforeCall` takes an `RNode.Call`, and `RNode` is
 * the resolver-to-interpreter contract, which must not become plugin ABI (see docs/plugin-interpreter.md).
 * So the published seam is phrased over the two things a policy actually needs from that node, the declaring
 * class and the member's name, and this pulls them out. The extraction matches what
 * [PreviewSandboxPolicy] does with the same node, which is the proof that those two are enough.
 */
internal class PluginHooks(private val delegate: InterpretHooks) : InterpreterHooks {

    override fun beforeCall(call: RNode.Call, receiver: Any?, args: List<Any?>): CoreDecision {
        val ownerFqn = (call.callee as? ResolvedCallable.Library)?.ownerFqn
        // A constructor's "member" is the type itself (`FileInputStream(...)`), reported as `<init>` so a
        // policy can match construction without special-casing every type name.
        val member = if (call.dispatch == DispatchKind.CONSTRUCTOR) "<init>" else call.callee.displayName
        return delegate.beforeCall(ownerFqn, member, receiver, args).core()
    }

    override fun beforePropertyRead(ownerFqn: String?, name: String, receiver: Any?): CoreDecision =
        delegate.beforePropertyRead(ownerFqn, name, receiver).core()

    override fun beforePropertyWrite(name: String, receiver: Any?): CoreDecision =
        delegate.beforePropertyWrite(name, receiver).core()

    override fun beforeClassInit(fqn: String): Boolean = delegate.beforeClassInit(fqn)
}

private fun HookDecision.core(): CoreDecision = when (this) {
    is HookDecision.Proceed -> CoreDecision.Proceed
    is HookDecision.Replace -> CoreDecision.Replace(value)
    is HookDecision.Deny -> CoreDecision.Deny(reason)
}

/**
 * Both hook seams at once, in the order that makes the sandbox unavoidable: the [sandbox] decides first, and
 * a plugin's own [plugin] hooks see only what the sandbox allowed.
 *
 * That order is the point. A plugin cannot widen the sandbox by answering [HookDecision.Proceed] to something
 * the user's settings restrict, which it could if the two were consulted the other way around, and it can
 * still refuse or stand in for anything the sandbox was fine with.
 */
internal class ChainedHooks(
    private val sandbox: InterpreterHooks,
    private val plugin: InterpreterHooks,
) : InterpreterHooks {

    override fun beforeCall(call: RNode.Call, receiver: Any?, args: List<Any?>): CoreDecision =
        sandbox.beforeCall(call, receiver, args).let {
            if (it is CoreDecision.Proceed) plugin.beforeCall(call, receiver, args) else it
        }

    override fun beforePropertyRead(ownerFqn: String?, name: String, receiver: Any?): CoreDecision =
        sandbox.beforePropertyRead(ownerFqn, name, receiver).let {
            if (it is CoreDecision.Proceed) plugin.beforePropertyRead(ownerFqn, name, receiver) else it
        }

    override fun beforePropertyWrite(name: String, receiver: Any?): CoreDecision =
        sandbox.beforePropertyWrite(name, receiver).let {
            if (it is CoreDecision.Proceed) plugin.beforePropertyWrite(name, receiver) else it
        }

    override fun beforeClassInit(fqn: String): Boolean =
        sandbox.beforeClassInit(fqn) && plugin.beforeClassInit(fqn)
}

/**
 * The hooks a session runs with, and where its sandbox findings come from.
 *
 * [policy] is null only when the session restricts nothing, in which case there are no findings to drain.
 */
internal class SessionHooks(val hooks: InterpreterHooks?, private val policy: PreviewSandboxPolicy?) {

    fun findings(): List<InterpretProblem> =
        policy?.findings().orEmpty().map { it.problem() }

    fun clearFindings() = policy?.clearFindings()

    companion object {

        /** Compose the sandbox for [categories] (empty = restrict nothing) with the plugin's own [hooks]. */
        fun of(categories: Set<String>, strict: Boolean, hooks: InterpretHooks?): SessionHooks {
            val policy = if (categories.isEmpty()) null
            else PreviewSandboxPolicy.fromIds(categories, stubOnDeny = !strict)
            val plugin = hooks?.let { PluginHooks(it) }
            val chained = when {
                policy == null -> plugin
                plugin == null -> policy
                else -> ChainedHooks(policy, plugin)
            }
            return SessionHooks(chained, policy)
        }
    }
}

private fun SandboxFinding.problem(): InterpretProblem = InterpretProblem(
    severity = InterpretProblem.Severity.WARNING,
    message = "the sandbox blocked ${category.label}",
    detail = member,
)
