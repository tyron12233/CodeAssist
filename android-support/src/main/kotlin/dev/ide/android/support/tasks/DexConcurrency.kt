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

    /** Archive-step heap model (see [archivePlan]): the shared, once-parsed classpath index (android.jar etc.)
     *  reserved off the top, plus one library's D8 working set per concurrent worker. Sized from on-device
     *  measurement (peak ~156 MB with one worker over a ~70 MB base). Far below [PER_WORKER_BYTES] because the
     *  bootclasspath is no longer loaded per worker — the reason the archive can now fan out across cores. */
    private const val ARCHIVE_SHARED_BASE_BYTES = 96L * 1024 * 1024
    private const val ARCHIVE_PER_WORKER_BYTES = 96L * 1024 * 1024
    private const val ARCHIVE_HEAP_FRACTION = 0.85

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

    /**
     * Concurrency for the IN-PROCESS library ARCHIVE step (per-class-file dexing of many independent jars) once
     * the bootclasspath + desugaring classpath are shared, once-parsed resource providers (see `SharedDexClasspath`)
     * rather than re-loaded per invocation. That collapses each worker's footprint from "android.jar + classpath in
     * its own heap" (the [plan]/[PER_WORKER_BYTES] model, which forced a single worker on a phone) to just the ONE
     * library's D8 working set on top of the shared, one-time index — measured at ~85 MB per worker over a ~70 MB
     * shared base. So the archive can fan out across cores on a heap where [plan] would serialize it: workers ≈
     * min(cores, (heap·0.85 − sharedBase) / perWorker). Still collapses to 1 on a genuinely tiny heap.
     */
    fun archivePlan(taskCount: Int, runtime: Runtime = Runtime.getRuntime()): Plan =
        archivePlanFor(taskCount, runtime.availableProcessors().coerceAtLeast(1), runtime.maxMemory())

    /** Pure [archivePlan] math over explicit cores + max heap (testable without a real [Runtime]). */
    internal fun archivePlanFor(taskCount: Int, cores: Int, maxMemoryBytes: Long): Plan {
        // NB: DON'T add D8-internal threads to squeeze a lone/big input on ART. Dexing on ART's small heap is
        // GC-bound, not CPU-bound — extra D8 threads each hold another working set of classes being desugared, so
        // more threads means more GC, not more speed (measured: R.jar 3.4s at 1 thread → 5.5s at 4). Prefer
        // worker-level fan-out (independent inputs) and 1 thread each.
        if (taskCount <= 1) return Plan(1, 1)
        val forWorkers = (maxMemoryBytes.toDouble() * ARCHIVE_HEAP_FRACTION) - ARCHIVE_SHARED_BASE_BYTES
        val heapWorkers = (forWorkers / ARCHIVE_PER_WORKER_BYTES).toInt().coerceAtLeast(1)
        val workers = minOf(cores.coerceAtLeast(1), heapWorkers, taskCount, MAX_WORKERS).coerceAtLeast(1)
        val threads = (cores.coerceAtLeast(1) / workers).coerceIn(1, 2)
        return Plan(workers, threads)
    }

    /** Split the cores across the workers so the product stays ~= cores; cap so a lone worker doesn't spawn 32.
     *  On a tight ART heap, force single-threaded — fewer D8 threads = a smaller peak heap (see [TIGHT_HEAP_BYTES]). */
    private fun threadsFor(workers: Int, runtime: Runtime): Int {
        if (runtime.maxMemory() <= TIGHT_HEAP_BYTES) return 1
        val cores = runtime.availableProcessors().coerceAtLeast(1)
        return (cores / workers).coerceIn(1, 4)
    }
}
