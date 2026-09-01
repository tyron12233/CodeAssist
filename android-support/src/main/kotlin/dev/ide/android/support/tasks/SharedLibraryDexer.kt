package dev.ide.android.support.tasks

import dev.ide.android.support.tools.DexDiagnostics
import dev.ide.android.support.tools.Dexer
import dev.ide.android.support.tools.OffHeapArchiveDexer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Library (sub-module + external jar) dexing with the content-addressed shared cache — extracted from
 * [DexArchiveBuilderTask] so BOTH the Android build AND the layout preview's real-view runtime run the SAME
 * code against the SAME cache. Each library jar is dexed to a per-class `DexFilePerClassFile` archive under
 * `<root>/<contentHash>/`, and seeded into `<dexCacheRoot>/<cacheTag>/<contentHash>/` so a library is D8-dexed
 * ONCE per machine and reused across builds and previews (whoever dexes it first wins). Incremental per-jar:
 * a changed dependency re-dexes alone.
 *
 * Parameterized by callbacks instead of a build `TaskContext`: [log], [checkCanceled], [reportDiagnostics], and
 * [onFailure] (the humanized first error). The build wires these to the task context; the preview wires them to
 * its logger.
 */
class SharedLibraryDexer(
    private val dexer: Dexer,
    private val androidJar: Path,
    private val minApi: Int,
    private val release: Boolean,
    private val dexCacheRoot: Path?,
    private val desugaredLibConfig: Path? = null,
    private val log: (String) -> Unit = {},
    private val checkCanceled: () -> Unit = {},
    private val reportDiagnostics: (List<String>) -> Unit = {},
    private val onFailure: (String) -> Unit = {},
) {
    /** The library desugaring universe (content-hash-deduped, class-indexed) + the shared-cache digest keyed to
     *  it, so a bucket is only reused under an identical universe. */
    class Universe(
        val hashOf: Map<Path, String>,
        val universeByHash: Map<String, Path>,
        val classesOf: Map<Path, Set<String>>,
        val desugarDigest: String,
        val desugaringNeeded: Boolean,
    )

    /**
     * A worker budget SHARED by scopes being archived at the same time. Each scope sizing itself against the whole
     * machine would multiply the fan-out by the number of concurrent scopes; drawing workers from one gate instead
     * keeps total concurrency at what the largest scope would have used alone, which is what the heap model behind
     * [DexConcurrency.archivePlan] was calibrated for.
     */
    class ScopeBudget(val workers: Int, val threadsPerInvocation: Int) {
        internal val gate = Semaphore(workers.coerceAtLeast(1))
    }

    /** A cache key for the desugaring config: empty when disabled (byte-identical namespaces to a
     *  no-desugaring build), a content hash when enabled (toggling busts stale dex). */
    fun desugarConfigKey(): String = desugarConfigKey(desugaredLibConfig)

    /**
     * Build the desugaring universe for [libJars] (the sub+ext scope jars, or the preview's library classpath).
     * D8 consults this classpath only to resolve type hierarchies for interface-method (< 24) / lambda (< 26) /
     * core-library desugaring; when neither applies the (expensive) class-name indexing is skipped, but the
     * hash-deduped universe stays so the shared-cache digest is byte-identical (no cache bust). [hashCacheDir]
     * holds the path+size+mtime hash sidecar so unchanged libraries aren't re-hashed.
     *
     * [extraDexJars] are jars that must be DEXED (they get a [Universe.hashOf] entry so [dexScope] archives them
     * into their own content-hash bucket) but are deliberately kept OUT of the desugaring universe AND its cache
     * digest: the app's generated `R.jar`. `R` holds only resource-id constants (no interface hierarchy any
     * library desugars against), yet its content changes on every resource edit. Folding it into [desugarDigest]
     * would re-key the whole shared cache each time `R` changed, re-dexing every stable library under a fresh
     * namespace; excluding it means only `R.jar` itself re-dexes on a resource edit.
     */
    fun computeUniverse(libJars: List<Path>, hashCacheDir: Path, extraDexJars: List<Path> = emptyList()): Universe =
        computeUniverse(libJars, hashCacheDir, minApi, desugaredLibConfig, extraDexJars)

    /** One library the scope has to dex: its content hash, the jar, and its `.class` entries (needed for the
     *  desugaring-classpath exclusion and the post-dex completeness check). Held only for MISSES. */
    private class Miss(val hash: String, val jar: Path, val classes: Set<String>)

    /**
     * Archive each of [jars] into `<root>/<contentHash>/`. Three tiers of reuse, then dex only the true misses,
     * in parallel bounded by [DexConcurrency] (or the off-heap plan, or a caller-supplied [budget] shared with
     * other scopes dexing at the same time): 1) the module bucket is complete → reuse; 2) the shared cross-project
     * cache has it → copy; 3) dex it once, then seed the shared cache. [u] is the shared desugaring universe from
     * [computeUniverse].
     */
    suspend fun dexScope(jars: List<Path>, root: Path, u: Universe, budget: ScopeBudget? = null): Boolean {
        Files.createDirectories(root)
        // One pass over the scope: dedup by content hash, collect the buckets worth keeping, and collect the
        // MISSES to dex. A bucket carrying a completeness sentinel is accepted without opening its jar at all
        // ([DexArchives.bucketVerified]) — so a warm scope no longer reads every library's zip directory and stats
        // one file per class, and no class-name set is retained for a library it isn't going to dex.
        val keep = HashSet<String>()
        val todo = ArrayList<Miss>()
        for (jar in jars) {
            val hash = u.hashOf[jar] ?: continue
            if (!keep.add(hash)) continue                        // content-identical jars share one bucket
            val bucket = root.resolve(hash)
            if (DexArchives.bucketVerified(bucket)) continue
            val classes = DexArchives.classNamesOf(jar)
            // Skip jars with no class entries (a resource-only AAR's classes.jar): they dex to nothing, and an
            // empty/zero-entry jar can't be opened by ART's zip layer — never hand it to D8. Dropping the hash
            // again keeps them out of [keep], exactly as when they never entered the map.
            if (classes.isEmpty()) { keep.remove(hash); continue }
            if (!DexArchives.bucketComplete(bucket) { classes }) todo.add(Miss(hash, jar, classes))
        }
        if (todo.isEmpty()) {
            DexArchives.prune(root, keep); return true
        }
        // Dispatch LARGEST-first (by class count): with a bounded worker pool, starting the big/straggler libraries
        // early packs the parallel schedule tighter, so a slow library isn't left running alone at the tail while
        // other cores idle (measured: one lib ~6.6s vs ~0.7s avg). Byte-identical output; only scheduling changes.
        todo.sortByDescending { it.classes.size }

        val offHeap = dexer as? OffHeapArchiveDexer
        val workers: Int
        val threadsPer: Int
        if (budget != null) {
            // Concurrently-running scopes share one budget so the fan-out isn't multiplied per scope.
            workers = budget.workers; threadsPer = budget.threadsPerInvocation
        } else if (offHeap != null) {
            val p = offHeap.offHeapArchivePlan(todo.size); workers = p.concurrency; threadsPer = p.threadsPerInvocation
        } else {
            // In-process archiving with shared classpath providers: each worker is light (one library's working
            // set over the shared android.jar index), so fan out across cores rather than the [plan] model that
            // serializes on a phone (it assumed each worker loads android.jar itself). See [archivePlan].
            val p = DexConcurrency.archivePlan(todo.size); workers = p.workers; threadsPer = p.threadsPerInvocation
        }
        val sem = budget?.gate ?: Semaphore(workers.coerceAtLeast(1))
        val ok = AtomicBoolean(true)
        // Perf instrumentation: parallelism factor (sum-per-lib / wall) + per-lib cost, so a benchmark can see
        // whether the fresh-dex cost is under-parallelized, per-lib D8 compute, or dominated by a few big libs.
        val sumMs = java.util.concurrent.atomic.AtomicLong(0)
        val maxMs = java.util.concurrent.atomic.AtomicLong(0)
        val wallStart = System.nanoTime()
        log("dexScope ${root.fileName}: dexing ${todo.size} lib(s), $workers worker(s) x $threadsPer thread(s), desugar=${u.desugaringNeeded}")
        coroutineScope {
            todo.map { miss ->
                async(Dispatchers.IO) {
                    sem.withPermit {
                        checkCanceled()
                        // Desugaring classpath = the library universe minus this jar's own content (by hash) and
                        // class-deduped (so a different jar can't redefine this jar's types, nor any type twice).
                        val others = u.universeByHash.filterKeys { it != miss.hash }.values
                        val classpath = DexArchives.classpathFor(others, u.classesOf, exclude = u.classesOf[miss.jar] ?: emptySet())
                        val t0 = System.nanoTime()
                        val okOne = dexOneLibrary(miss.jar, root.resolve(miss.hash), miss.hash, classpath, u.desugarDigest, threadsPer, offHeap, miss.classes)
                        val ms = (System.nanoTime() - t0) / 1_000_000
                        sumMs.addAndGet(ms); maxMs.updateAndGet { maxOf(it, ms) }
                        if (!okOne) ok.set(false)
                    }
                }
            }.awaitAll()
        }
        val wallMs = (System.nanoTime() - wallStart) / 1_000_000
        val par = if (wallMs > 0) sumMs.get().toDouble() / wallMs else 1.0
        log("dexScope ${root.fileName}: wall=${wallMs}ms sum-per-lib=${sumMs.get()}ms parallelism~${"%.1f".format(par)}x avg=${sumMs.get() / todo.size.coerceAtLeast(1)}ms max-lib=${maxMs.get()}ms")
        DexArchives.prune(root, keep)
        return ok.get()
    }

    /** Tier 2/3 for one library: copy from the shared cache on a COMPLETE hit, else dex it and seed the cache
     *  under the [desugarDigest]-keyed namespace. Off-heap dexing (forked VM) still archives into [bucket]. */
    private fun dexOneLibrary(
        jar: Path, bucket: Path, hash: String, classpath: List<Path>, desugarDigest: String,
        threads: Int, offHeap: OffHeapArchiveDexer?, jarClasses: Set<String>,
    ): Boolean {
        val shared = dexCacheRoot?.resolve(cacheTag(desugarDigest))?.resolve(hash)
        if (shared != null && DexArchives.bucketComplete(shared, jarClasses)) {
            DexArchives.clearDir(bucket)
            val copiedDex = DexArchives.copyDir(shared, bucket)
            // The copy source was just verified complete and [copyDir] either finishes or throws, so the module
            // bucket is now provably complete too — record it against what actually landed on disk, which also
            // covers the case where the shared bucket predates sentinels. The count comes from the copy's own walk;
            // re-walking each bucket here cost ~0.4s of a ~6s build across a 51-library classpath.
            DexArchives.markBucketVerified(bucket, copiedDex)
            log("dex cache hit: ${jar.fileName}")
            return true
        }
        DexArchives.clearDir(bucket); Files.createDirectories(bucket)
        // Strip @kotlin.Metadata from a Kotlin library before dexing so the bundled D8's older kotlin-metadata
        // parser doesn't warn per class (and can't hit the rewriter drop path); a pure-Java jar passes through
        // untouched. Keyed by the ORIGINAL jar's content hash (the class SET is unchanged — only an annotation is
        // removed), so the bucket and its completeness check are identical. Only reached on a miss.
        val program = DexArchives.strippedJar(jar, bucket.resolveSibling("${bucket.fileName}.stripped.jar"))
        val r = try {
            offHeap?.dexArchiveOffHeap(program, classpath, androidJar, minApi, release, bucket, threads, desugaredLibConfig)
                ?: dexer.dexArchive(listOf(program), classpath, androidJar, minApi, release, bucket, threads, desugaredLibConfig)
        } finally {
            if (program != jar) runCatching { Files.deleteIfExists(program) }
        }
        r.log.forEach(log)
        reportDiagnostics(r.log)
        if (!r.success) {
            DexDiagnostics.firstError(r.log)?.let(onFailure)
            log("dex archive failed for ${jar.fileName}")
            return false
        }
        // Verify completeness: a dexer can report success yet drop a class (then it's ABSENT → a runtime
        // ClassNotFoundException). Fail naming the casualties rather than seed a partial bucket.
        val missing = jarClasses.filter { DexArchives.dexable(it) && !Files.isRegularFile(bucket.resolve(DexArchives.dexRelOf(it))) }
        if (missing.isNotEmpty()) {
            val names = missing.take(3).joinToString { it.removeSuffix(".class").replace('/', '.') }
            val msg = "dex archive for ${jar.fileName} dropped ${missing.size} class(es) (e.g. $names) — they would be absent. The dexer reported success but produced no .dex for them."
            onFailure(msg)
            log("error: $msg")
            return false
        }
        // The per-class check above just proved this bucket complete; record it so no later build (or preview
        // readiness scan) has to prove it again. Written BEFORE publishing so the shared copy carries it too.
        DexArchives.markBucketVerified(bucket)
        if (shared != null && !DexArchives.bucketComplete(shared, jarClasses)) {
            DexArchives.replaceInCache(bucket, shared)
        }
        return true
    }

    /** Shared-cache namespace: a dexed archive is only reusable for the same min-api, mode, and dex format, plus
     *  the desugaring classpath ([desugarDigest]) WHEN desugaring applies. An empty digest (no desugaring) drops
     *  the `-cp` component entirely, so the key is own-content-only (AGP's `DexingNoClasspathTransform`) and a
     *  library is shared across projects with different classpaths. Byte-identical to the build's key so the
     *  build and the preview share buckets. */
    fun cacheTag(desugarDigest: String): String = cacheTag(minApi, release, desugarDigest)

    companion object {
        /** At and above this `minApi`, D8 desugars nothing by itself, so a library's dex depends only on the
         *  library. Below it (or with core-library desugaring on) the output depends on the desugaring
         *  CLASSPATH too, which is why the cache key has to fold the whole library set. */
        const val DESUGAR_FREE_MIN_API = 26

        /**
         * Whether the shared-cache key for [minApi] and this desugaring config depends ONLY on a library's own
         * content, so any two dexings of that library land in the same bucket.
         *
         * When it does not — the `-cp<digest>` component of [cacheTag], which folds a digest of the whole
         * library universe — a bucket written for one library set is unreachable to a build whose set differs
         * by a single jar. Anything that dexes AHEAD of a build has to check this: it cannot know the exact set
         * the next build will resolve (an instrumented debug build appends the app-log bridge runtime), so
         * without the guarantee it is writing buckets that build will ignore and dex again.
         */
        fun cacheKeyIsOwnContentOnly(minApi: Int, desugaredLibConfig: Path?): Boolean =
            minApi >= DESUGAR_FREE_MIN_API && desugarConfigKey(desugaredLibConfig).isEmpty()

        /**
         * Static [computeUniverse] — builds the desugaring universe with no [Dexer]/instance. The layout-preview
         * readiness check ([undexedLibraries]) calls this; the instance overload delegates here, so the universe
         * (and therefore the shared-cache key) is byte-identical between build, preview, and readiness check.
         */
        fun computeUniverse(
            libJars: List<Path>, hashCacheDir: Path, minApi: Int, desugaredLibConfig: Path?,
            extraDexJars: List<Path> = emptyList(),
        ): Universe {
            val libUniverse = libJars.filter { Files.exists(it) && Files.size(it) > 0L }
            val extra = extraDexJars.filter { Files.exists(it) && Files.size(it) > 0L }
            val hashCache = DexArchives.HashCache(hashCacheDir)
            val hashOf = (libUniverse + extra).associateWith { hashCache.hashOf(it) }
            hashCache.flush()
            val universeByHash = LinkedHashMap<String, Path>()
            for (j in libUniverse) hashOf[j]?.let { universeByHash.putIfAbsent(it, j) }
            val cfgKey = desugarConfigKey(desugaredLibConfig)
            val desugaringNeeded = minApi < DESUGAR_FREE_MIN_API || cfgKey.isNotEmpty()
            val classesOf =
                if (desugaringNeeded) universeByHash.values.associateWith { DexArchives.classNamesOf(it) } else emptyMap()
            val desugarDigest = if (!desugaringNeeded) "" else {
                val desugarExtra = if (cfgKey.isEmpty()) emptyList() else listOf("cfg:$cfgKey")
                DexArchives.digestOf(universeByHash.keys + desugarExtra)
            }
            return Universe(hashOf, universeByHash, classesOf, desugarDigest, desugaringNeeded)
        }

        fun desugarConfigKey(desugaredLibConfig: Path?): String =
            desugaredLibConfig?.takeIf { Files.exists(it) }?.let { DexArchives.fileHash(it) } ?: ""

        fun cacheTag(minApi: Int, release: Boolean, desugarDigest: String): String =
            "minApi$minApi-${if (release) "release" else "debug"}-$DEX_CACHE_FORMAT" +
                if (desugarDigest.isEmpty()) "" else "-cp$desugarDigest"

        /**
         * The [jars] whose per-class dex is NOT yet in the shared cache under [dexCacheRoot] — the ones
         * [dexScope] would have to D8-dex. Pure (no dexing, no [Dexer]); it consults the SAME bucket key
         * [dexOneLibrary] reuses from, so an empty result means the layout preview can class-load with zero
         * dexing (all libraries already dexed by a build or a prior preview). [u] is from [computeUniverse].
         */
        fun undexedLibraries(jars: List<Path>, u: Universe, dexCacheRoot: Path, minApi: Int, release: Boolean): List<Path> {
            val tag = cacheTag(minApi, release, u.desugarDigest)
            val out = ArrayList<Path>()
            val seen = HashSet<String>()
            for (jar in jars) {
                val h = u.hashOf[jar] ?: continue
                if (!seen.add(h)) continue                    // content-identical jars share one bucket
                val bucket = dexCacheRoot.resolve(tag).resolve(h)
                if (DexArchives.bucketVerified(bucket)) continue   // already dexed; no need to open the jar
                val cls = DexArchives.classNamesOf(jar)
                if (cls.isEmpty()) continue                   // a resource-only jar dexes to nothing → never "undexed"
                // NB `out += jar` binds List<Path>.plus(Iterable) since a Path IS Iterable<Path> — use add().
                if (!DexArchives.bucketComplete(bucket) { cls }) out.add(jar)
            }
            return out
        }

        /**
         * The build's dex-scope universes for the layout preview's library classpath, split EXACTLY as the
         * [dev.ide.android.support.tasks.DexArchiveBuilderTask] does: an EXTERNAL library desugars against the
         * external set ALONE (deps point down — it never sees your modules), a dependency-MODULE output against
         * sub+ext. The preview MUST reuse this same split. A single combined universe over (external + module)
         * hashes to a different [Universe.desugarDigest] — and therefore a different cache tag — whenever
         * desugaring is active (minApi < 26, or core-library desugaring), so the build's buckets are missed and
         * the "prepare libraries" gate never flips after a successful build. Second element is null when there
         * are no module outputs (the common single-app case). Both readiness ([undexedForPreview]) and the
         * on-device render derive their universes here so the two can never drift.
         */
        fun previewScopeUniverses(
            externalJars: List<Path>, moduleJars: List<Path>, hashCacheDir: Path, minApi: Int, desugaredLibConfig: Path?,
        ): Pair<Universe, Universe?> {
            val ext = computeUniverse(externalJars, hashCacheDir, minApi, desugaredLibConfig)
            val sub = if (moduleJars.isEmpty()) null
            else computeUniverse(moduleJars + externalJars, hashCacheDir, minApi, desugaredLibConfig)
            return ext to sub
        }

        /**
         * The layout preview's dex-readiness gate: [undexedLibraries] over the build-faithful
         * [previewScopeUniverses], so an empty result means a build (or a prior preview) already dexed every
         * library and the real-view render can class-load with zero dexing. Replaces a single combined-universe
         * scan, which missed the build's scoped buckets below API 26.
         */
        fun undexedForPreview(
            externalJars: List<Path>, moduleJars: List<Path>, hashCacheDir: Path, minApi: Int,
            desugaredLibConfig: Path?, dexCacheRoot: Path,
        ): List<Path> = previewDexStatus(externalJars, moduleJars, hashCacheDir, minApi, desugaredLibConfig, dexCacheRoot).undexed

        /** [undexed] libraries the render would still have to D8-dex, plus [dexed] — how many DEXABLE libraries ARE
         *  already in the shared cache. The count lets the caller tell a truly COLD cache (dexed == 0 → prompt the
         *  one-time "prepare libraries") from a few STRAGGLERS (dexed > 0 → let the render dex the rest, which shows
         *  progress and seeds the cache, so the gate self-heals rather than looping when a prepare left a mismatch). */
        class PreviewDexStatus(val undexed: List<Path>, val dexed: Int)

        fun previewDexStatus(
            externalJars: List<Path>, moduleJars: List<Path>, hashCacheDir: Path, minApi: Int,
            desugaredLibConfig: Path?, dexCacheRoot: Path,
        ): PreviewDexStatus {
            val (ext, sub) = previewScopeUniverses(externalJars, moduleJars, hashCacheDir, minApi, desugaredLibConfig)
            val undexed = ArrayList<Path>()
            var dexed = 0
            fun scan(jars: List<Path>, u: Universe) {
                val tag = cacheTag(minApi, release = false, u.desugarDigest)
                val seen = HashSet<String>()
                for (jar in jars) {
                    val h = u.hashOf[jar] ?: continue
                    if (!seen.add(h)) continue                    // content-identical jars share one bucket
                    val bucket = dexCacheRoot.resolve(tag).resolve(h)
                    // A verified bucket was dexed, so it had classes: counts as dexed without opening the jar.
                    if (DexArchives.bucketVerified(bucket)) { dexed++; continue }
                    val cls = DexArchives.classNamesOf(jar)
                    if (cls.isEmpty()) continue                   // a resource-only jar dexes to nothing → neither
                    if (DexArchives.bucketComplete(bucket) { cls }) dexed++ else undexed.add(jar)
                }
            }
            scan(externalJars, ext)
            if (sub != null) scan(moduleJars, sub)
            return PreviewDexStatus(undexed, dexed)
        }

        /**
         * Content-hash each existing file in [files], cached by (path, size, mtime) in the `.hashcache` sidecar
         * under [hashCacheDir] so an unchanged file isn't re-read byte-for-byte. This is the SAME content hash
         * [computeUniverse] assigns a jar (both route through the internal `HashCache`), so a signature built from
         * these agrees with the dex cache's notion of "changed": a rebuilt-but-byte-identical jar (e.g. an
         * `R.jar`/module output the build regenerates with a fresh mtime) keeps its hash. Missing files are
         * omitted. NOT thread-safe (the sidecar is a plain file) — the caller serializes access.
         */
        fun contentHashes(files: List<Path>, hashCacheDir: Path): Map<Path, String> {
            val cache = DexArchives.HashCache(hashCacheDir)
            val out = LinkedHashMap<Path, String>()
            for (f in files) if (Files.exists(f)) out[f] = cache.hashOf(f)
            cache.flush()
            return out
        }
    }
}
