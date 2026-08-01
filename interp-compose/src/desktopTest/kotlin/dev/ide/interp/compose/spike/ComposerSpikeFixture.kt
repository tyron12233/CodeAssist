package dev.ide.interp.compose.spike

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Executors
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Milestone-A phase-1 drivers: run a real COMPOSITION (not just snapshot state) so the interpreter drives an
 * interpreted `Composer`/`SlotTable` — the positional-memoization core the bridged-runtime flip can't align across
 * Compose versions. This file's package and `androidx.compose.runtime` are the only interpreted namespaces; the
 * Kotlin/coroutine floor is bridged. Each returns a plain value checkable against running the same code for real.
 */
object ComposerSpikeFixture {

    /** An applier for a composition that emits no nodes — every method is unreachable for a node-free content. */
    private class UnitApplier : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) {}
        override fun insertBottomUp(index: Int, instance: Unit) {}
        override fun remove(index: Int, count: Int) {}
        override fun move(from: Int, to: Int, count: Int) {}
        override fun onClear() {}
    }

    /**
     * Compose a node-free content that runs one `remember { }` and returns how many times the remember calculation
     * ran. A single composeInitial pass must run it exactly once (returns 1) — proving `setContent` drives the
     * interpreted Composer's slot table, group protocol, and remember through a full composition.
     */
    @JvmStatic
    fun rememberRunsOnce(): Int {
        var computeCount = 0
        val recomposer = Recomposer(EmptyCoroutineContext)
        val composition = Composition(UnitApplier(), recomposer)
        composition.setContent {
            remember { computeCount++ }
        }
        composition.dispose()
        return computeCount
    }

    /**
     * Compose content with SEVERAL sequential `remember` slots and a KEYED loop of nested remembers, concatenating
     * each remembered value in composition order. This exercises the Composer's positional memoization directly —
     * distinct slots per remember, a `key(i) { }` group per iteration — the exact machinery whose MISALIGNMENT (a
     * stale slot bleeding across unrelated remembers) broke the bridged-runtime flip. A correct interpreted composer
     * returns "start|a|b|k0|k1|k2|end"; any slot/group drift corrupts the order or repeats a value.
     */
    @JvmStatic
    fun multiSlotGroups(): String {
        val recomposer = Recomposer(EmptyCoroutineContext)
        val composition = Composition(UnitApplier(), recomposer)
        val out = StringBuilder()
        composition.setContent {
            out.append(remember { "start" })
            out.append('|').append(remember { "a" })
            out.append('|').append(remember { "b" })
            for (i in 0 until 3) {
                key(i) { out.append('|').append(remember { "k$i" }) }
            }
            out.append('|').append(remember { "end" })
        }
        composition.dispose()
        return out.toString()
    }

    /**
     * Phase 2: drive a REAL recomposition on the interpreted composer via the sanctioned Recomposer frame loop
     * (a bare `setContent` composes but never applies; a manual `recompose()`+`applyChanges()` throws). The content
     * reads a `MutableState` (subscribing its scope) and counts its runs; after a state write + pumped frames the
     * body must run exactly TWICE (initial + one recomposition). Returns the run count — 2 proves the interpreted
     * composer handles snapshot-driven invalidation, the Recomposer's suspend loop, and re-apply, not just an
     * initial pass. Mirrors the proven [dev.ide.interp.compose.RecompositionSkipTest] harness.
     */
    @JvmStatic
    fun recomposesOnStateChange(): Int {
        val state = mutableIntStateOf(0)
        var bodyRuns = 0
        val executor = Executors.newSingleThreadExecutor { Thread(it, "spike-recompose") }
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            return runBlocking {
                withTimeout(30_000) {
                    val clock = BroadcastFrameClock()
                    val recomposer = Recomposer(coroutineContext + dispatcher + clock)
                    val runJob = launch(dispatcher + clock) { recomposer.runRecomposeAndApplyChanges() }
                    recomposer.currentState.first { it == Recomposer.State.Idle } // loop up + apply observer registered

                    val composition = withContext(dispatcher) {
                        Composition(UnitApplier(), recomposer).also { c ->
                            c.setContent {
                                state.intValue // subscribe this scope to the state
                                bodyRuns++
                            }
                        }
                    }
                    withContext(dispatcher) { check(bodyRuns == 1) { "composes once initially, was $bodyRuns" } }

                    withContext(dispatcher) {
                        state.intValue = 1
                        Snapshot.sendApplyNotifications()
                    }
                    var frame = 0L
                    while (bodyRuns < 2 && frame < 240) {
                        clock.sendFrame(frame++)
                        delay(5)
                    }
                    withContext(dispatcher) { composition.dispose() }
                    recomposer.cancel()
                    runJob.cancel()
                    bodyRuns
                }
            }
        } finally {
            executor.shutdownNow()
        }
    }
}
