package dev.ide.interp.compose.spike

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.currentComposer
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Executors
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
}
