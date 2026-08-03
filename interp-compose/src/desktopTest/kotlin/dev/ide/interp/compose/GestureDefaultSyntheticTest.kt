package dev.ide.interp.compose

import dev.ide.interp.InterpretedLambda
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
import dev.ide.lang.kotlin.symbols.KotlinType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals

/** A stand-in for `PointerInputScope`, so [detectGestures] is a suspend EXTENSION exactly like the real
 *  `androidx.compose.foundation.gestures.detectDragGestures`. */
class Gestures

/** A record of what the interpreted gesture callbacks did — the observable the test asserts on. */
class GestureSink {
    val events = CopyOnWriteArrayList<String>()
    fun mark(s: String) { events.add(s) }
    fun event(n: Int) { events.add("e$n") }
}

/**
 * The shape of a low-level Compose gesture detector: a SUSPEND extension whose leading callbacks are DEFAULTED
 * and whose trailing callback is required — exactly `detectDragGestures(onDragStart = {}, onDragEnd = {},
 * onDragCancel = {}, onDrag)`. Compiling it produces a `detectGestures$default(Gestures, Function0, Function0,
 * Function0, Function1, int mask, Object marker, Continuation)` synthetic — the trailing `Continuation` AFTER
 * the mask+marker is what the default-synthetic dispatch has to account for.
 */
suspend fun Gestures.detectGestures(
    onStart: () -> Unit = {},
    onEnd: () -> Unit = {},
    onCancel: () -> Unit = {},
    onEvent: (Int) -> Unit,
) {
    onStart()
    onEvent(1)
    onEvent(2)
    onEnd()
}

/**
 * Regression for the reported preview bug: a `Modifier.pointerInput { detectDragGestures(...) }` gesture never
 * fired (the 2048 board couldn't be swiped). Root cause: `detectDragGestures` is a SUSPEND function with
 * defaulted callbacks, so its call routes through the `$default` synthetic — but that synthetic carries a
 * trailing `Continuation` after the mask+marker, which the default-synthetic dispatch mis-slotted (rejecting
 * the synthetic outright), so the whole gesture block threw and was swallowed.
 *
 * This drives the interpreter through the SAME path (a suspend block, via the coroutine bridge, calling a
 * defaulted suspend extension with a trailing lambda) against a real compiled `detectGestures$default`, and
 * asserts every callback — including the trailing one and the defaulted ones — runs.
 */
class GestureDefaultSyntheticTest {

    private val span = SourceSpan(0, 0)
    private val facade = "dev.ide.interp.compose.GestureDefaultSyntheticTestKt"

    private fun sinkCall(method: String, paramType: KotlinType, sinkSlot: SlotId, arg: RNode): RNode.Call =
        RNode.Call(
            ResolvedCallable.Library(method, GestureSink::class.java.name, method, listOf(paramType),
                isStatic = false, isConstructor = false, isInline = false),
            DispatchKind.MEMBER, receiver = RNode.Name(Binding.Param(sinkSlot, "sink"), span),
            args = listOf(RArg(arg)), callSiteKey = CallSiteKey(method.hashCode()), source = span,
        )

    /** `{ scope.detectGestures(onStart = { sink.mark("start") }, onEnd = { sink.mark("end") }) { e -> sink.event(e) } }`
     *  — the defaulted `onCancel` omitted, the trailing `onEvent` supplied — as an interpreted block over [interp]. */
    private fun gestureBlock(interp: Interpreter, scope: Gestures, sink: GestureSink): InterpretedLambda {
        val scopeSlot = SlotId(0)
        val sinkSlot = SlotId(1)
        val eSlot = SlotId(2)
        val onStart = RNode.Lambda(emptyList(), sinkCall("mark", KotlinType("kotlin.String"), sinkSlot, RNode.Const("start", null, span)), emptyList(), span)
        val onEnd = RNode.Lambda(emptyList(), sinkCall("mark", KotlinType("kotlin.String"), sinkSlot, RNode.Const("end", null, span)), emptyList(), span)
        val onEvent = RNode.Lambda(
            listOf(RParam(eSlot, "e", null)),
            sinkCall("event", KotlinType("kotlin.Int"), sinkSlot, RNode.Name(Binding.Param(eSlot, "e"), span)),
            emptyList(), span,
        )
        val detect = RNode.Call(
            ResolvedCallable.Library("detectGestures", facade, "detectGestures", listOf(null, null, null, null),
                isStatic = true, isConstructor = false, isInline = false, isSuspend = true),
            DispatchKind.EXTENSION, receiver = RNode.Name(Binding.Param(scopeSlot, "scope"), span),
            args = listOf(RArg(onStart), RArg(onEnd), RArg(onEvent, trailingLambda = true)),
            callSiteKey = CallSiteKey(1), source = span,
        )
        val fn = ResolvedFunction("body", listOf(RParam(scopeSlot, "scope", null), RParam(sinkSlot, "sink", null)),
            RNode.Block(listOf(detect), isExpression = false, span), emptyList())
        return object : InterpretedLambda {
            override val paramCount = 0
            override fun invoke(args: List<Any?>): Any? = interp.call(fn, listOf(scope, sink))
        }
    }

    @Test
    fun defaultedSuspendGestureDetectorRunsEveryCallback() = runBlocking {
        val sink = GestureSink()
        val interp = Interpreter(functions = emptyMap(), dispatcher = ComposeDispatcher())
        val block = gestureBlock(interp, Gestures(), sink)
        // Drive it as the pointerInput node would: a real coroutine invokes the suspend block with its Continuation.
        val job = launch(Dispatchers.Default) {
            suspendCancellableCoroutine<Unit> { cont -> ComposeSuspendBridge().runSuspending(block, listOf(cont)) }
        }
        job.join()
        // Before the fix `detectGestures` threw (its `$default` synthetic was rejected) and the block swallowed
        // it, leaving the sink empty. After the fix every callback runs, in order.
        assertEquals(listOf("start", "e1", "e2", "end"), sink.events.toList())
    }
}
