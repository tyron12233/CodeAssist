package dev.ide.android

import android.content.Context
import dev.ide.android.support.tasks.DexConcurrency
import dev.ide.android.support.tools.D8Dexer
import dev.ide.android.support.tools.D8InProcessDexer
import dev.ide.android.support.tools.Dexer
import dev.ide.android.support.tools.MergePlan
import dev.ide.android.support.tools.OffHeapArchiveDexer
import dev.ide.android.support.tools.ToolResult
import dev.ide.platform.log.Log
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * On-device D8 dexer for the dex MERGE step ([dex]), run in a FORKED command-line VM so the merge gets a heap
 * above the app cap — the merge (finalizing all class-dex at once) is the debug-path memory peak.
 *
 * The per-class archive step ([dexArchive]) stays in-process for the common incremental case (a few changed
 * classes / small library jars): forking that costs more in VM-spawn overhead than it saves. But a LARGE
 * archive invocation — a clean build's whole-project jar, or a big library — is itself a meaningful in-process
 * peak (it parses android.jar and touches its desugaring classpath), so above [ARCHIVE_FORK_BYTES] of input it
 * is routed through the same forked VM. D8's `--file-per-class-file --intermediate` archive mode works
 * identically in the subprocess, so the merge still finalizes the intermediates.
 *
 * Shares the R8 fork machinery ([R8ForkSupport]) and the same "R8 execution" / "R8 forked-VM heap" settings
 * as [ForkedR8Shrinker], so In-process mode keeps the merge in-process too. Resolved lazily; self-falls-back
 * to in-process D8 when forking isn't usable here.
 */
