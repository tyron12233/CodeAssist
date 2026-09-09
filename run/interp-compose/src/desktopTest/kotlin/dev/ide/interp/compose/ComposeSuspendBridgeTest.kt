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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The interpreter's coroutine bridge ([ComposeSuspendBridge]): an interpreted `suspend` block runs as a real,
 * cancellable coroutine so `delay` actually SUSPENDS (a `while { delay(); tick() }` timer ticks over time
 * instead of busy-looping the UI thread — the reported Memory-Match ANR), and Compose cancelling the effect
 * stops it promptly with nothing left running.
 */
class ComposeSuspendBridgeTest {

    /** A tick sink the interpreted timer calls each pass (a stand-in for `seconds++`). */
    class Ticker {
        val count = AtomicInteger(0)
        fun tick() { count.incrementAndGet() }
    }

    private val span = SourceSpan(0, 0)

    /** `{ while (true) { delay([ms]); ticker.tick() } }` as an interpreted lambda over [interp], with the ticker
     *  bound to slot 0. */
    private fun timerBlock(interp: Interpreter, ms: Long): InterpretedLambda {
        val tickerSlot = SlotId(0)
        val delayCall = RNode.Call(
            ResolvedCallable.Library("delay", "kotlinx.coroutines.DelayKt", "delay",
                listOf(KotlinType("kotlin.Long")), isStatic = true, isConstructor = false, isInline = false),
            DispatchKind.TOP_LEVEL, receiver = null, args = listOf(RArg(RNode.Const(ms, null, span))),
            callSiteKey = CallSiteKey(1), source = span,
        )
        val tickCall = RNode.Call(
            ResolvedCallable.Library("tick", Ticker::class.java.name, "tick", emptyList(),
                isStatic = false, isConstructor = false, isInline = false),
            DispatchKind.MEMBER, receiver = RNode.Name(Binding.Param(tickerSlot, "t"), span), args = emptyList(),
            callSiteKey = CallSiteKey(2), source = span,
        )
        val body = RNode.While(
            RNode.Const(true, null, span),
            RNode.Block(listOf(delayCall, tickCall), isExpression = false, span),
            doWhile = false, source = span,
        )
        val fn = ResolvedFunction("timer", listOf(RParam(tickerSlot, "t", null)), body, emptyList())
        return object : InterpretedLambda {
            override val paramCount = 0
            override fun invoke(args: List<Any?>): Any? = interp.call(fn, listOf(ticker))
        }
    }

    private lateinit var ticker: Ticker

    /** `{ withContext(ctx) { while (true) { delay([ms]); ticker.tick() } } }` — verifies `withContext` runs its
     *  block and a `delay` nested inside it still suspends. */
    private fun withContextTimerBlock(interp: Interpreter, ms: Long): InterpretedLambda {
        val tickerSlot = SlotId(0)
        val delayCall = RNode.Call(
            ResolvedCallable.Library("delay", "kotlinx.coroutines.DelayKt", "delay", listOf(KotlinType("kotlin.Long")),
                isStatic = true, isConstructor = false, isInline = false),
            DispatchKind.TOP_LEVEL, receiver = null, args = listOf(RArg(RNode.Const(ms, null, span))), callSiteKey = CallSiteKey(1), source = span,
        )
        val tickCall = RNode.Call(
            ResolvedCallable.Library("tick", Ticker::class.java.name, "tick", emptyList(), isStatic = false, isConstructor = false, isInline = false),
            DispatchKind.MEMBER, receiver = RNode.Name(Binding.Param(tickerSlot, "t"), span), args = emptyList(), callSiteKey = CallSiteKey(2), source = span,
        )
        val loop = RNode.While(RNode.Const(true, null, span), RNode.Block(listOf(delayCall, tickCall), false, span), doWhile = false, source = span)
        val innerLambda = RNode.Lambda(emptyList(), RNode.Block(listOf(loop), false, span), emptyList(), span)
        val withContextCall = RNode.Call(
            ResolvedCallable.Library("withContext", "kotlinx.coroutines.BuildersKt", "withContext", listOf(null, null),
                isStatic = true, isConstructor = false, isInline = false),
            DispatchKind.TOP_LEVEL, receiver = null,
            args = listOf(RArg(RNode.Const(null, null, span)), RArg(innerLambda)), callSiteKey = CallSiteKey(3), source = span,
        )
        val fn = ResolvedFunction("timer", listOf(RParam(tickerSlot, "t", null)), RNode.Block(listOf(withContextCall), false, span), emptyList())
        return object : InterpretedLambda {
            override val paramCount = 0
            override fun invoke(args: List<Any?>): Any? = interp.call(fn, listOf(ticker))
        }
    }

