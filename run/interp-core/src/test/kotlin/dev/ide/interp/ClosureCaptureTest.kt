package dev.ide.interp

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

/**
 * Closure-capture scoping regression. A lambda created inside a loop (or one created once and invoked many
 * times) must capture the value bound for THAT iteration/invocation, not a single slot mutated to its final
 * value — the bug behind "every `clickable { … println(index) }` prints 49". These drive the interpreter with
 * hand-built [RNode] trees (the lightweight test resolver can't lower deferred higher-order programs) and use
 * a [RecordingDispatcher] to grab the per-iteration lambdas and invoke them AFTER the loop, so a shared-env
 * regression would surface as the last value repeated.
 */
class ClosureCaptureTest {

    private val sp = SourceSpan(0, 0)
    private val ck = CallSiteKey(0)

    /** A dispatcher that records the lambda passed to `sink.record(...)` (raw, un-proxied) for later invocation. */
    private class RecordingDispatcher : Dispatcher {
        val recorded = mutableListOf<InterpretedLambda>()
        override fun dispatch(call: RNode.Call, receiver: Any?, args: List<Any?>): Any? {
            if (call.callee.displayName == "record") {
                recorded.add(args[0] as InterpretedLambda)
                return Unit
            }
            throw InterpreterException("unexpected call ${call.callee.displayName}")
        }
    }

    private fun recordCall(sinkSlot: Int, captured: RNode): RNode.Call = RNode.Call(
        callee = ResolvedCallable.Library("record", null, "record", listOf(null), isStatic = false, isConstructor = false, isInline = false),
        dispatch = DispatchKind.MEMBER,
        receiver = RNode.Name(Binding.Param(SlotId(sinkSlot), "sink"), sp),
        args = listOf(RArg(RNode.Lambda(params = emptyList(), body = captured, captures = emptyList(), source = sp))),
        callSiteKey = ck,
        source = sp,
    )

    @Test
    fun forEachLoopCapturesPerIterationValue() {
        // for (x in xs) { sink.record { x } } — each `{ x }` must capture its own iteration's x.
        val xsSlot = 0; val sinkSlot = 1; val xSlot = 2
        val body = RNode.Block(listOf(recordCall(sinkSlot, RNode.Name(Binding.Param(SlotId(xSlot), "x"), sp))), isExpression = false, source = sp)
        val forEach = RNode.ForEach(
            loopVar = RParam(SlotId(xSlot), "x", null),
            iterable = RNode.Name(Binding.Param(SlotId(xsSlot), "xs"), sp),
            iterator = null, hasNext = null, next = null, body = body, source = sp,
        )
        val fn = ResolvedFunction(
            name = "collect",
            params = listOf(RParam(SlotId(xsSlot), "xs", null), RParam(SlotId(sinkSlot), "sink", null)),
            body = RNode.Block(listOf(forEach), isExpression = false, source = sp),
            diagnostics = emptyList(),
        )
        val disp = RecordingDispatcher()
        Interpreter(emptyMap(), disp).call(fn, listOf(listOf(0, 1, 2), Any()))
        assertEquals(listOf(0, 1, 2), disp.recorded.map { it.invoke(emptyList()) })
    }

    @Test
    fun repeatInvokesOneLambdaWithFreshScopePerCall() {
        // The user's exact shape: repeat(3) { index -> sink.record { index } }. ONE outer lambda is created and
        // invoked 3 times by the `repeat` intrinsic; each invocation must bind `index` in a fresh scope so the
        // recorded inner lambdas see 0, 1, 2 — not 2, 2, 2.
        val sinkSlot = 10; val indexSlot = 0
        val outerLambda = RNode.Lambda(
            params = listOf(RParam(SlotId(indexSlot), "index", null)),
            body = recordCall(sinkSlot, RNode.Name(Binding.Param(SlotId(indexSlot), "index"), sp)),
            captures = emptyList(), source = sp,
        )
        val repeatCall = RNode.Call(
            callee = ResolvedCallable.Library("repeat", "kotlin.StandardKt", "repeat", listOf(null, null), isStatic = true, isConstructor = false, isInline = true),
            dispatch = DispatchKind.TOP_LEVEL,
            receiver = null,
            args = listOf(RArg(RNode.Const(3, null, sp)), RArg(outerLambda)),
            callSiteKey = ck, source = sp,
        )
        val fn = ResolvedFunction(
            name = "run3",
            params = listOf(RParam(SlotId(sinkSlot), "sink", null)),
            body = RNode.Block(listOf(repeatCall), isExpression = false, source = sp),
            diagnostics = emptyList(),
        )
        val disp = RecordingDispatcher()
        Interpreter(emptyMap(), disp).call(fn, listOf(Any()))
        assertEquals(listOf(0, 1, 2), disp.recorded.map { it.invoke(emptyList()) })
    }
}