class ForkedD8Dexer(
    context: Context,
    private val modeProvider: () -> String? = { null },
    private val maxHeapMbProvider: () -> Int? = { null },
    /** Input size (MB) at/above which the archive step forks (the "Off-heap dexing threshold" setting); null
     *  → [ARCHIVE_FORK_DEFAULT_MB]. Read per call so a settings change applies on the next build. */
    private val archiveForkMbProvider: () -> Int? = { null },
    /** The "Max concurrent dex forks" setting (0/null = auto, sized from device RAM). Read per call so a
     *  settings change applies on the next build. */
    private val forkConcurrencyProvider: () -> Int? = { null },
    private val fallback: Dexer = D8InProcessDexer(),
) : Dexer, OffHeapArchiveDexer {
    private val log = Log.logger("ide.mem")
    private val appContext = context.applicationContext

    @Volatile
    private var note: String? = null

    /** The `-Xmx` (MB) the forked merge VM was granted, captured in [resolve]; null until resolved or when the
     *  merge runs in-process. Sizes how many forks the device's available RAM allows in [mergePlan]. */
    @Volatile
    private var forkXmxMb: Int? = null
    private val delegate: Dexer by lazy { resolve() }

    override fun dex(
        inputs: List<Path>, androidJar: Path, minApi: Int, release: Boolean, outDir: Path,
        threads: Int, desugaredLibConfig: Path?,
    ): ToolResult {
        // A merge that forks holds a global fork permit so the parallel merge tasks don't over-commit RAM
        // (in-process merges don't fork → no gating).
        val r = onForkGate { delegate.dex(inputs, androidJar, minApi, release, outDir, threads, desugaredLibConfig) }
        return note?.let { r.copy(log = listOf(it) + r.log) } ?: r
    }

    // Small archives stay in-process; a large batch (clean-build project jar / big library) is the in-process
    // archive peak, so route it through the forked VM — but only when forking is actually available (else
    // [delegate] IS [fallback] and this is a plain in-process call).
    override fun dexArchive(
        inputs: List<Path>, classpath: List<Path>, androidJar: Path, minApi: Int, release: Boolean,
        outDir: Path, threads: Int, desugaredLibConfig: Path?,
    ): ToolResult {
        val inputBytes = inputs.sumOf { runCatching { if (Files.exists(it)) Files.size(it) else 0L }.getOrDefault(0L) }
        val thresholdBytes = (archiveForkMbProvider()?.takeIf { it > 0 } ?: ARCHIVE_FORK_DEFAULT_MB).toLong() * 1024 * 1024
        val dexer = if (inputBytes >= thresholdBytes && delegate !== fallback) {
            log.info("forked-D8 archive: ${inputBytes / (1024 * 1024)}MB input ≥ ${thresholdBytes / (1024 * 1024)}MB → forked VM (off the app heap)")
            delegate
        } else {
            fallback
        }
        // Gate only the forked archive (dexer === delegate); an in-process archive is bounded by the app heap.
        val call = { dexer.dexArchive(inputs, classpath, androidJar, minApi, release, outDir, threads, desugaredLibConfig) }
        return if (dexer === delegate) onForkGate(call) else call()
    }

    /** Run [body] holding a global fork permit when this dexer is actually forking; otherwise run it directly. */
    private fun onForkGate(body: () -> ToolResult): ToolResult =
        if (delegate === fallback) body() else R8ForkSupport.withForkPermit(forkBudget(), body)

    /** Concurrent-fork budget for this device + the resolved fork heap, honouring the user's setting. */
    private fun forkBudget(): Int =
        R8ForkSupport.forkBudget(appContext, forkXmxMb ?: DEFAULT_XMX_MB, forkConcurrencyProvider())

    private fun resolve(): Dexer {
        if (modeProvider()?.lowercase() == MODE_INPROCESS) {
            note = null
            log.info("forked-D8 merge: in-process (R8 execution = In-process)")
            return fallback
        }
        val launcher = R8ForkSupport.launcher() ?: return inProcess("no forked-VM launcher")
        val dexes = R8ForkSupport.extractR8Dexes(appContext) ?: return inProcess("R8 runtime asset unavailable")
        val requested = (maxHeapMbProvider() ?: DEFAULT_XMX_MB).coerceAtLeast(MIN_XMX_MB)
        val candidates = (listOf(requested) + FALLBACK_LADDER.filter { it < requested }).distinct()
        for (xmx in candidates) {
            if (R8ForkSupport.canFork(launcher, dexes, xmx)) {
                note = "dex merge: forked VM, ${xmx}MB heap"
                forkXmxMb = xmx
                log.info("forked-D8 merge: runs in a forked $launcher -Xmx${xmx}m (${dexes.size} dex)")
                return D8Dexer(dexes.map { it.toPath() }, Paths.get(launcher), listOf("-Xmx${xmx}m"))
            }
        }
        return inProcess("the device wouldn't start a forked VM at ${candidates.last()}MB+")
    }

    private fun inProcess(reason: String): Dexer {
        note = "dex merge: in-process ($reason)"
        forkXmxMb = null
        log.warn("forked-D8 merge: $reason → in-process D8")
        return fallback
    }

    /**
     * Batch + cautious-parallel merge plan for the FORKED path. A forked merge VM runs off the app heap with
     * its own big `-Xmx` ([forkXmxMb]), so the app-heap-bounded `DexConcurrency` (which collapses to 1 worker
     * on a phone) is the wrong constraint — it forces N per-library merges to fork one VM at a time, the serial
     * flood. Instead:
     *  - **batch**: a 1.5 GB fork merges many libraries at once, so coalesce the [inputCount] buckets into one
     *    group per concurrent fork (collapsing N forks to a handful), and
     *  - **parallelize cautiously**: run as many forks at once as the device's *available* RAM affords each
     *    fork's `-Xmx`, capped low ([MAX_CONCURRENT_FORKS]) and by cores, with a safety margin so the
     *    low-memory killer doesn't take the app or the forks. availMem (not totalMem) keeps it honest under
     *    pressure → 1 fork merging everything, still far better than N sequential ones.
     *
     * Returns null (defer to the in-process default) when the merge isn't actually forking here.
     */
    override fun mergePlan(inputCount: Int): MergePlan? {
        if (delegate === fallback) return null
        val xmx = forkXmxMb ?: return null
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val concurrency = forkBudget()
        // One batch per concurrent fork: collapses the per-library flood to `concurrency` forked invocations.
        val maxInvocations = concurrency.coerceAtMost(inputCount.coerceAtLeast(1))
        val threads = (cores / concurrency).coerceIn(1, 4)
        log.info("forked-D8 merge plan: $inputCount input(s) → $maxInvocations forked invocation(s), $concurrency concurrent @ ${xmx}MB ($cores cores)")
        return MergePlan(maxInvocations = maxInvocations, concurrency = concurrency, threadsPerInvocation = threads)
    }

    // --- OffHeapArchiveDexer: parallel, off-heap library archiving for dexBuilder -------------------------------

    override fun offHeapArchivePlan(jarCount: Int): MergePlan {
        // Library archiving now runs IN-PROCESS (see [dexArchiveOffHeap]) so it reuses the shared, once-parsed
        // android.jar + desugaring-classpath providers (AGP's ClassFileProviderFactory) instead of re-parsing
        // android.jar in a fresh forked VM for every library — the dominant cold-dexBuilder cost. The archive
        // step is not the memory peak (the dex MERGE is, and that still forks), so size the concurrency via
        // [DexConcurrency.archivePlan] — the light shared-provider footprint (~85 MB/worker), which fans out
        // across cores instead of the [plan] model that serialized to one worker on a phone.
        val p = DexConcurrency.archivePlan(jarCount)
        log.info("in-process archive plan: $jarCount library jar(s) → ${p.workers} worker(s) × ${p.threadsPerInvocation} thread(s) (shared classpath providers)")
        return MergePlan(maxInvocations = jarCount, concurrency = p.workers, threadsPerInvocation = p.threadsPerInvocation)
    }

    override fun dexArchiveOffHeap(
        jar: Path, classpath: List<Path>, androidJar: Path, minApi: Int, release: Boolean, outDir: Path,
        threads: Int, desugaredLibConfig: Path?,
    ): ToolResult {
        // In-process, NOT forked. A per-library archive is small and is not the memory peak, and running it in
        // process lets it reuse the shared android.jar/classpath providers (parsed once for the whole build)
        // rather than re-opening + re-indexing android.jar in a new VM for each of the dozens of libraries. The
        // forked VM stays reserved for the dex merge (the real peak, see [dex]).
        return fallback.dexArchive(listOf(jar), classpath, androidJar, minApi, release, outDir, threads, desugaredLibConfig)
    }

    private companion object {
        const val MODE_INPROCESS = "inprocess"
        // The merge peaks ~365MB; reuse R8's heap ladder (more than enough, one shared setting).
        const val DEFAULT_XMX_MB = 1536
        const val MIN_XMX_MB = 768
        val FALLBACK_LADDER = listOf(2048, 1536, 1024, 768)

        // Default archive-fork threshold (MB) when the setting is unset: archive inputs at/above this size fork
        // (clean-build project jar / big library); smaller ones (the incremental case) stay in-process.
        const val ARCHIVE_FORK_DEFAULT_MB = 8
    }
}