    @Test
    fun scopeLaunchRunsAnInterpretedCoroutineThatSuspendsAndCancels() = runBlocking {
        // `scope.launch { while (true) { delay(30); tick() } }` — the `rememberCoroutineScope().launch { }`
        // shape (event-handler coroutines). It should dispatch reflectively, its suspend block routed through
        // the bridge so `delay` suspends; cancelling the SCOPE (the composition being disposed) stops it.
        ticker = Ticker()
        val dispatcher = ComposeDispatcher() // wires the suspend bridge into its fallback
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val block = timerBlock(Interpreter(functions = emptyMap()), ms = 30)
        val launchCallee = ResolvedCallable.Library(
            displayName = "launch", ownerFqn = "kotlinx.coroutines.BuildersKt", methodName = "launch",
            paramTypes = listOf(
                KotlinType("kotlin.coroutines.CoroutineContext"), KotlinType("kotlinx.coroutines.CoroutineStart"),
                KotlinType("kotlin.Function2"),
            ),
            isStatic = true, isConstructor = false, isInline = false, isComposable = false,
        )
        val launchCall = RNode.Call(
            launchCallee, DispatchKind.EXTENSION, receiver = null,
            args = listOf(RArg(RNode.Const(null, null, span), trailingLambda = true)), callSiteKey = CallSiteKey(9), source = span,
        )
        dispatcher.dispatch(launchCall, receiver = scope, args = listOf<Any?>(block))

        delay(250)
        val whileRunning = ticker.count.get()
        scope.cancel()
        delay(120)
        val afterCancel = ticker.count.get()
        assertTrue(whileRunning >= 3, "scope.launch must run the interpreted coroutine with real suspension (got $whileRunning)")
        assertTrue(afterCancel - whileRunning <= 1, "cancelling the scope must stop the launched coroutine ($whileRunning → $afterCancel)")
    }

    @Test
    fun flowCollectDrivesTheInterpretedCollectorAndCancels() = runBlocking {
        // `{ src.collect { tick() } }` over an ENDLESS flow (emits every 30ms). `collect` is inline (no JVM
        // method); the bridge drives it as a blocking collect, invoking the interpreted collector per emission,
        // and cancelling the coroutine stops it. Dispatch goes through ComposeDispatcher so collect is routed to
        // the bridge; the collector's `tick()` runs per value.
        ticker = Ticker()
        val dispatcher = ComposeDispatcher()
        val interp = Interpreter(functions = emptyMap(), dispatcher = dispatcher, composableInvoker = ComposeRuntime(dispatcher))
        val src: Flow<Int> = flow { var i = 0; while (true) { emit(i++); delay(30) } }

        val flowSlot = SlotId(0)
        val tickerSlot = SlotId(1)
        val tickCall = RNode.Call(
            ResolvedCallable.Library("tick", Ticker::class.java.name, "tick", emptyList(), isStatic = false, isConstructor = false, isInline = false),
            DispatchKind.MEMBER, receiver = RNode.Name(Binding.Param(tickerSlot, "tk"), span), args = emptyList(), callSiteKey = CallSiteKey(2), source = span,
        )
        val collector = RNode.Lambda(listOf(RParam(SlotId(2), "v", null)), RNode.Block(listOf(tickCall), false, span), emptyList(), span)
        val collectCall = RNode.Call(
            ResolvedCallable.Library("collect", "kotlinx.coroutines.flow.FlowKt", "collect", listOf(null),
                isStatic = false, isConstructor = false, isInline = false),
            DispatchKind.MEMBER, receiver = RNode.Name(Binding.Param(flowSlot, "f"), span), args = listOf(RArg(collector, trailingLambda = true)), callSiteKey = CallSiteKey(3), source = span,
        )
        val fn = ResolvedFunction("run", listOf(RParam(flowSlot, "f", null), RParam(tickerSlot, "tk", null)), RNode.Block(listOf(collectCall), false, span), emptyList())
        val block = object : InterpretedLambda {
            override val paramCount = 0
            override fun invoke(args: List<Any?>): Any? = interp.call(fn, listOf(src, ticker))
        }

        val job = launch(Dispatchers.Default) {
            suspendCancellableCoroutine<Unit> { cont -> ComposeSuspendBridge().runSuspending(block, listOf(cont)) }
        }
        delay(250)
        val whileRunning = ticker.count.get()
        job.cancelAndJoin()
        delay(120)
        val afterCancel = ticker.count.get()
        assertTrue(whileRunning >= 3, "flow.collect must invoke the interpreted collector per emission (got $whileRunning)")
        assertTrue(afterCancel - whileRunning <= 2, "cancelling must stop the collect ($whileRunning → $afterCancel)")
    }

