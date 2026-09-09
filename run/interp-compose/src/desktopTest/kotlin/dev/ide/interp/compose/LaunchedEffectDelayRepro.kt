package dev.ide.interp.compose

import androidx.compose.runtime.Applier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import dev.ide.lang.kotlin.interp.CallSiteKey
import dev.ide.lang.kotlin.interp.DispatchKind
import dev.ide.lang.kotlin.interp.RArg
import dev.ide.lang.kotlin.interp.RNode
import dev.ide.lang.kotlin.interp.ResolvedCallable
import dev.ide.lang.kotlin.interp.ResolvedFunction
import dev.ide.lang.kotlin.interp.SourceSpan
import dev.ide.lang.kotlin.symbols.KotlinType
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.Collections
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Repro for the reported "`delay` outside an interpreted coroutine (no suspend bridge)" on a `LaunchedEffect`
 * timer. Hand-builds `LaunchedEffect(0) { probe(1); delay(10); probe(2) }` (referencing a test-observable
 * `probe`), renders it through a real `Recomposer` (like `CounterStateRepro`), and records what the effect
 * reaches: [1, 2] = the block ran AND `delay` suspended+resumed (works); [1] (+ a "delay outside" partial
 * error) = the block ran synchronously off the bridge; [] = the effect never launched.
 */
class LaunchedEffectDelayRepro {

    private val span = SourceSpan(0, 0)
    private val self = "dev.ide.interp.compose.LaunchedEffectDelayReproKt"

    private fun probeCall(v: Int) = RNode.Call(
        ResolvedCallable.Library("leDelayProbe", self, "leDelayProbe", listOf(KotlinType("kotlin.Int")), isStatic = true, isConstructor = false, isInline = false),
        DispatchKind.TOP_LEVEL, null, listOf(RArg(RNode.Const(v, KotlinType("kotlin.Int"), span))), CallSiteKey(v), span,
    )

    @Test
    fun launchedEffectDelayRunsThroughTheSuspendBridge() {
        leDelayProbed.clear()
        val delayCall = RNode.Call(
            ResolvedCallable.Library("delay", "kotlinx.coroutines.DelayKt", "delay", listOf(KotlinType("kotlin.Long")), isStatic = true, isConstructor = false, isInline = false),
            DispatchKind.TOP_LEVEL, null, listOf(RArg(RNode.Const(10L, KotlinType("kotlin.Long"), span))), CallSiteKey(10), span,
        )
        // The block is `suspend CoroutineScope.() -> Unit`; the bridge invokes it with the scope arg (Continuation
        // stripped). No declared params → the scope arg is ignored; no captures (probe/delay are library calls).
        val block = RNode.Lambda(emptyList(), RNode.Block(listOf(probeCall(1), delayCall, probeCall(2)), false, span), emptyList(), span)
        val launchedEffect = RNode.Call(
            ResolvedCallable.Library(
                "LaunchedEffect", "androidx.compose.runtime.EffectsKt", "LaunchedEffect",
                listOf(KotlinType("kotlin.Any"), KotlinType("kotlin.Function2")),
                isStatic = true, isConstructor = false, isInline = false, isComposable = true,
            ),
            DispatchKind.TOP_LEVEL, null,
            listOf(RArg(RNode.Const(0, KotlinType("kotlin.Int"), span)), RArg(block, trailingLambda = true)),
            CallSiteKey(3), span,
        )
        val entry = ResolvedFunction("Timer", emptyList(), RNode.Block(listOf(launchedEffect), false, span), emptyList(), returnsUnit = true)

        val partial = Collections.synchronizedList(mutableListOf<Throwable?>())
        val renderer = ComposePreviewRenderer(loader = null)
        val executor = Executors.newSingleThreadExecutor { Thread(it, "le-repro") }
        val cd = executor.asCoroutineDispatcher()
        try {
            runBlocking {
                withTimeout(20_000) {
                    val clock = BroadcastFrameClock()
                    val recomposer = Recomposer(coroutineContext + cd + clock)
                    val runJob = launch(cd + clock) { recomposer.runRecomposeAndApplyChanges() }
                    recomposer.currentState.first { it == Recomposer.State.Idle }
                    val composition = withContext(cd) {
                        Composition(UnitApplier, recomposer).also { c ->
                            c.setContent { renderer.Render(entry, emptyMap(), emptyList(), emptyList(), onError = {}, onPartialError = { partial.add(it) }) }
                        }
                    }
                    var frame = 0L
                    while (frame < 60 && leDelayProbed.snap().size < 2) { clock.sendFrame(frame++ * 16_000_000L); delay(16) }
                    withContext(cd) { composition.dispose() }
                    recomposer.cancel(); runJob.cancel()
                }
            }
        } finally {
            executor.shutdownNow()
        }
        val errs = synchronized(partial) { partial.filterNotNull() }
        // The suspend block must run through the bridge: probe(1) BEFORE `delay`, then (delay suspends+resumes)
        // probe(2) AFTER. A regression (the block run synchronously on the composition thread) throws "delay
        // outside an interpreted coroutine", which is swallowed → only [1] is recorded.
        assertEquals(listOf(1, 2), leDelayProbed.snap(), "LaunchedEffect suspend block must reach probe(2) past `delay`; partialErrors=${errs.map { it.message }}")
        assertTrue(errs.none { it.message?.contains("delay outside") == true }, "no `delay outside an interpreted coroutine`; got ${errs.map { it.message }}")
    }

    private fun MutableList<Int>.snap(): List<Int> = synchronized(this) { toList() }

    private object UnitApplier : Applier<Unit> {
        override val current: Unit get() = Unit
        override fun down(node: Unit) {}
        override fun up() {}
        override fun insertTopDown(index: Int, instance: Unit) {}
        override fun insertBottomUp(index: Int, instance: Unit) {}
        override fun remove(index: Int, count: Int) {}
        override fun move(from: Int, to: Int, count: Int) {}
        override fun clear() {}
    }
}

val leDelayProbed: MutableList<Int> = Collections.synchronizedList(mutableListOf())
fun leDelayProbe(v: Int) { leDelayProbed.add(v) }
