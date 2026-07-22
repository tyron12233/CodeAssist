package dev.ide.interp.compose

import dev.ide.interp.Interpreter
import dev.ide.lang.kotlin.interp.Binding
import dev.ide.lang.kotlin.interp.CallSiteKey
import dev.ide.lang.kotlin.interp.DispatchKind
import dev.ide.lang.kotlin.interp.RArg
import dev.ide.lang.kotlin.interp.RNode
import dev.ide.lang.kotlin.interp.RParam
import dev.ide.lang.kotlin.interp.ResolvedCallable
import dev.ide.lang.kotlin.interp.ResolvedFunction
import dev.ide.lang.kotlin.interp.SlotId
import dev.ide.lang.kotlin.interp.SourceSpan
import kotlin.test.Test
import kotlin.test.assertEquals

/** An ARBITRARY real suspend function — NOT hardcoded anywhere in the interpreter. Proves the general
 *  continuation bridge drives any compiled suspend function, not a known list. */
suspend fun realArbitrarySuspend(): Int {
    kotlinx.coroutines.delay(1)
    return 42
}

/**
 * The coroutine builders `runBlocking`/`async`/`await`/`launch` run cooperatively in the interpreter: a
 * `runBlocking { }` returns its block's value, `async { }` captures a result for `await`, and a `launch { }`
 * child runs (its effect is visible after the scope). Trees are hand-built (the conformance harness has no
 * kotlinx.coroutines on its classpath to lower against).
 */
class CoroutineBuilderTest {

    private val span = SourceSpan(0, 0)
    private fun lib(name: String) = ResolvedCallable.Library(
        displayName = name, ownerFqn = "kotlinx.coroutines.BuildersKt", methodName = name,
        paramTypes = emptyList(), isStatic = true, isConstructor = false, isInline = false,
    )
    private fun call(callee: ResolvedCallable, dispatch: DispatchKind, receiver: RNode?, vararg args: RNode) =
        RNode.Call(callee, dispatch, receiver, args.map { RArg(it) }, CallSiteKey(0), span)
    private fun const(v: Any?) = RNode.Const(v, null, span)
    private fun lambda(vararg body: RNode) =
        RNode.Lambda(emptyList(), RNode.Block(body.toList(), isExpression = true, span), captures = emptyList(), source = span)
    private fun run(body: RNode): Any? {
        val fn = ResolvedFunction("box", emptyList(), RNode.Return(body, span), emptyList())
        return Interpreter(mapOf("box/0" to fn)).call(fn, emptyList())
    }

    @Test fun runBlockingReturnsAsyncAwaitResult() {
        // runBlocking { async { 21 }.await() * 2 }
        val asyncCall = call(lib("async"), DispatchKind.TOP_LEVEL, null, lambda(const(21)))
        val awaitCall = call(lib("await").copy(ownerFqn = "kotlinx.coroutines.Deferred"), DispatchKind.MEMBER, asyncCall)
        val times = RNode.Call(
            ResolvedCallable.Library("times", "kotlin.Int", "times", emptyList(), false, false, false),
            DispatchKind.OPERATOR, awaitCall, listOf(RArg(const(2))), CallSiteKey(0), span,
        )
        val block = call(lib("runBlocking"), DispatchKind.TOP_LEVEL, null, lambda(times))
        assertEquals(42, run(block))
    }

    @Test fun launchChildEffectIsVisible() {
        // runBlocking { var x = 0; launch { x = 10 }; x }
        val x = SlotId(1)
        val xRef = RNode.Name(Binding.Local(x, "x", mutable = true), span)
        val launchBlock = RNode.Lambda(
            emptyList(), RNode.Assign(xRef, const(10), span), captures = emptyList(), source = span,
        )
        val body = RNode.Block(
            listOf(
                RNode.LocalVar(x, "x", mutable = true, const(0), span),
                call(lib("launch"), DispatchKind.TOP_LEVEL, null, launchBlock),
                xRef,
            ),
            isExpression = true, span,
        )
        val block = call(lib("runBlocking"), DispatchKind.TOP_LEVEL, null,
            RNode.Lambda(emptyList(), body, captures = emptyList(), source = span))
        assertEquals(10, run(block))
    }

    @Test fun arbitrarySuspendFunctionRunsThroughTheGeneralBridge() {
        // runBlocking { realArbitrarySuspend() } — the callee is a real compiled suspend function the
        // interpreter has never heard of; the general continuation bridge drives it (its internal `delay`
        // suspends and resumes) with zero per-function code.
        val suspendCallee = ResolvedCallable.Library(
            displayName = "realArbitrarySuspend", ownerFqn = "dev.ide.interp.compose.CoroutineBuilderTestKt",
            methodName = "realArbitrarySuspend", paramTypes = emptyList(), isStatic = true,
            isConstructor = false, isInline = false, isSuspend = true,
        )
        val computeCall = RNode.Call(suspendCallee, DispatchKind.TOP_LEVEL, null, emptyList(), CallSiteKey(0), span)
        val block = call(lib("runBlocking"), DispatchKind.TOP_LEVEL, null, lambda(computeCall))
        assertEquals(42, run(block))
    }

    @Test fun interpretedDelayFlowsThroughTheBridge() {
        // runBlocking { delay(20); 7 } — `delay` is NOT hardcoded; it is a suspend function routed through the
        // general bridge, which invokes the real kotlinx `delay` with a blocking continuation.
        val delayCallee = ResolvedCallable.Library(
            displayName = "delay", ownerFqn = "kotlinx.coroutines.DelayKt", methodName = "delay",
            paramTypes = listOf(null), isStatic = true, isConstructor = false, isInline = false, isSuspend = true,
        )
        val delayCall = RNode.Call(delayCallee, DispatchKind.TOP_LEVEL, null, listOf(RArg(const(20L))), CallSiteKey(0), span)
        val block = call(lib("runBlocking"), DispatchKind.TOP_LEVEL, null, lambda(delayCall, const(7)))
        assertEquals(7, run(block))
    }
}
