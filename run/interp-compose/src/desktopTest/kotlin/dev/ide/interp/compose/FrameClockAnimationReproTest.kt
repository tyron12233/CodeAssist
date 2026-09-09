package dev.ide.interp.compose

import androidx.compose.runtime.Applier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
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
import kotlin.test.assertTrue

/**
 * Repro for "gradient based shadow animation does not work" — the case where the animation is driven by a
 * hand-rolled frame loop `LaunchedEffect(Unit) { while (true) { withFrameNanos { … } } }` (the common idiom
 * for animating a custom `drawBehind` gradient shadow). Hand-builds `LaunchedEffect(0) { withFrameNanos {
 * frameProbe(it) }; withFrameNanos { frameProbe(it) } }` and renders it through a real `Recomposer` +
 * `BroadcastFrameClock` (like [LaunchedEffectDelayRepro]), pumping frames.
 *
 * Records each frame time the interpreted `withFrameNanos` callback observes:
 *  - ≥ 2 increasing values  → the interpreted frame-clock path ticks (animation works).
 *  - empty (+ a swallowed partial error) → `withFrameNanos` never resumed on the bridge thread (broken).
 */
class FrameClockAnimationReproTest {

    private val span = SourceSpan(0, 0)
    private val self = "dev.ide.interp.compose.FrameClockAnimationReproTestKt"

    private fun withFrameNanosCall(key: Int): RNode.Call {
        val tSlot = SlotId(1)
        // { t -> frameProbe(t) }
        val onFrame = RNode.Lambda(
            listOf(RParam(tSlot, "t", null)),
            RNode.Call(
                ResolvedCallable.Library("frameProbe", self, "frameProbe", listOf(KotlinType("kotlin.Long")), isStatic = true, isConstructor = false, isInline = false),
                DispatchKind.TOP_LEVEL, null, listOf(RArg(RNode.Name(Binding.Local(tSlot, "t", false), span))), CallSiteKey(key + 100), span,
            ),
            captures = emptyList(), source = span,
        )
        return RNode.Call(
            ResolvedCallable.Library(
                "withFrameNanos", "androidx.compose.runtime.MonotonicFrameClockKt", "withFrameNanos",
                listOf(KotlinType("kotlin.Function1")), isStatic = true, isConstructor = false, isInline = false, isSuspend = true,
            ),
            DispatchKind.TOP_LEVEL, null, listOf(RArg(onFrame, trailingLambda = true)), CallSiteKey(key), span,
        )
    }

