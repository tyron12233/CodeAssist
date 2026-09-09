package dev.ide.interp.compose.spike

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.IdentityHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.BiConsumer
import java.util.function.Consumer

/**
 * Milestone-A phase-B′ (the two-interpreter threading) bootstrapping proofs. The too-new preview keeps the user
 * `@Preview` SOURCE-interpreted (interp-core) but threads an INTERPRETED composer (a `VmObject` from the
 * project's own interpreted runtime) instead of the bridged host composer. Before any of that can work, HOST
 * code must be able to (1) obtain the interpreted composer out of an interpreted composition, and (2) drive its
 * group protocol back through the VM. This fixture exercises step 1 (the bootstrapping seam): an interpreted
 * composition hands its `currentComposer` to a host callback, so the host side (the future VM-backed
 * `ComposableAbi`/`ComposeRuntime`) receives the `VmObject` composer to thread.
 *
 * Only `androidx.compose.runtime` + this spike package are interpreted; the Kotlin/coroutine floor is bridged.
 */
object ComposerThreadingSpikeFixture {

    /** A node-free applier: this bootstrapping proof exercises only the composer, not node emission. */
    private class UnitApplier : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) {}
        override fun insertBottomUp(index: Int, instance: Unit) {}
        override fun remove(index: Int, count: Int) {}
        override fun move(from: Int, to: Int, count: Int) {}
        override fun onClear() {}
    }

    /**
     * Set up an interpreted composition and, inside `setContent`, hand the composition's `currentComposer` to the
     * host [sink]. `currentComposer` is the Compose intrinsic for the ambient composer, so under interpretation
     * it is the interpreted (`VmObject`) composer — the value the source-interpreter threading needs to drive.
     * Uses the sanctioned Recomposer frame loop (so `composeInitial` runs), calling the host sink from the
     * composition thread. Returns a marker string so the caller can confirm the whole pass completed.
     */
    @JvmStatic
    fun handComposerToHost(sink: Consumer<Any?>): String {
        val executor = Executors.newSingleThreadExecutor { Thread(it, "spike-threading") }
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            return runBlocking {
                withTimeout(30_000) {
                    val clock = BroadcastFrameClock()
                    val recomposer = Recomposer(coroutineContext + dispatcher + clock)
                    val runJob = launch(dispatcher + clock) { recomposer.runRecomposeAndApplyChanges() }
                    recomposer.currentState.first { it == Recomposer.State.Idle }
                    withContext(dispatcher) {
                        val composition = Composition(UnitApplier(), recomposer)
                        composition.setContent {
                            sink.accept(currentComposer)
                        }
                        composition.dispose()
                    }
                    recomposer.cancel()
                    runJob.cancel()
                    "handed"
                }
            }
        } finally {
            executor.shutdownNow()
        }
    }

    /** Records the emitted node tree (parent = the applier's `current`) instead of touching LayoutNode's internal
     *  `insertAt` — same approach as the phase-B node-tree spikes, so a host-driven composition reads back
     *  structurally. */
    private class RecordingApplier(
        root: Any,
        private val edges: IdentityHashMap<Any, MutableList<Any>>,
    ) : AbstractApplier<Any>(root) {
        override fun insertTopDown(index: Int, instance: Any) {}
        override fun insertBottomUp(index: Int, instance: Any) {
            edges.getOrPut(current) { ArrayList() }.add(instance)
        }
        override fun remove(index: Int, count: Int) {}
        override fun move(from: Int, to: Int, count: Int) {}
        override fun onClear() {}
    }

    private fun serialize(node: Any, edges: IdentityHashMap<Any, MutableList<Any>>): String {
        val kids = edges[node] ?: return "()"
        return "(" + kids.joinToString(",") { serialize(it, edges) } + ")"
    }

    /** The Owner locals `LayoutNode.setCompositionLocalMap` reads eagerly (see the phase-B node-tree spikes). */
    private val viewConfiguration = object : ViewConfiguration {
        override val longPressTimeoutMillis get() = 500L
        override val doubleTapTimeoutMillis get() = 300L
        override val doubleTapMinTimeMillis get() = 40L
        override val touchSlop get() = 8f
    }

    /**
     * Like [handComposerToHost] but with a recording applier + Owner locals, returning the emitted node tree's
     * structure. Hands the composition's `currentComposer` (inside the locals provider) to the host [driver]; the
     * driver runs the source interpreter, whose library composable emits a real `LayoutNode` into this
     * composition — so the emitted tree comes back through the source-interpreter path on the interpreted composer
     * (the end-to-end node-emission proof).
     */
    @JvmStatic
    fun composeSourceDrivenTree(driver: Consumer<Any?>): String {
        val root = Any()
        val edges = IdentityHashMap<Any, MutableList<Any>>()
        val executor = Executors.newSingleThreadExecutor { Thread(it, "spike-threading-nodes") }
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            return runBlocking {
                withTimeout(30_000) {
                    val clock = BroadcastFrameClock()
                    val recomposer = Recomposer(coroutineContext + dispatcher + clock)
                    val runJob = launch(dispatcher + clock) { recomposer.runRecomposeAndApplyChanges() }
                    recomposer.currentState.first { it == Recomposer.State.Idle }
                    val result = withContext(dispatcher) {
                        val composition = Composition(RecordingApplier(root, edges), recomposer)
                        composition.setContent {
                            CompositionLocalProvider(
                                LocalDensity provides Density(1f),
                                LocalLayoutDirection provides LayoutDirection.Ltr,
                                LocalViewConfiguration provides viewConfiguration,
                            ) {
                                driver.accept(currentComposer)
                            }
                        }
                        val r = serialize(root, edges)
                        composition.dispose()
                        r
                    }
                    recomposer.cancel()
                    runJob.cancel()
                    result
                }
            }
        } finally {
            executor.shutdownNow()
        }
    }

    /**
     * B′.2 recomposition proof: drive an INTERPRETED composition through the sanctioned Recomposer frame loop and
     * check that a write to an INTERPRETED `MutableState` recomposes a SOURCE-interpreted body that read it. The
     * state is created interpreted here (so a write goes through the interpreted snapshot the interpreted
     * Recomposer observes), then handed with the composer to the host [driver], which runs the source interpreter
     * body (reading `state.value` subscribes its scope). After the write + pumped frames the body must run twice
     * (initial + one recomposition); [runs] is the host-side counter the driver increments, and the loop pumps
     * until it reaches 2. Mirrors the all-interpreted [ComposerSpikeFixture.recomposesOnStateChange], but the
     * reading body is source-interpreted and the composer/state are threaded to it.
     */
    @JvmStatic
    fun driveRecomposition(driver: BiConsumer<Any?, Any?>, runs: AtomicInteger): Int {
        val state = mutableStateOf(0)
        val executor = Executors.newSingleThreadExecutor { Thread(it, "spike-recompose-src") }
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            return runBlocking {
                withTimeout(30_000) {
                    val clock = BroadcastFrameClock()
                    val recomposer = Recomposer(coroutineContext + dispatcher + clock)
                    val runJob = launch(dispatcher + clock) { recomposer.runRecomposeAndApplyChanges() }
                    recomposer.currentState.first { it == Recomposer.State.Idle }
                    val composition = withContext(dispatcher) {
                        Composition(UnitApplier(), recomposer).also { c ->
                            c.setContent { driver.accept(currentComposer, state) }
                        }
                    }
                    withContext(dispatcher) {
                        state.value = 1
                        Snapshot.sendApplyNotifications()
                    }
                    var frame = 0L
                    while (runs.get() < 2 && frame < 240) {
                        clock.sendFrame(frame++)
                        delay(5)
                    }
                    withContext(dispatcher) { composition.dispose() }
                    recomposer.cancel()
                    runJob.cancel()
                    runs.get()
                }
            }
        } finally {
            executor.shutdownNow()
        }
    }
}
