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
}