    @Test
    fun withFrameNanosLoopTicksInsideInterpretedLaunchedEffect() {
        frameProbed.clear()
        // LaunchedEffect(0) { withFrameNanos { frameProbe(it) }; withFrameNanos { frameProbe(it) } }
        val block = RNode.Lambda(
            emptyList(),
            RNode.Block(listOf(withFrameNanosCall(1), withFrameNanosCall(2)), false, span),
            emptyList(), span,
        )
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
        val entry = ResolvedFunction("FrameAnim", emptyList(), RNode.Block(listOf(launchedEffect), false, span), emptyList(), returnsUnit = true)

        val partial = Collections.synchronizedList(mutableListOf<Throwable?>())
        val renderer = ComposePreviewRenderer(loader = null)
        val executor = Executors.newSingleThreadExecutor { Thread(it, "frame-repro") }
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
                    while (frame < 120 && frameProbed.snap().size < 2) { clock.sendFrame(frame++ * 16_000_000L); delay(16) }
                    withContext(cd) { composition.dispose() }
                    recomposer.cancel(); runJob.cancel()
                }
            }
        } finally {
            executor.shutdownNow()
        }
        val errs = synchronized(partial) { partial.filterNotNull() }
        val frames = frameProbed.snap()
        assertTrue(
            frames.size >= 2,
            "an interpreted `withFrameNanos` loop must tick (got ${frames.size} frame(s): $frames); partialErrors=${errs.map { "${it::class.simpleName}: ${it.message}" }}",
        )
        assertTrue(frames[1] > frames[0], "frame time must advance across frames; got $frames")
    }

    // --- Probe A: the STANDARD animation API a gradient shadow is usually driven by ---

    private fun animConst(v: Any?, fqn: String) = RNode.Const(v, KotlinType(fqn), span)

    @Test
    fun rememberInfiniteTransitionAnimatesAcrossFrames() {
        animProbed.clear()
        // val t = rememberInfiniteTransition("t")
        // val v = t.animateFloat(0f, 100f, infiniteRepeatable(tween(1000)), "a").value
        // animProbe(v)
        val tSlot = SlotId(1)
        val rememberTransition = RNode.Call(
            ResolvedCallable.Library(
                "rememberInfiniteTransition", "androidx.compose.animation.core.InfiniteTransitionKt", "rememberInfiniteTransition",
                listOf(KotlinType("kotlin.String")), isStatic = true, isConstructor = false, isInline = false, isComposable = true,
            ),
            DispatchKind.TOP_LEVEL, null, listOf(RArg(animConst("t", "kotlin.String"))), CallSiteKey(1), span,
        )
        val tweenCall = RNode.Call(
            ResolvedCallable.Library("tween", "androidx.compose.animation.core.AnimationSpecKt", "tween", listOf(KotlinType("kotlin.Int")), isStatic = true, isConstructor = false, isInline = false),
            DispatchKind.TOP_LEVEL, null, listOf(RArg(animConst(1000, "kotlin.Int"))), CallSiteKey(2), span,
        )
        val infiniteRepeatable = RNode.Call(
            ResolvedCallable.Library("infiniteRepeatable", "androidx.compose.animation.core.AnimationSpecKt", "infiniteRepeatable", listOf(KotlinType("androidx.compose.animation.core.DurationBasedAnimationSpec")), isStatic = true, isConstructor = false, isInline = false),
            DispatchKind.TOP_LEVEL, null, listOf(RArg(tweenCall)), CallSiteKey(3), span,
        )
        val animateFloat = RNode.Call(
            ResolvedCallable.Library(
                "animateFloat", "androidx.compose.animation.core.InfiniteTransitionKt", "animateFloat",
                listOf(KotlinType("kotlin.Float"), KotlinType("kotlin.Float"), KotlinType("androidx.compose.animation.core.InfiniteRepeatableSpec"), KotlinType("kotlin.String")),
                isStatic = true, isConstructor = false, isInline = false, isComposable = true,
            ),
            DispatchKind.EXTENSION, RNode.Name(Binding.Local(tSlot, "t", false), span),
            listOf(RArg(animConst(0f, "kotlin.Float")), RArg(animConst(100f, "kotlin.Float")), RArg(infiniteRepeatable), RArg(animConst("a", "kotlin.String"))),
            CallSiteKey(4), span,
        )
        val readValue = RNode.PropertyGet(animateFloat, Binding.Property("value", ownerFqn = null, backingField = false), span)
        val animProbeCall = RNode.Call(
            ResolvedCallable.Library("animProbe", self, "animProbe", listOf(KotlinType("kotlin.Float")), isStatic = true, isConstructor = false, isInline = false),
            DispatchKind.TOP_LEVEL, null, listOf(RArg(readValue)), CallSiteKey(5), span,
        )
        val entry = ResolvedFunction(
            "InfiniteAnim", emptyList(),
            RNode.Block(listOf(RNode.LocalVar(tSlot, "t", mutable = false, initializer = rememberTransition, source = span), animProbeCall), false, span),
            emptyList(), returnsUnit = true,
        )

        val partial = Collections.synchronizedList(mutableListOf<Throwable?>())
        val fatal = Collections.synchronizedList(mutableListOf<Throwable>())
        val renderer = ComposePreviewRenderer(loader = null)
        val executor = Executors.newSingleThreadExecutor { Thread(it, "anim-repro") }
        val cd = executor.asCoroutineDispatcher()
        try {
            runBlocking {
                withTimeout(30_000) {
                    val clock = BroadcastFrameClock()
                    val recomposer = Recomposer(coroutineContext + cd + clock)
                    val runJob = launch(cd + clock) { recomposer.runRecomposeAndApplyChanges() }
                    recomposer.currentState.first { it == Recomposer.State.Idle }
                    val composition = withContext(cd) {
                        Composition(UnitApplier, recomposer).also { c ->
                            c.setContent { renderer.Render(entry, emptyMap(), emptyList(), emptyList(), onError = { fatal.add(it) }, onPartialError = { partial.add(it) }) }
                        }
                    }
                    var frame = 0L
                    while (frame < 120) { clock.sendFrame(frame++ * 16_000_000L); delay(12) }
                    withContext(cd) { composition.dispose() }
                    recomposer.cancel(); runJob.cancel()
                }
            }
        } finally {
            executor.shutdownNow()
        }
        val perrs = synchronized(partial) { partial.filterNotNull() }
        val ferrs = synchronized(fatal) { fatal.toList() }
        val values = animProbed.snapF()
        val distinct = values.toSet()
        assertTrue(
            distinct.size >= 2,
            "a standard rememberInfiniteTransition().animateFloat() must produce changing values across frames " +
                "(got ${values.size} sample(s), ${distinct.size} distinct: ${values.take(8)}…); " +
                "fatal=${ferrs.map { "${it::class.simpleName}: ${it.message}" }}; partial=${perrs.map { "${it::class.simpleName}: ${it.message}" }}",
        )
    }

    private fun MutableList<Long>.snap(): List<Long> = synchronized(this) { toList() }
    private fun MutableList<Float>.snapF(): List<Float> = synchronized(this) { toList() }

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

val frameProbed: MutableList<Long> = Collections.synchronizedList(mutableListOf())
fun frameProbe(t: Long) { frameProbed.add(t) }

val animProbed: MutableList<Float> = Collections.synchronizedList(mutableListOf())
fun animProbe(v: Float) { animProbed.add(v) }