    @Test
    fun withFrameNanosDrivesAFrameLoop() = runBlocking {
        // `{ while (true) { withFrameNanos { tick() } } }` — a frame-driven animation loop. Each frame is a
        // simulated ~60fps tick under the bridge; the loop advances instead of busy-spinning, and cancels.
        ticker = Ticker()
        val interp = Interpreter(functions = emptyMap())
        val tickerSlot = SlotId(0)
        val tickCall = RNode.Call(
            ResolvedCallable.Library("tick", Ticker::class.java.name, "tick", emptyList(), isStatic = false, isConstructor = false, isInline = false),
            DispatchKind.MEMBER, receiver = RNode.Name(Binding.Param(tickerSlot, "t"), span), args = emptyList(), callSiteKey = CallSiteKey(2), source = span,
        )
        val frameLambda = RNode.Lambda(listOf(RParam(SlotId(1), "frameTime", null)), RNode.Block(listOf(tickCall), false, span), emptyList(), span)
        val frameCall = RNode.Call(
            ResolvedCallable.Library("withFrameNanos", "androidx.compose.runtime.MonotonicFrameClockKt", "withFrameNanos",
                listOf(KotlinType("kotlin.Function1")), isStatic = true, isConstructor = false, isInline = false),
            DispatchKind.TOP_LEVEL, receiver = null, args = listOf(RArg(frameLambda)), callSiteKey = CallSiteKey(3), source = span,
        )
        val loop = RNode.While(RNode.Const(true, null, span), RNode.Block(listOf(frameCall), false, span), doWhile = false, source = span)
        val fn = ResolvedFunction("frames", listOf(RParam(tickerSlot, "t", null)), RNode.Block(listOf(loop), false, span), emptyList())
        val block = object : InterpretedLambda {
            override val paramCount = 0
            override fun invoke(args: List<Any?>): Any? = interp.call(fn, listOf(ticker))
        }
        val job = launch(Dispatchers.Default) {
            suspendCancellableCoroutine<Unit> { cont -> ComposeSuspendBridge().runSuspending(block, listOf(cont)) }
        }
        delay(250)
        val frames = ticker.count.get()
        job.cancelAndJoin()
        assertTrue(frames >= 3, "withFrameNanos must drive the frame loop (got $frames frames in 250ms)")
    }

    @Test
    fun withContextRunsItsBlockAndNestedDelaySuspends() = runBlocking {
        ticker = Ticker()
        val interp = Interpreter(functions = emptyMap())
        val job = launch(Dispatchers.Default) {
            suspendCancellableCoroutine<Unit> { cont -> ComposeSuspendBridge().runSuspending(withContextTimerBlock(interp, 30), listOf(cont)) }
        }
        delay(250)
        val whileRunning = ticker.count.get()
        job.cancelAndJoin()
        assertTrue(whileRunning >= 3, "withContext must run its block and the nested delay must suspend (got $whileRunning)")
    }

    @Test
    fun delayActuallySuspendsAndCancelStopsTheLoop() = runBlocking {
        ticker = Ticker()
        val interp = Interpreter(functions = emptyMap())
        val block = timerBlock(interp, ms = 30)
        val bridge = ComposeSuspendBridge()

        // Drive the block exactly as the runtime would: a real coroutine calls it with its Continuation.
        val job = launch(Dispatchers.Default) {
            suspendCancellableCoroutine<Unit> { cont -> bridge.runSuspending(block, listOf(cont)) }
        }

        delay(250) // real time — a 30ms interpreted delay should tick several times, NOT spin
        val whileRunning = ticker.count.get()
        job.cancelAndJoin()
        delay(120)
        val afterCancel = ticker.count.get()

        assertTrue(whileRunning >= 3, "delay must actually suspend so the timer ticks over time (got $whileRunning)")
        assertTrue(afterCancel - whileRunning <= 1, "cancellation must stop the loop — no leaked spinning coroutine ($whileRunning → $afterCancel)")
    }
}
