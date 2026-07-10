package dev.ide.interp.compose

import dev.ide.interp.InterpretedLambda
import dev.ide.interp.SuspendBridge
import dev.ide.interp.SuspendContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume

/**
 * Runs an interpreted `suspend` block (a `LaunchedEffect { }` / `rememberCoroutineScope().launch { }` body) as
 * a REAL, cancellable coroutine — the recomposition/coroutine half of the interpreter's Compose bridge.
 *
 * The block is invoked by the real Compose runtime with a trailing [Continuation] (the caller coroutine's). We
 * run the block on [Dispatchers.Default] as a CHILD of the caller's `Job` (taken from that continuation's
 * context), so structured concurrency ties its whole lifecycle to the effect: when Compose cancels the effect
 * (a `LaunchedEffect` key change, or the preview being disposed / recomposed with a new program) the caller
 * `Job` is cancelled, the child coroutine with it, and [runInterruptible] interrupts the interpreter thread so
 * a blocking `delay` sleep aborts at once. There is no thread pool or registry of our own to leak — the runtime
 * tears the child (and its thread) down. Returning [COROUTINE_SUSPENDED] makes the caller coroutine suspend
 * while the block runs off the caller (main) thread; the block's snapshot-state writes (`count++` in a timer)
 * flow back through Compose's thread-safe snapshot system and schedule recomposition on the applier thread.
 *
 * [SuspendContext.runManaged] marks the background thread so the `delay` intrinsic knows it may block THERE
 * (never on the caller/main thread). Suspend calls the interpreter can't model still fail, but now abort only
 * this one effect (degrading to no-op) instead of hanging.
 */
class ComposeSuspendBridge : SuspendBridge {

    override fun runSuspending(lambda: InterpretedLambda, args: List<Any?>): Any? {
        @Suppress("UNCHECKED_CAST")
        val continuation = args.last() as Continuation<Any?>
        val blockArgs = args.dropLast(1) // strip the trailing Continuation; the receiver/value params remain
        // Child of the caller's Job (its context carries it) → Compose's cancellation propagates in.
        CoroutineScope(continuation.context).launch(Dispatchers.Default) {
            try {
                runInterruptible(Dispatchers.Default) {
                    SuspendContext.runManaged { lambda.invoke(blockArgs) }
                }
                // Completed normally → resume the suspended caller coroutine so the effect finishes.
                runCatching { continuation.resume(Unit) }
            } catch (c: kotlinx.coroutines.CancellationException) {
                // Cancelled by Compose: the runtime already resumed the caller's continuation — never double-resume.
                throw c
            } catch (t: Throwable) {
                // A suspend call we can't model, or an interpreter error mid-block: degrade — complete the effect
                // normally so the (partial) preview stands, rather than crashing the composition. Guarded against
                // a resume race with a just-fired cancellation.
                if (t is VirtualMachineError) throw t
                runCatching { continuation.resume(Unit) }
            }
        }
        return COROUTINE_SUSPENDED
    }

    @Suppress("UNCHECKED_CAST")
    override fun collectFlow(flow: Any, action: InterpretedLambda): Any? {
        // We're already on the bridge's managed background thread (inside `runInterruptible`, i.e. a plain
        // blocking thread, not a coroutine), so drive the collection with `runBlocking`: it starts an event loop
        // HERE and runs the flow to completion — or, for an endless `StateFlow`, until the coroutine is cancelled,
        // which interrupts this thread and makes `runBlocking` throw (aborting the collect). The interpreted
        // collector runs synchronously per emission (its own `delay`/state writes work as in any bridge block).
        runBlocking { (flow as Flow<Any?>).collect { value -> action.invoke(listOf(value)) } }
        return Unit
    }
}
