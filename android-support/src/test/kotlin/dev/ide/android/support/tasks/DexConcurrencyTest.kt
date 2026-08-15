package dev.ide.android.support.tasks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The in-process library-archive concurrency plan ([DexConcurrency.archivePlan]). Once the bootclasspath +
 * desugaring classpath are shared, once-parsed providers, a worker is light, so the archive must fan out across
 * cores on a heap where the old per-worker-loads-android.jar model ([DexConcurrency.plan]) serialized it.
 */
class DexConcurrencyTest {
    private val mb = 1024L * 1024

    @Test
    fun materialDeviceFansOutAcrossCoresInsteadOfSerializing() {
        // The measured on-device case: 4 cores, a 576 MB build heap, 52 library jars to archive. The old
        // per-worker-loads-android.jar model ([DexConcurrency.plan], PER_WORKER_BYTES = 512 MB) collapsed this to
        // ONE worker — ~450 MB idle, 3 cores unused, ~105 s serial. With shared providers it must fan out.
        val archive = DexConcurrency.archivePlanFor(52, cores = 4, maxMemoryBytes = 576 * mb)
        assertEquals(4, archive.workers, "archive fans out to all 4 cores on a 576 MB heap, was ${archive.workers}")
        assertTrue(archive.workers * archive.threadsPerInvocation >= 4, "keeps the cores busy")
    }

    @Test
    fun tightHeapStillCollapsesToOneWorker() {
        // A genuinely small heap can't fit two concurrent library working sets → stay serial (safe).
        val plan = DexConcurrency.archivePlanFor(52, cores = 4, maxMemoryBytes = 256 * mb)
        assertEquals(1, plan.workers)
    }

    @Test
    fun bigDesktopHeapIsCoreBoundedNotHeapBounded() {
        val plan = DexConcurrency.archivePlanFor(52, cores = 8, maxMemoryBytes = 4096 * mb)
        assertEquals(8, plan.workers, "capped by cores/MAX_WORKERS, not heap, on a large machine")
    }

    @Test
    fun neverExceedsTheInputCount() {
        val plan = DexConcurrency.archivePlanFor(taskCount = 2, cores = 8, maxMemoryBytes = 4096 * mb)
        assertEquals(2, plan.workers)
    }

    @Test
    fun singleInputStaysSingleThreaded() {
        // A lone input stays 1 worker × 1 thread: dexing on ART's small heap is GC-bound, so extra D8 threads add
        // GC pressure (another working set each), not speed — measured R.jar 3.4s@1t → 5.5s@4t. Fan out across
        // independent inputs instead.
        val plan = DexConcurrency.archivePlanFor(1, cores = 8, maxMemoryBytes = 4096 * mb)
        assertEquals(1, plan.workers)
        assertEquals(1, plan.threadsPerInvocation)
    }

    // --- InProcessDexGate: the process-wide budget that stops concurrent dex tasks over-committing the heap ---

    @Test
    fun gateGrantsAsManyCreditsAsArchivePlanGrantsWorkers() {
        // The gate must never throttle a single task below its own plan, so on a given heap its credit count
        // equals what [archivePlan] would grant workers (both reserve the shared android.jar-index base, then
        // divide the rest into ~96 MB units). At 576 MB that's 4 — matching the on-device archive fan-out above.
        assertEquals(4, InProcessDexGate.computePermits(576 * mb))
        assertEquals(
            DexConcurrency.archivePlanFor(52, cores = 8, maxMemoryBytes = 576 * mb).workers,
            InProcessDexGate.computePermits(576 * mb),
            "gate credits track archivePlan workers on the same heap",
        )
    }

    @Test
    fun tightHeapCollapsesTheGateToOneCredit() {
        // A 256 MB ART heap backs a single in-process working set: one credit, so the three scope merges
        // (each drawing a clamped merge cost) serialize instead of running 2-3 at once and OOMing.
        assertEquals(1, InProcessDexGate.computePermits(256 * mb))
    }

    @Test
    fun bigDesktopHeapIsNeverGateThrottled() {
        // A large heap mints many credits (capped only so the number stays tidy), so a desktop build's dex
        // tasks are never serialized by the gate — behaviour there is unchanged.
        assertTrue(InProcessDexGate.computePermits(4096 * mb) >= 8, "a 4 GB heap grants plenty of credits")
    }

    @Test
    fun mergeDrawsMoreCreditsThanAnArchive() {
        // A DexIndexed merge finalizes many class-dex at once — a bigger working set than one per-class archive
        // worker — so it draws several credits to one. That is what serializes concurrent merges on a tight heap.
        assertTrue(
            InProcessDexGate.unitsFor(256 * mb) > InProcessDexGate.unitsFor(96 * mb),
            "a merge's estimated working set costs more credits than an archive's",
        )
        assertEquals(1, InProcessDexGate.unitsFor(1 * mb), "a tiny working set still costs at least one credit")
    }
}
