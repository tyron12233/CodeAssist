package dev.ide.platform

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Behavioural tests for [EngineScheduler] — the editor engine's priority lanes. These run on REAL threads
 * (not virtual time) because preemption is genuinely concurrent: a higher-priority caller flips a lower-
 * priority call's [CancelToken] from its own thread while that call runs on the engine worker, and the
 * heavy work bails at its next [EngineCancellation.checkCanceled] poll.
 *
 * The individual editor calls (completion, analyze, hints, …) are each fast; what matters — and what these
 * test — is how they INTERLEAVE on the single shared worker. [EngineTimeline] records the lane lifecycle so a
 * test (and a human) can see exactly what blocked what.
 */
class EngineSchedulerTest {

    /** A preemptible "busy for [ms]" block: polls cancellation like the real engine does between AST nodes. */
    private fun busy(ms: Long) {
        val end = System.nanoTime() + ms * 1_000_000
        while (System.nanoTime() < end) {
            EngineCancellation.checkCanceled()
            Thread.sleep(1)
        }
    }

    @Test
    fun interactivePreemptsARunningBackgroundPass() = runBlocking {
        val sched = EngineScheduler()
        val bgStarted = CompletableDeferred<Unit>()
        val bgOutcome = CompletableDeferred<String>()
        val bg = launch(Dispatchers.Default) {
            try {
                sched.background {
                    bgStarted.complete(Unit)
                    busy(5_000) // a long pass; should be cut short well before this
                    bgOutcome.complete("ran-to-completion")
                }
            } catch (e: EngineCanceledException) {
                bgOutcome.complete("preempted")
            }
        }
        bgStarted.await() // the background pass holds the worker
        val r = withTimeout(3_000) { sched.interactive { "completion" } } // must cut ahead, not wait 5s
        assertEquals("completion", r)
        assertEquals("preempted", withTimeout(2_000) { bgOutcome.await() })
        bg.join()
    }

    @Test
    fun backgroundPreemptsPreviewWhichThenRetriesToCompletion() = runBlocking {
        val sched = EngineScheduler(previewRetryDelay = kotlin.time.Duration.ZERO)
        val previewStarted = CompletableDeferred<Unit>()
        val previewDone = CompletableDeferred<String>()
        val pv = launch(Dispatchers.Default) {
            // preview() swallows preemption and retries internally, so it always eventually returns.
            previewDone.complete(sched.preview {
                previewStarted.complete(Unit)
                busy(800)
                "preview-rendered"
            })
        }
        previewStarted.await()
        // A background pass cuts ahead; the in-flight preview bails and the preview lane retries after it.
        sched.background { busy(50) }
        assertEquals("preview-rendered", withTimeout(5_000) { previewDone.await() })
        pv.join()
    }

    @Test
    fun keystrokeContentionCompletionCutsAheadOfTheBackgroundFanOut() = runBlocking {
        // Model one keystroke in a large file: the editor fans out analyze+hints+semantic+folding on the
        // BACKGROUND lane (they serialize on the one worker), then a completion request arrives on the
        // INTERACTIVE lane and must cut ahead instead of waiting for the whole fan-out.
        val timeline = EngineTimeline()
        val sched = EngineScheduler(observer = timeline.observer)

        // ART-ish durations: analyze dominates; the others are smaller.
        val fanOut = listOf("analyze" to 200L, "hints" to 40L, "semantic" to 60L, "folding" to 20L)
        val jobs = fanOut.map { (label, ms) ->
            launch(Dispatchers.Default) {
                try { sched.background(label) { busy(ms) } } catch (e: EngineCanceledException) { /* editor retries */ }
            }
        }
        Thread.sleep(30) // a couple of fan-out passes are now queued/running
        val completionLatency = timeMs {
            withTimeout(3_000) { sched.interactive("completion") { busy(30) } }
        }
        jobs.forEach { it.join() }

        val events = timeline.events()
        // Completion ran and was NEVER preempted (it's the top lane).
        assertTrue(events.any { it.lane == EngineLane.INTERACTIVE && it.phase == EnginePhase.DONE }, "completion never finished")
        assertFalse(events.any { it.lane == EngineLane.INTERACTIVE && it.phase == EnginePhase.PREEMPTED }, "completion must not be preempted")
        // It cut ahead: its latency is far below the full fan-out sum (320ms), not stuck behind analyze.
        assertTrue(completionLatency < 250, "completion waited too long ($completionLatency ms) — it didn't preempt")
        // At least one background pass was preempted by the completion (that's the cut-ahead, and the wasted work).
        assertTrue(events.any { it.lane == EngineLane.BACKGROUND && it.phase == EnginePhase.PREEMPTED }, "expected a preemption")

        println("\n=== keystroke contention timeline (completion latency=${completionLatency}ms) ===")
        println(timeline.render())
    }

    @Test
    fun aSuspendedInteractiveBlockFreesTheWorkerForOtherCalls() = runBlocking {
        val sched = EngineScheduler()
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<String>()
        val first = async(Dispatchers.Default) {
            sched.interactive {
                started.complete(Unit)
                // The K2-completion shape: hop off the worker for I/O mid-block. The serialized worker
                // must free at this suspension point, not sit blocked until the I/O answers.
                withContext(Dispatchers.IO) { gate.await() }
            }
        }
        started.await() // the first call holds the worker until it parks on its I/O
        // While the first call is parked, a second call runs to completion on the freed worker — it would
        // time out behind the first if suspension held the worker.
        val second = withTimeout(2_000) { sched.interactive { "second" } }
        assertEquals("second", second)
        gate.complete("items")
        assertEquals("items", withTimeout(2_000) { first.await() })
    }

    private inline fun timeMs(block: () -> Unit): Long {
        val t0 = System.nanoTime(); block(); return (System.nanoTime() - t0) / 1_000_000
    }
}

/** Records [EngineScheduler] lane lifecycle as a timeline — the instrument for "which is blocking". */
class EngineTimeline {
    data class Event(val tMs: Long, val lane: EngineLane, val phase: EnginePhase, val label: String)

    private val t0 = System.nanoTime()
    private val events = CopyOnWriteArrayList<Event>()

    val observer = EngineSchedulerObserver { lane, phase, label ->
        events += Event((System.nanoTime() - t0) / 1_000_000, lane, phase, label)
    }

    fun events(): List<Event> = events.toList()

    /** A readable one-line-per-event dump, e.g. `  12ms BACKGROUND analyze RUNNING`. */
    fun render(): String = events().joinToString("\n") { e ->
        "  %5dms  %-11s %-10s %s".format(e.tMs, e.lane, e.label.ifEmpty { "-" }, e.phase)
    }
}
