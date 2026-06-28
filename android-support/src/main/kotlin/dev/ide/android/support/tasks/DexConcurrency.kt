package dev.ide.android.support.tasks

/**
 * Picks how many dex invocations to run at once and how many threads each may use. Dexing many library jars
 * is embarrassingly parallel (independent per-jar archives), but each in-process D8 invocation holds
 * `android.jar` + its input in the *app's* heap — so on a phone (a small, fixed ART heap) running several at
 * once risks OOM, while on a desktop JVM (a large heap + many cores) serial dexing wastes the machine.
 *
 * The plan is therefore bounded by BOTH cores and available heap: workers ≈ min(cores, heap-budget), and each
 * worker gets `cores / workers` D8 threads so `workers × threadsPerInvocation` stays near the core count
 * instead of oversubscribing. On a 256 MB ART heap this collapses to 1 worker with a few D8 threads (safe,
 * still uses cores within the one invocation); on an 8-core desktop with GBs of heap it fans out to ~8.
 */
object DexConcurrency {
    data class Plan(val workers: Int, val threadsPerInvocation: Int)

    /**
     * Rough per-invocation heap budget: android.jar parse + the library desugaring classpath (now passed to
     * each archive) + the input's working set. Deliberately generous so a phone fans out cautiously — peak
     * heap, not cores, is what OOMs an in-process dexer.
     */
    private const val PER_WORKER_BYTES = 512L * 1024 * 1024
    private const val MAX_WORKERS = 8

    /** At or below this max heap (ART `largeHeap` territory; a desktop JVM is far above), each D8/merge
     *  invocation runs SINGLE-THREADED. Device measurement (docs/build-process-isolation.md) showed a clean
     *  build pegging a 384 MB heap at ~365 MB during dexBuilder/merge with `workers` already collapsed to 1 —
     *  so the remaining lever is D8's INTERNAL thread pool, where every extra thread holds another working set
     *  of classes being desugared/dexed. Trading dex speed for a smaller peak is the right call on a phone. */
    private const val TIGHT_HEAP_BYTES = 640L * 1024 * 1024

    /** A plan for dexing [taskCount] independent inputs. Reads live cores + max heap each call (cheap). */
    fun plan(taskCount: Int, runtime: Runtime = Runtime.getRuntime()): Plan {
        if (taskCount <= 1) return Plan(1, threadsFor(1, runtime))
        val cores = runtime.availableProcessors().coerceAtLeast(1)
        // Leave headroom: only ~half of max heap is fair game for concurrent dexers (the rest is the IDE,
        // any concurrent compile, and GC slack).
        val heapWorkers = ((runtime.maxMemory().toDouble() * 0.5) / PER_WORKER_BYTES).toInt().coerceAtLeast(1)
        val workers = minOf(cores, heapWorkers, taskCount, MAX_WORKERS).coerceAtLeast(1)
        return Plan(workers, threadsFor(workers, runtime))
    }

    /** Split the cores across the workers so the product stays ~= cores; cap so a lone worker doesn't spawn 32.
     *  On a tight ART heap, force single-threaded — fewer D8 threads = a smaller peak heap (see [TIGHT_HEAP_BYTES]). */
    private fun threadsFor(workers: Int, runtime: Runtime): Int {
        if (runtime.maxMemory() <= TIGHT_HEAP_BYTES) return 1
        val cores = runtime.availableProcessors().coerceAtLeast(1)
        return (cores / workers).coerceIn(1, 4)
    }
}
