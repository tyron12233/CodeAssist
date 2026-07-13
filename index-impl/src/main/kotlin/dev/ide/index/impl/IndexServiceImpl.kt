package dev.ide.index.impl

import dev.ide.index.Hit
import dev.ide.index.Externalizer
import dev.ide.index.IndexExtension
import dev.ide.index.IndexId
import dev.ide.index.IndexInput
import dev.ide.index.IndexBuildStats
import dev.ide.index.IndexItem
import dev.ide.index.IndexItemState
import dev.ide.index.IndexOrigin
import dev.ide.index.IndexerStat
import dev.ide.index.IndexScope
import dev.ide.index.IndexService
import dev.ide.index.IndexStatus
import dev.ide.lang.dom.ParsedFile
import dev.ide.platform.ContentHash
import dev.ide.platform.Disposable
import dev.ide.platform.log.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.URI
import java.nio.file.FileSystem
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.LongAdder
import java.util.stream.Collectors
import java.util.zip.ZipFile
import java.nio.file.Paths
import kotlin.io.path.readText

/**
 * The [IndexService]: per index, a **static side** (SDK + libraries) of immutable on-disk [Segment]s — one
 * per artifact, keyed by content hash, built once and reused across launches — and an in-memory
 * **source side** ([IndexData], rebuilt incrementally on edit). Queries merge both.
 *
 * The static side is the low-RAM win: a library/SDK index is never resident; its [Segment] is read from
 * disk on demand through a bounded [BlockCache] (default [DEFAULT_BLOCK_CACHE_BYTES]), so total resident
 * memory is the cache cap + each segment's tiny sparse term index — flat regardless of how large the
 * indexes grow. The source side stays in RAM because it is project-sized and changes every keystroke.
 *
 * Heavy building is blocking work driven from a background coroutine; it yields so the caller can cancel.
 */
class IndexServiceImpl(
    extensions: List<IndexExtension<*, *>>,
    private val cacheRoot: Path,
    private val parse: (Path, String) -> ParsedFile? = { _, _ -> null },
    blockCacheBytes: Long = DEFAULT_BLOCK_CACHE_BYTES,
    blockSize: Int = 4096,
    /** PER-PROJECT dir for the persisted source-side partitions + per-file fingerprints (unlike [cacheRoot],
     *  which is the SHARED library/SDK segment store). When set, a re-open reparses ONLY the source/resource
     *  files whose content changed since the last session; null disables cross-launch persistence (the source
     *  diff still avoids reparsing unchanged files within a single session). */
    private val sourceCacheRoot: Path? = null,
    /**
     * READ-ONLY mode, for a second process sharing [cacheRoot] with the IDE (the Kotlin Analysis API
     * daemon): [ensureUpToDate] opens whatever content-addressed segments already exist but NEVER builds a
     * missing one (queries simply lack that artifact's entries until the owning process builds it and a
     * later [ensureUpToDate] re-opens), and the mutating entry points ([invalidate], [reindexSource]) are
     * rejected. The IDE process is the store's sole writer; a reader can't duplicate its build work, and
     * the safety of concurrent same-segment builds never has to be relied on.
     */
    private val readOnly: Boolean = false,
) : IndexService, Closeable {

    private class State(val ext: IndexExtension<*, *>) {
        /** Static (SDK + library) partitions, read from disk on demand. CopyOnWrite ⇒ lock-free query reads. */
        val segments = CopyOnWriteArrayList<Segment>()

        /** Content hashes whose segment is already open, so a repeated build doesn't open it twice. Concurrent
         *  because artifacts are indexed in parallel (each adds its OWN distinct key, so no key races). */
        val openHashes: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

        /** Project source: in-memory + incremental, the SINGLE store (no separate per-file value map). It is
         *  file-partitioned internally by the interned integer file id (see [FileIdTable]), so an edit updates
         *  exactly one file's entries ([IndexData.setFile]) and a path is held once (in the table). */
        val source = IndexData(ext.matching)
    }

    private val states: Map<IndexId, State> = extensions.associate { it.id to State(it) }
    private val blockCache = BlockCache(blockCacheBytes, blockSize)

    /** True on memory-constrained hosts (the caller passes the smaller [CONSTRAINED_BLOCK_CACHE_BYTES] cap on
     *  device). Drives memory-vs-speed trade-offs during the build (e.g. serial artifact indexing).
     **/
    private val constrained = blockCacheBytes <= CONSTRAINED_BLOCK_CACHE_BYTES
    private val segIds = AtomicInteger(0)

    /** Measurement channel for [ensureUpToDate]: a one-line build summary at INFO plus per-artifact lines
     *  for the costly (built, or slow-to-open) ones, so a device run reveals whether the shared on-disk
     *  cache is reused (cheap) or rebuilt (the large-project cost), and where the build-time heap peaks.
     *  Reaches logcat ([dev.ide.platform.log.ConsoleLogSink]) and the in-app Logs viewer (the ring buffer). */
    private val perfLog = Log.logger("index.perf")

    /** A source/resource file's (size + mtime) fingerprint — the change signal the source diff keys on to
     *  decide what to reparse since the previous pass (incl. across launches, when [sourceCacheRoot] is set). */
    private class SourceSig(val size: Long, val mtime: Long) {
        fun matches(o: SourceSig) = size == o.size && mtime == o.mtime
    }

    /**
     * Interns source/resource file paths to compact, per-project integer ids — the IntelliJ `fileId` approach.
     * The in-memory file partitions ([IndexData]) and the on-disk entry files reference an id (one int)
     * instead of repeating the full absolute path in every extension's partition; the path string is held
     * exactly ONCE here, alongside its [SourceSig]. Freed ids (deleted files) are recycled so the id space
     * stays dense across delete/add churn. This is the master file list the diff walks.
     */
    private class FileIdTable {
        private val pathToId = HashMap<String, Int>()
        private val idToPath = HashMap<Int, String>()
        private val sigById = HashMap<Int, SourceSig>()

        // Freed ids (deleted files), recycled before bumping [nextId] so the id space stays dense. Persisted
        // alongside [nextId] so id assignment is DETERMINISTIC across launches — an id, once given to a file,
        // is never silently reassigned to a different file: it is retired on delete and only handed out again
        // from this list. That durability is what lets values (e.g. SymbolValue) store a file id instead of a
        // path and still resolve correctly after a restart.
        private val freeIds = ArrayDeque<Int>()
        private var nextId = 0

        val ids: Set<Int> get() = idToPath.keys
        fun isEmpty() = idToPath.isEmpty()
        fun pathOf(id: Int): String? = idToPath[id]
        fun sigOf(id: Int): SourceSig? = sigById[id]
        fun setSig(id: Int, sig: SourceSig) {
            sigById[id] = sig
        }

        /** The id for [path], allocating (reusing a freed id when possible) if unseen. Leaves the fingerprint
         *  untouched — callers set it via [setSig] once they have actually (re)indexed the file. */
        fun idFor(path: String): Int = pathToId.getOrPut(path) {
            val id = freeIds.removeLastOrNull() ?: nextId++
            idToPath[id] = path
            id
        }

        fun removeId(id: Int) {
            val path = idToPath.remove(id) ?: return
            pathToId.remove(path); sigById.remove(id); freeIds.addLast(id)
        }

        /** Forget every fingerprint but KEEP the path↔id mapping (and [nextId]/[freeIds]): the next diff then
         *  re-indexes every file with its EXISTING id. Used when the entry partitions can't be loaded but the
         *  id table can — so ids stay stable through a partition rebuild. */
        fun dropFingerprintsKeepIds() {
            sigById.clear()
        }

        fun clear() {
            pathToId.clear(); idToPath.clear(); sigById.clear(); freeIds.clear(); nextId = 0
        }

        /** Serialize the whole table (assignment counter + free list + every live row). */
        fun writeTo(out: DataOutputStream) {
            out.writeInt(nextId)
            out.writeInt(freeIds.size); for (f in freeIds) out.writeInt(f)
            out.writeInt(idToPath.size)
            for ((id, path) in idToPath) {
                val s = sigById[id] ?: SourceSig(0L, 0L)
                out.writeInt(id); out.writeUTF(path); out.writeLong(s.size); out.writeLong(s.mtime)
            }
        }

        /** Replace this table's contents with a persisted snapshot (ids preserved verbatim). */
        fun readFrom(din: DataInputStream) {
            clear()
            nextId = din.readInt()
            repeat(din.readInt()) { freeIds.addLast(din.readInt()) }
            repeat(din.readInt()) {
                val id = din.readInt();
                val path = din.readUTF();
                val sig = SourceSig(din.readLong(), din.readLong())
                pathToId[path] = id; idToPath[id] = path; sigById[id] = sig
            }
        }
    }

    private val fileIds = FileIdTable()

    /** Load the persisted source cache lazily, exactly once per process (the first [indexSource] pass). */
    private var sourceCacheLoaded = false

    @Volatile
    override var status: IndexStatus = IndexStatus()
        private set
    private val listeners = CopyOnWriteArrayList<(IndexStatus) -> Unit>()

    override fun observeStatus(listener: (IndexStatus) -> Unit): Disposable {
        listeners.add(listener)
        listener(status)
        return Disposable { listeners.remove(listener) }
    }

    private fun setStatus(s: IndexStatus) {
        status = s
        listeners.forEach { runCatching { it(s) } }
    }

    // ---- queries ----

    @Suppress("UNCHECKED_CAST")
    override fun <V : Any> exact(id: IndexId, key: String): Sequence<V> {
        val st = states[id] ?: return emptySequence()
        val out = ArrayList<Any>()
        st.segments.forEach { it.exact(key, out) }
        out.addAll(st.source.exact(key))
        return out.asSequence().map { it as V }
    }

    override fun <V : Any> prefix(id: IndexId, prefix: String, limit: Int): Sequence<Hit<V>> =
        query(id, prefix, fuzzy = false, limit)

    override fun <V : Any> fuzzy(id: IndexId, pattern: String, limit: Int): Sequence<Hit<V>> =
        query(id, pattern, fuzzy = true, limit)

    @Suppress("UNCHECKED_CAST")
    private fun <V : Any> query(
        id: IndexId, q: String, fuzzy: Boolean, limit: Int
    ): Sequence<Hit<V>> {
        val st = states[id] ?: return emptySequence()
        if (q.isEmpty()) {
            return emptySequence()
        }
        val cap = (limit * 8).coerceAtLeast(64)
        val out = ArrayList<Hit<Any>>()
        // Each source gets its own `cap` budget (the union is re-ranked below) so a large segment can't
        // starve the others by filling the shared buffer first.
        for (seg in st.segments) {
            val tmp = ArrayList<Hit<Any>>()
            if (fuzzy) seg.fuzzy(q, tmp, cap) else seg.prefix(q, tmp, cap)
            out.addAll(tmp)
        }
        val src = ArrayList<Hit<Any>>()
        if (fuzzy) st.source.fuzzy(q, src, cap) else st.source.prefix(q, src, cap)
        out.addAll(src)
        @Suppress("UNCHECKED_CAST") return rankMerged(out, limit).asSequence()
            .map { Hit(it.key, it.value as V, it.score) }
    }

    /** Resolve a source value's interned [IndexInput.fileId] back to its path (go-to-symbol navigation). */
    override fun filePath(id: Int): String? = fileIds.pathOf(id)

    /** Test instrumentation: total (term, value) entries appended to the source side across all extensions.
     *  A per-file reindex bumps this by only the edited file's entry count, so a test can prove the in-memory
     *  update is O(edited file), not O(whole project). */
    internal fun debugSourceAddOps(): Long = states.values.sumOf { it.source.addOps }

    // ---- build ----

    override suspend fun ensureUpToDate(scope: IndexScope) {
        setStatus(
            IndexStatus(
                building = true, message = "Indexing…", fraction = -1.0, phase = "Starting…"
            )
        )
        try {
            // Soft reset: drop the in-memory open segments (but KEEP the on-disk `.seg` files) so this call
            // re-syncs to the current scope — segments for artifacts no longer on the classpath fall out, and
            // unchanged artifacts are RE-OPENED from disk (cheap) rather than rebuilt. This is what makes a
            // model-change re-index cheap: a 40k-class android.jar is segmented once and reopened thereafter,
            // not re-scanned every time. invalidate() still forces a from-scratch rebuild by deleting THIS
            // service's segments (it leaves other projects' segments in the shared store untouched).
            closeSegments()
            val artifacts = buildList {
                scope.jdkHome?.let { add(Artifact.Jrt(it)) }
                scope.libraryJars.forEach { add(Artifact.Jar(it, scope.stableJarIds[it])) }
                scope.sourceArchives.forEach { if (Files.exists(it)) add(Artifact.SourceArchive(it)) }
                // Immutable dependency/AAR res dirs index to content-addressed disk segments (parsed once,
                // reopened thereafter), not into the resident source side.
                scope.libraryResourceRoots.forEach {
                    if (Files.isDirectory(it)) add(
                        Artifact.ResourceDir(
                            it
                        )
                    )
                }
            }
            val total = artifacts.size + 1
            // Index artifacts in parallel. Each artifact is independent — it scans its own jar and writes its
            // own per-(ext,hash) segment file; the only shared state is each State's `segments` (CopyOnWrite)
            // and `openHashes` (concurrent set, distinct key per artifact). Sequential, this was the dominant
            // first-load cost gating the first completion/diagnostics on a `.kt` file.
            //
            // TWO different limits, because the two paths cost differently:
            //  - REOPEN (cache hit): just opens each extension's prebuilt `.seg` (a FileChannel + footer read),
            //    holds no accumulator, is pure I/O. A warm re-open is ~all cache hits — 156 artifacts × ~10
            //    extension segments = ~1.5k tiny file opens — so it is bounded by I/O parallelism, not memory.
            //  - BUILD (cache miss): streams a whole artifact's entries through per-(ext) SegmentWriters, so N
            //    concurrent builds keep N big-jar accumulators live; on a constrained (tight-heap) host that
            //    peak is what must be bounded.
            // So run the artifact coroutines at a HIGH I/O width but gate the build section with a small
            // semaphore. On a warm cache nothing takes a permit and all opens run wide (the ~10s reopen the
            // old flat concurrency=2 caused drops ~I/O-parallelism-fold); a cold build stays memory-bounded.
            val cores = Runtime.getRuntime().availableProcessors()
            val ioConcurrency = minOf(8, maxOf(4, cores))
            val buildPermits = if (constrained) 2 else minOf(4, maxOf(1, cores - 1))
            val buildGate = kotlinx.coroutines.sync.Semaphore(buildPermits)
            val done = AtomicInteger(0)
            // Artifacts dropped by the per-artifact catch below (an unreadable/corrupt jar, or a failed segment
            // write). A skipped artifact leaves its classes out of the index, so the index is NOT a complete
            // superset of the classpath and ready must be withheld (see the ready gate at the end of this build).
            val skipped = AtomicInteger(0)
            // Per-artifact worklist for the index-status dialog (PENDING → ACTIVE → DONE). Mutated from the
            // parallel builders under [worklistLock]; each status emit publishes an immutable snapshot.
            val worklist =
                artifacts.mapTo(ArrayList()) { IndexItem(it.label, IndexItemState.PENDING) }
            val worklistLock = Any()
            // Per-indexer time/entry accumulator for this build. Snapshotted into every status emit so the
            // detail view watches each index's cost grow live (and keeps the final breakdown when idle).
            val timer = BuildTimer()
            fun emitArtifacts(message: String) {
                val items = synchronized(worklistLock) { worklist.toList() }
                val d = done.get()
                setStatus(
                    IndexStatus(
                        true,
                        message,
                        d.toDouble() / total,
                        phase = "Libraries & SDK",
                        items = items,
                        processed = d,
                        total = artifacts.size,
                        breakdown = timer.snapshot(),
                    )
                )
            }
            emitArtifacts("Indexing…")
            // Measurement (index.perf): collect each artifact's hit/build outcome + timing, and track the
            // build-time heap peak (sampled as each artifact finishes), so a device run on a large project
            // shows whether the shared cache is reused or rebuilt and where memory peaks. See the summary
            // emitted after the source phase below.
            val buildStart = System.nanoTime()
            val probes = ConcurrentLinkedQueue<ArtifactProbe>()
            val peakHeap = AtomicLong(usedHeapBytes())
            fun samplePeak() {
                val u = usedHeapBytes(); peakHeap.updateAndGet { maxOf(it, u) }
            }
            val buildDispatcher = Dispatchers.IO.limitedParallelism(ioConcurrency)
            kotlinx.coroutines.coroutineScope {
                for ((i, art) in artifacts.withIndex()) {
                    launch(buildDispatcher) {
                        synchronized(worklistLock) {
                            worklist[i] = worklist[i].copy(state = IndexItemState.ACTIVE)
                        }
                        emitArtifacts("Indexing ${art.label}")
                        try {
                            val probe = indexArtifact(art, buildGate, timer)
                            probes.add(probe)
                            samplePeak()
                            // Surface the costly artifacts (built, or a slow open) at INFO; quiet otherwise, so a
                            // warm reopen (all cache hits) logs only the one summary line.
                            when {
                                !probe.isHit && probe.ms >= 50 -> perfLog.info("built ${probe.label}: exts=${probe.built} entries=${probe.entries} ${probe.ms}ms")

                                !probe.isHit -> perfLog.debug("built ${probe.label}: exts=${probe.built} entries=${probe.entries} ${probe.ms}ms")

                                probe.ms >= 50 -> perfLog.info("reused ${probe.label} (slow open ${probe.ms}ms)")
                            }
                        } catch (c: kotlinx.coroutines.CancellationException) {
                            throw c
                        } catch (t: Throwable) {
                            // One unreadable artifact must NOT cancel the whole index build (a coroutineScope
                            // child throwing cancels its siblings). The known case: ART's ZipFile throws
                            // `ZipException: No entries` opening a zero-entry jar (a resource-only AAR's empty
                            // classes.jar) — on desktop the same jar opens fine, so this aborted only on device.
                            skipped.incrementAndGet()
                            dev.ide.platform.log.Log.logger("index")
                                .warn("indexing ${art.label} failed, skipped: ${t.javaClass.simpleName}: ${t.message}")
                        }
                        synchronized(worklistLock) {
                            worklist[i] = worklist[i].copy(state = IndexItemState.DONE)
                        }
                        done.incrementAndGet()
                        emitArtifacts("Indexing ${art.label}")
                    }
                }
            }
            val libMs = elapsedMs(buildStart)
            setStatus(
                IndexStatus(
                    true,
                    "Indexing project source",
                    artifacts.size.toDouble() / total,
                    phase = "Project source"
                )
            )
            val srcProbe = indexSource(
                scope.sourceRoots, scope.resourceRoots, timer = timer
            ) { current, processed, totalFiles ->
                setStatus(
                    IndexStatus(
                        true,
                        "Indexing project source",
                        artifacts.size.toDouble() / total,
                        phase = "Project source",
                        items = listOf(IndexItem(current, IndexItemState.ACTIVE)),
                        processed = processed,
                        total = totalFiles,
                        breakdown = timer.snapshot(),
                    )
                )
            }
            samplePeak()
            // One-line measurement summary: artifact reuse vs rebuild (the large-project cost signal), source
            // diff, phase timings, build parallelism, and the build-time heap peak.
            val built = probes.count { !it.isHit }
            val reused = probes.count { it.isHit }
            val mb = 1024L * 1024L
            perfLog.info(
                "build done: ${artifacts.size} artifacts ($built built, $reused reused), " + "${probes.sumOf { it.entries }} entries; source ${srcProbe.walked} files " + "(${srcProbe.parsed} parsed, ${srcProbe.removed} removed); lib ${libMs}ms, src ${srcProbe.ms}ms, " + "total ${
                    elapsedMs(
                        buildStart
                    )
                }ms; io=$ioConcurrency build=$buildPermits; " + "heap peak ${peakHeap.get() / mb}MB / max ${
                    Runtime.getRuntime().maxMemory() / mb
                }MB" + if (skipped.get() > 0) "; skipped=${skipped.get()}" else ""
            )
            // ready=true tells the JDT name environment it may trust the index as a complete superset of the
            // classpath (authoritative negatives, no probing — see JdtEnvironmentCache.libraryBytes). Only
            // claim that when EVERY artifact indexed: a skipped jar's classes are absent, so resolving them
            // would be a false "not found". A partial index stays usable (segments are open and queried), the
            // name environment just keeps probing instead of trusting misses. A read-only service's missing
            // (not-yet-built-by-the-owner) segments withhold ready the same way.
            val missing = probes.sumOf { it.missing }
            val complete = skipped.get() == 0 && missing == 0
            val msg = when {
                complete -> "Indexed"
                missing > 0 -> "Indexed (partial: $missing segment(s) not built yet)"
                else -> "Indexed (partial: ${skipped.get()} artifact(s) skipped)"
            }
            // Terminal status carries the last build's per-indexer breakdown + aggregate stats. It stays the
            // current status (the flow's latest value) until the next build, so the detail view + the analytics
            // watcher can read "what took the time" after the fact, not just live.
            setStatus(
                IndexStatus(
                    false, msg, 1.0, ready = complete,
                    breakdown = timer.snapshot(),
                    stats = IndexBuildStats(
                        libMs = libMs,
                        sourceMs = srcProbe.ms,
                        artifacts = artifacts.size,
                        artifactsBuilt = built,
                        artifactsReused = reused,
                        sourceFiles = srcProbe.walked,
                        sourceParsed = srcProbe.parsed,
                    ),
                )
            )
        } catch (t: Throwable) {
            setStatus(IndexStatus(false, "Indexing failed: ${t.message}", 1.0))
            throw t
        }
    }

    override suspend fun invalidate() {
        check(!readOnly) { "read-only index: invalidate() belongs to the owning (IDE) process" }
        // [cacheRoot] holds the STATIC (SDK + library) segments and may be SHARED across projects. Each
        // artifact is content-addressed to one `.seg`, so two projects depending on the same jar reuse it.
        // So invalidate must NOT blind-wipe [cacheRoot] (that would delete other projects' segments): it drops
        // this service's in-memory state and deletes only the segment files THIS service has open (its working
        // set), so a "Re-index" forces those artifacts to rebuild without disturbing other projects. Reclaiming
        // orphaned segments (a jar changed → a stale `.seg`) is a shared-store GC concern, intentionally not
        // done here. The in-memory source side is always per-project. Capture paths before closeSegments()
        // clears openHashes; close channels first so the files aren't held open when we delete them.
        val openFiles = states.values.flatMap { st ->
            st.openHashes.map { key ->
                segmentFileForKey(
                    st.ext, key
                )
            }
        }
        closeSegments()
        states.values.forEach { it.source.clear() }
        fileIds.clear()
        blockCache.clear()
        runCatching { openFiles.forEach { Files.deleteIfExists(it); pruneEmptyParents(it) } }
        // The persisted source cache is per-project (not shared), so a Re-index wipes it wholesale: the next
        // [ensureUpToDate] then re-parses every source file from scratch and re-persists.
        sourceCacheRoot?.let { root ->
            runCatching {
                if (Files.exists(root)) Files.walk(root).use { s ->
                    s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
                }
            }
        }
        setStatus(IndexStatus(building = false, message = "Index cleared", fraction = -1.0))
    }

    /** Close every open segment (releases file channels + their cached blocks). */
    private fun closeSegments() {
        states.values.forEach { st ->
            st.segments.forEach { runCatching { it.close() } }
            st.segments.clear()
            st.openHashes.clear()
        }
    }

    override fun close() {
        closeSegments()
        blockCache.clear()
    }

    /** One artifact's contribution to a build, for the [perfLog] measurement. [built] is 0 when every
     *  extension's segment was reused from the shared on-disk cache (a pure cache hit). [missing] counts
     *  the extensions whose segment did not exist and was NOT built (read-only mode); a non-zero missing
     *  count withholds `ready`, exactly like a skipped artifact. */
    private class ArtifactProbe(
        val label: String, val reused: Int, val built: Int, val entries: Int, val ms: Long,
        val missing: Int = 0,
    ) {
        val isHit: Boolean get() = built == 0
    }

    /** The source phase's contribution: files [walked], of which [parsed] (new/changed) and [removed] (gone). */
    private class SourceProbe(val walked: Int, val parsed: Int, val removed: Int, val ms: Long)

    /** Accumulates per-indexer ([IndexExtension]) time + entry counts across ONE [ensureUpToDate] build, so the
     *  status can answer "which index is taking the time". Thread-safe: the artifact phase runs [indexArtifact]
     *  on several coroutines at once, all recording into the same extension buckets ([LongAdder] eats the
     *  contention). A `null` timer (the incremental [reindexSource] path) records nothing. */
    private class BuildTimer {
        private val nanos = ConcurrentHashMap<IndexId, LongAdder>()
        private val entries = ConcurrentHashMap<IndexId, LongAdder>()

        fun record(id: IndexId, elapsedNanos: Long, produced: Int) {
            nanos.computeIfAbsent(id) { LongAdder() }.add(elapsedNanos)
            if (produced > 0) entries.computeIfAbsent(id) { LongAdder() }.add(produced.toLong())
        }

        /** Immutable snapshot, slowest indexer first. Cheap (~one entry per registered extension). */
        fun snapshot(): List<IndexerStat> =
            nanos.entries
                .map { (id, adder) -> IndexerStat(id.value, adder.sum() / 1_000_000L, entries[id]?.sum() ?: 0L) }
                .sortedByDescending { it.indexMs }
    }

    private fun elapsedMs(startNanos: Long): Long = (System.nanoTime() - startNanos) / 1_000_000L
    private fun usedHeapBytes(): Long {
        val rt = Runtime.getRuntime(); return rt.totalMemory() - rt.freeMemory()
    }

    private suspend fun indexArtifact(art: Artifact, buildGate: kotlinx.coroutines.sync.Semaphore, timer: BuildTimer? = null): ArtifactProbe {
        val t0 = System.nanoTime()
        val hash = art.contentHash()
        val key = sanitize(hash.value)

        // Already built on a prior run? Just open the segment — no re-indexing, nothing loaded into RAM. This
        // is the warm-cache path; it holds no accumulator, so it runs ungated at the full I/O concurrency.
        var reused = 0
        for (st in states.values) {
            if (key in st.openHashes) continue
            val f = segmentFile(st.ext, hash)
            if (Files.exists(f)) {
                runCatching {
                    Segment.open(
                        f,
                        st.ext,
                        blockCache,
                        segIds.getAndIncrement()
                    )
                }.onSuccess { st.segments.add(it); st.openHashes.add(key); reused++ }
            }
        }
        val needBuild = states.values.filter { key !in it.openHashes }
        if (needBuild.isEmpty()) {
            return ArtifactProbe(art.label, reused, 0, 0, elapsedMs(t0))
        }
        if (readOnly) {
            // A reader never builds: the artifact's entries are absent until the owning (IDE) process
            // writes the segment; the next ensureUpToDate re-checks the store and opens it then.
            return ArtifactProbe(art.label, reused, 0, 0, elapsedMs(t0), missing = needBuild.size)
        }

        // Cache MISS → build. This holds a whole artifact's SegmentWriters (accumulators) live, so gate it:
        // only [buildPermits] artifacts build concurrently, bounding the heap peak on a tight-heap host. The
        // reopen path above never reaches here, so a warm cache never takes a permit.
        return buildGate.withPermit { buildArtifact(art, hash, key, needBuild, reused, timer, t0) }
    }

    /** Build (index) an artifact's missing per-extension segments. Called only under [indexArtifact]'s build
     *  gate (a cache miss), so the concurrent count of these live accumulators stays bounded. */
    private suspend fun buildArtifact(
        art: Artifact, hash: ContentHash, key: String, needBuild: List<State>, reused: Int,
        timer: BuildTimer?, t0: Long,
    ): ArtifactProbe {
        // Stream each extension's entries straight into a per-(ext) [SegmentWriter] rather than buffering the
        // whole artifact's entries in an ArrayList. A large artifact (android.jar holds ~95k entries × every
        // extension) used to keep all of that resident until the segment was written, driving the build-time
        // heap peak and forcing serial indexing on device; the writer spills to disk past a bounded batch.
        val writers = needBuild.associateWith { SegmentWriter(segmentFile(it.ext, hash), it.ext) }
        try {
            val (inputs, closeable) = art.open()
            try {
                for (input in inputs) {
                    for (st in needBuild) {
                        @Suppress("UNCHECKED_CAST") val e = st.ext as IndexExtension<Any, Any>
                        if (!e.inputFilter.accepts(input)) continue
                        val extStart = System.nanoTime()
                        var produced = 0
                        runCatching {
                            for ((k, vs) in e.index(input)) {
                                val term = e.keyDescriptor.asTerm(k)
                                for (v in vs) {
                                    writers.getValue(st).add(term, v, input.origin); produced++
                                }
                            }
                        }
                        timer?.record(st.ext.id, System.nanoTime() - extStart, produced)
                    }
                    yield()
                }
            } finally {
                runCatching { closeable.close() }
            }
            var entries = 0
            for (st in needBuild) {
                val w = writers.getValue(st)
                entries += w.count
                // No runCatching here: a failed segment write/open leaves this artifact's classes out of the
                // index. That must propagate to the per-artifact handler in [ensureUpToDate] (which counts it as
                // a skip, so the index is marked incomplete and ready is withheld), not be swallowed into a
                // falsely "ready" index that the name environment would then trust for authoritative negatives.
                w.finish()
                val seg = Segment.open(segmentFile(st.ext, hash), st.ext, blockCache, segIds.getAndIncrement())
                st.segments.add(seg)
                st.openHashes.add(key)
            }
            return ArtifactProbe(art.label, reused, needBuild.size, entries, elapsedMs(t0))
        } finally {
            // Releases any spill temp files: finish() cleans its own, this covers a cancel/throw before finish.
            writers.values.forEach { runCatching { it.close() } }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun indexSource(
        roots: List<Path>,
        resourceRoots: List<Path> = emptyList(),
        /** Per-indexer accumulator for this build (null on the incremental save path). */
        timer: BuildTimer? = null,
        /** Reports the (relative) file currently indexed plus a running count, for the index-status dialog. */
        progress: (current: String, processed: Int, total: Int) -> Unit = { _, _, _ -> },
    ): SourceProbe {
        val t0 = System.nanoTime()
        // First pass this session: pull the persisted per-file partitions + fingerprints off disk, so a re-open
        // re-parses ONLY the files whose content changed since the previous launch instead of every source file.
        if (!sourceCacheLoaded) {
            loadSourceCache(); sourceCacheLoaded = true
        }

        // Walk the current source/resource tree, interning each file to its id and capturing its (size, mtime)
        // fingerprint. Source roots: Java/Kotlin. Resource roots: Android res XML. Inputs are disjoint — each
        // extension's inputFilter selects its own, so the Java indexes ignore .xml and the resource index .java.
        val groups =
            roots.map { it to setOf(".java", ".kt") } + resourceRoots.map { it to setOf(".xml") }
        val currentIds = HashSet<Int>()
        val dirty = ArrayList<Dirty>() // only files that are new or whose fingerprint changed
        for ((root, exts) in groups) {
            if (!Files.isDirectory(root)) continue
            Files.walk(root).use { s ->
                s.filter { f -> Files.isRegularFile(f) && exts.any { f.toString().endsWith(it) } }
                    .forEach { f ->
                        val sig = runCatching {
                            SourceSig(
                                Files.size(f), Files.getLastModifiedTime(f).toMillis()
                            )
                        }.getOrDefault(SourceSig(0L, 0L))
                        val id = fileIds.idFor(f.toString())
                        currentIds.add(id)
                        val prev = fileIds.sigOf(id)
                        if (prev == null || !prev.matches(sig)) dirty.add(Dirty(f, root, id, sig))
                    }
            }
        }

        // Drop files that no longer exist under any root (deleted/moved) from the source index + the id table.
        val gone = fileIds.ids - currentIds
        for (id in gone) {
            states.values.forEach { it.source.removeFile(id) }; fileIds.removeId(id)
        }

        // Only the dirty files need a read + parse + index. Everything else is already in [IndexData] (loaded
        // from disk in [loadSourceCache] or indexed on a prior pass), updated incrementally per file below.
        val total = dirty.size
        var processed = 0
        for (d in dirty) {
            runCatching { d.file.readText() }.getOrNull()
                ?.let { indexSourceFile(d.file, it, d.root, d.id, timer) }
            fileIds.setSig(d.id, d.sig)
            processed++
            // The source phase can be thousands of small files, so throttle status churn: report every 16th
            // file (and the last) rather than every one.
            if (processed % 16 == 0 || processed == total) {
                val rel = runCatching { d.root.relativize(d.file).toString() }.getOrNull()
                    ?: d.file.fileName?.toString() ?: ""
                progress(rel, processed, total)
            }
            yield()
        }

        // No full rebuild: [indexSourceFile] already applied each dirty file to [IndexData] incrementally, and
        // unchanged files were never removed. The in-memory index is up to date here.

        // Persist only when something actually changed, so a truly unchanged restart writes nothing.
        if (dirty.isNotEmpty() || gone.isNotEmpty()) saveSourceCache()
        return SourceProbe(
            walked = currentIds.size, parsed = dirty.size, removed = gone.size, ms = elapsedMs(t0)
        )
    }

    /** A source file that needs (re)indexing this pass: its path, the root it was found under, its interned
     *  id, and the fresh fingerprint to record once it is indexed. */
    private class Dirty(val file: Path, val root: Path, val id: Int, val sig: SourceSig)

    @Suppress("UNCHECKED_CAST")
    private fun indexSourceFile(file: Path, text: String, root: Path?, id: Int, timer: BuildTimer? = null) {
        val input = SourceInput(file, root, text, parse, id)
        for (st in states.values) {
            val e = st.ext as IndexExtension<Any, Any>
            if (!e.inputFilter.accepts(input)) {
                st.source.removeFile(id); continue
            }
            val entries = ArrayList<IndexEntry>()
            val extStart = System.nanoTime()
            runCatching {
                for ((k, vs) in e.index(input)) {
                    val term = e.keyDescriptor.asTerm(k)
                    for (v in vs) entries.add(IndexEntry(term, v, IndexOrigin.SOURCE))
                }
            }
            timer?.record(st.ext.id, System.nanoTime() - extStart, entries.size)
            st.source.setFile(id, entries)
        }
    }

    override suspend fun reindexSource(path: Path, text: String) {
        check(!readOnly) { "read-only index: reindexSource() belongs to the owning (IDE) process" }
        val id = fileIds.idFor(path.toString())
        indexSourceFile(path, text, null, id)
        // Keep this file's fingerprint current so a later [ensureUpToDate] in the same session won't needlessly
        // re-parse it. This is the SAVE path (the host re-indexes from the just-written file), so the on-disk
        // (size, mtime) matches the indexed [text]; the persisted cache is refreshed by the next ensureUpToDate.
        runCatching {
            fileIds.setSig(
                id, SourceSig(Files.size(path), Files.getLastModifiedTime(path).toMillis())
            )
        }
        // [indexSourceFile] already replaced just this file's entries in each [IndexData] (O(this file), not a
        // whole-project rebuild) — nothing else to do.
    }

    // ---- source cache (per-project, persisted across launches) ----

    /** The file-id TABLE: the path string ↔ id mapping + per-id fingerprint, written ONCE (paths are not
     *  repeated in the per-extension partitions, which reference the int id instead). */
    private fun sourceIdTableFile(): Path? = sourceCacheRoot?.resolve("files.bin")
    private fun sourceEntriesFile(ext: IndexExtension<*, *>): Path? =
        sourceCacheRoot?.resolve(ext.id.value)?.resolve("v${ext.version}")?.resolve("entries.bin")

    /**
     * Load the persisted id table (the durable VFS: paths + fingerprints + the id-assignment state) and the
     * per-extension entry partitions into memory. The table is the AUTHORITY: it carries the stable file ids
     * that values (e.g. SymbolValue) reference, so it is loaded independently of the partitions.
     *  - Table file absent → cold start (empty table).
     *  - Table file corrupt → ids can't be trusted, so reset everything (a full rebuild reassigns ids).
     *  - Table OK but a partition is missing/corrupt/version-bumped → KEEP the table (ids stay stable) and
     *    drop only the fingerprints, so this pass re-indexes every file with its EXISTING id.
     */
    @Suppress("UNCHECKED_CAST")
    private fun loadSourceCache() {
        val table = sourceIdTableFile() ?: return
        if (!Files.exists(table)) return // cold start: no cache yet

        val tableOk = runCatching {
            DataInputStream(BufferedInputStream(Files.newInputStream(table))).use { din ->
                if (din.readInt() != SOURCE_CACHE_MAGIC) throw IOException("bad source-cache magic")
                fileIds.readFrom(din)
            }
        }.isSuccess
        if (!tableOk) {
            fileIds.clear(); states.values.forEach { it.source.clear() }
            dev.ide.platform.log.Log.logger("index")
                .warn("source index id table unreadable, rebuilding from scratch")
            return
        }

        // Table is trustworthy; now load each extension's partitions (referencing the table's ids).
        val loaded = HashMap<IndexId, LinkedHashMap<Int, List<IndexEntry>>>()
        val partitionsOk = runCatching {
            for (st in states.values) {
                val f = sourceEntriesFile(st.ext) ?: throw IOException("no source cache root")
                val ser = st.ext.valueExternalizer as Externalizer<Any>
                val map = LinkedHashMap<Int, List<IndexEntry>>()
                DataInputStream(BufferedInputStream(Files.newInputStream(f))).use { din ->
                    if (din.readInt() != SOURCE_CACHE_MAGIC) throw IOException("bad source-cache magic")
                    repeat(din.readInt()) { // file count
                        val id = din.readInt()
                        val count = din.readInt()
                        val entries = ArrayList<IndexEntry>(count)
                        repeat(count) {
                            val term = din.readUTF()
                            val value = ser.read(din)
                            entries.add(IndexEntry(term, value, IndexOrigin.SOURCE))
                        }
                        map[id] = entries
                    }
                }
                loaded[st.ext.id] = map
            }
        }.isSuccess

        if (partitionsOk) {
            states.values.forEach { st ->
                st.source.clear()
                loaded[st.ext.id]?.forEach { (id, entries) -> st.source.setFile(id, entries) }
            }
        } else {
            // Keep the id table (ids stable) but force a full re-index that reuses those ids.
            states.values.forEach { it.source.clear() }
            fileIds.dropFingerprintsKeepIds()
            dev.ide.platform.log.Log.logger("index")
                .warn("source index partitions unreadable, rebuilding entries (ids preserved)")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun saveSourceCache() {
        val table = sourceIdTableFile() ?: return
        runCatching {
            writeAtomically(table) { out -> out.writeInt(SOURCE_CACHE_MAGIC); fileIds.writeTo(out) }
            for (st in states.values) {
                val f = sourceEntriesFile(st.ext) ?: continue
                val ser = st.ext.valueExternalizer as Externalizer<Any>
                val files = st.source.fileIds()
                writeAtomically(f) { out ->
                    out.writeInt(SOURCE_CACHE_MAGIC)
                    out.writeInt(files.size)
                    for (id in files) {
                        val entries = st.source.entriesOf(id)
                        out.writeInt(id)
                        out.writeInt(entries.size)
                        for (e in entries) {
                            out.writeUTF(e.term); ser.write(out, e.value)
                        }
                    }
                }
            }
        }.onFailure {
            dev.ide.platform.log.Log.logger("index")
                .warn("source index cache save failed: ${it.message}")
        }
    }

    /** Write via a sibling temp file then move into place, so a crash mid-write never leaves a half-written
     *  (corrupt) cache that the next launch would trust. The source build is serialized, so a fixed `.tmp`
     *  name is safe. */
    private fun writeAtomically(target: Path, write: (DataOutputStream) -> Unit) {
        Files.createDirectories(target.parent)
        val tmp = target.resolveSibling("${target.fileName}.tmp")
        DataOutputStream(BufferedOutputStream(Files.newOutputStream(tmp))).use { write(it) }
        runCatching {
            Files.move(
                tmp,
                target,
                StandardCopyOption.ATOMIC_MOVE
            )
        }.onFailure { Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING) }
    }

    // ---- segment files ----

    private fun segmentFile(ext: IndexExtension<*, *>, hash: ContentHash): Path =
        segmentFileForKey(ext, sanitize(hash.value))

    /** The segment path for an already-sanitized content-hash key (the on-disk filename is `<key>.seg`). */
    private fun segmentFileForKey(ext: IndexExtension<*, *>, key: String): Path =
        cacheRoot.resolve(ext.id.value).resolve("v${ext.version}").resolve("$key.seg")

    /** After removing a segment, delete its now-empty parent dirs up to (but not including) [cacheRoot], so a
     *  scoped invalidate leaves no empty `<indexId>/v<n>` shells behind. Stops at the first non-empty dir. */
    private fun pruneEmptyParents(file: Path) {
        var dir: Path? = file.parent
        while (dir != null && dir != cacheRoot && dir.startsWith(cacheRoot)) {
            val d = dir
            val empty =
                runCatching { Files.list(d).use { it.findAny().isEmpty } }.getOrDefault(false)
            if (!empty || !runCatching { Files.deleteIfExists(d) }.getOrDefault(false)) break
            dir = d.parent
        }
    }

    private fun sanitize(s: String) =
        s.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("")

    // ---- artifacts ----

    private sealed class Artifact {
        abstract val label: String
        abstract fun contentHash(): ContentHash
        abstract fun open(): Pair<Sequence<IndexInput>, Closeable>

        class Jar(val path: Path, val stableId: String? = null) : Artifact() {
            override val label get() = jarDisplayLabel(path)
            override fun contentHash(): ContentHash {
                // A bundled/SDK jar carries a caller-supplied stable id (see [IndexScope.stableJarIds]): the host
                // re-extracts it from assets so its on-disk mtime is not stable across launches, which makes the
                // default path+size+mtime key re-index it every launch. The stable id is also PATH-free, so a
                // prebuilt segment shipped under the same id matches on a device with a different absolute path.
                stableId?.let { return ContentHash("jar-$it") }
                val attrs = runCatching {
                    Files.size(path) to Files.getLastModifiedTime(path).toMillis()
                }.getOrDefault(0L to 0L)
                // Key by the artifact's absolute PATH (digested), not just its file name. Every exploded AAR
                // yields a file named `classes.jar`, so a filename+size+mtime key collides across different
                // AARs; and because segments are content-addressed and SHARED, the second AAR would reuse the
                // first's segment, so its classes are never indexed (an authoritative "not on this classpath"
                // miss for, e.g., androidx.core.app.ComponentActivity, which breaks the AppCompatActivity
                // hierarchy). The path uniquely identifies the artifact; size+mtime still re-key it on re-extract.
                return ContentHash("jar-${pathDigest()}-${attrs.first}-${attrs.second}")
            }

            /** Stable 64-bit FNV-1a digest of the absolute path, so two artifacts that share a file name
             *  (every exploded AAR is a `classes.jar`) get distinct, non-colliding content keys. */
            private fun pathDigest(): String {
                val s = runCatching {
                    path.toAbsolutePath().normalize().toString()
                }.getOrDefault(path.toString())
                var h = -3750763034362895579L // FNV-1a offset basis
                for (c in s) h = (h xor c.code.toLong()) * 1099511628211L
                return java.lang.Long.toHexString(h)
            }

            override fun open(): Pair<Sequence<IndexInput>, Closeable> {
                // ART's ZipFile throws `ZipException: No entries` on a zero-entry jar (a resource-only AAR's
                // empty classes.jar) and on a corrupt one; treat an unopenable jar as empty rather than letting
                // it abort the index build. (The desktop JVM opens an empty jar fine, so this only bit on device.)
                val zip = runCatching { ZipFile(path.toFile()) }.getOrNull()
                    ?: return emptySequence<IndexInput>() to Closeable {}
                val hash = contentHash()
                // `.class` (the bytecode indexes) plus `.kotlin_builtins` (Kotlin's intrinsic List/Int/String/…
                // shapes, kept in kotlin-stdlib as protobuf, not bytecode). Each extension's inputFilter selects
                // its own, so the `.class`-only indexes ignore the builtins entries and vice versa.
                val seq = zip.entries().asSequence()
                    .filter { !it.isDirectory && (it.name.endsWith(".class") || it.name.endsWith(".kotlin_builtins")) }
                    .map { entry ->
                        LibraryInput(
                            IndexOrigin.LIBRARY, hash, entry.name, path
                        ) { zip.getInputStream(entry).readBytes() }
                    }
                return seq to Closeable { zip.close() }
            }
        }

        /** A library/SDK SOURCE archive (`-sources.jar`, JDK `src.zip`) or sources DIR (Android `sources/`):
         *  yields its `.java`/`.kt` entries as immutable [IndexOrigin.LIBRARY_SOURCE] units (segment-cached). */
        class SourceArchive(val path: Path) : Artifact() {
            private val isDir get() = Files.isDirectory(path)
            override val label get() = "sources: ${path.fileName}"
            override fun contentHash(): ContentHash {
                // A dir's sources are immutable once installed (SDK), so key by path; a zip by size+mtime.
                if (isDir) return ContentHash("srcdir-$path")
                val attrs = runCatching {
                    Files.size(path) to Files.getLastModifiedTime(path).toMillis()
                }.getOrDefault(0L to 0L)
                return ContentHash("srcjar-${path.fileName}-${attrs.first}-${attrs.second}")
            }

            override fun open(): Pair<Sequence<IndexInput>, Closeable> {
                val hash = contentHash()
                fun isSrc(n: String) = n.endsWith(".java") || n.endsWith(".kt")
                if (isDir) {
                    val files = Files.walk(path).use { s ->
                        s.filter { Files.isRegularFile(it) && isSrc(it.toString()) }
                            .collect(Collectors.toList())
                    }
                    val seq = files.asSequence().map { f ->
                        val rel = runCatching {
                            path.relativize(f).toString()
                        }.getOrDefault(f.fileName.toString())
                        SourceArchiveInput(
                            hash, rel
                        ) { runCatching { Files.readAllBytes(f) }.getOrDefault(ByteArray(0)) }
                    }
                    return seq to Closeable {}
                }
                val zip = ZipFile(path.toFile())
                val seq = zip.entries().asSequence().filter { !it.isDirectory && isSrc(it.name) }
                    .map { entry ->
                        SourceArchiveInput(hash, entry.name) {
                            zip.getInputStream(entry).readBytes()
                        } as IndexInput
                    }
                return seq to Closeable { zip.close() }
            }
        }

        class Jrt(val home: Path) : Artifact() {
            override val label get() = "JDK"
            override fun contentHash() =
                ContentHash("jrt-${home}-${System.getProperty("java.version")}")

            override fun open(): Pair<Sequence<IndexInput>, Closeable> {
                val (fs, ownFs) = openJrt(home)
                if (fs == null) return emptySequence<IndexInput>() to Closeable {}
                val hash = contentHash()
                val modules = fs.getPath("/modules")
                // Only index classes in packages a module actually EXPORTS — real JPMS visibility, so
                // jdk.internal.*, sun.* (non-exported), and other inaccessible internals never appear.
                val exported = exportedPackages(home)
                val inputs = if (Files.exists(modules)) {
                    Files.walk(modules).use { stream ->
                        stream.filter {
                            Files.isRegularFile(it) && it.toString().endsWith(".class")
                        }.map { p ->
                            modules.relativize(p).toString().substringAfter('/') to p
                        } // strip <module>/
                            .filter { accessible(it.first, exported) }.map { np ->
                                LibraryInput(
                                    IndexOrigin.SDK, hash, np.first
                                ) { Files.readAllBytes(np.second) } as IndexInput
                            }.collect(Collectors.toList())
                    }
                } else emptyList()
                return inputs.asSequence() to Closeable { if (ownFs) runCatching { fs.close() } }
            }
        }

        /** An immutable dependency/AAR `res/` dir: yields its `.xml` resource files as [IndexOrigin.LIBRARY]
         *  units, segment-cached by the dir's content. So a large merged dependency `values.xml` is parsed once
         *  and reopened from disk thereafter, not re-scanned into the resident source side every launch. */
        class ResourceDir(val path: Path) : Artifact() {
            override val label get() = "res: ${path.parent?.fileName?.let { "$it/" } ?: ""}${path.fileName}"
            override fun contentHash(): ContentHash {
                // FNV-1a over each `.xml` file's path + size (no read) - re-extraction invalidates it, and
                // identical dependency res across projects hashes to (and shares) one segment.
                var h = -3750763034362895579L // FNV offset basis
                runCatching {
                    Files.walk(path).use { s ->
                        s.filter { Files.isRegularFile(it) && it.toString().endsWith(".xml") }
                            .sorted().forEach { f ->
                                for (c in f.toString()) h = (h xor c.code.toLong()) * 1099511628211L
                                h =
                                    (h xor runCatching { Files.size(f) }.getOrDefault(0L)) * 1099511628211L
                            }
                    }
                }
                return ContentHash("resdir-${path.fileName}-$h")
            }

            override fun open(): Pair<Sequence<IndexInput>, Closeable> {
                val hash = contentHash()
                val files = runCatching {
                    Files.walk(path).use { s ->
                        s.filter {
                            Files.isRegularFile(it) && it.toString().endsWith(".xml")
                        }.collect(Collectors.toList())
                    }
                }.getOrDefault(emptyList())
                val seq = files.asSequence().map { f ->
                    val rel = runCatching {
                        path.relativize(f).toString()
                    }.getOrDefault(f.fileName.toString())
                    ResourceFileInput(
                        hash, rel, f
                    ) { runCatching { Files.readAllBytes(f) }.getOrDefault(ByteArray(0)) } as IndexInput
                }
                return seq to Closeable {}
            }
        }
    }

    /** A `.xml` entry from a dependency/AAR `res/` dir: a [IndexOrigin.LIBRARY] unit carrying the file's text
     *  and its real path (so a resource index reads its `res/<type>/` folder and records a go-to source). */
    private class ResourceFileInput(
        override val contentHash: ContentHash,
        override val unitName: String?,
        override val sourcePath: Path,
        private val readBytes: () -> ByteArray,
    ) : IndexInput {
        override val origin = IndexOrigin.LIBRARY
        private val bytes by lazy { readBytes() }
        override fun bytes(): ByteArray = bytes
        override fun text(): String = bytes.decodeToString()
        override fun dom(): ParsedFile? = null
    }

    private class LibraryInput(
        override val origin: IndexOrigin,
        override val contentHash: ContentHash,
        override val unitName: String?,
        // The owning artifact (the jar) for a library unit; null for jrt/SDK units (served from the jrt image).
        // Lets a locator index record fqcn -> jar so a name environment can open exactly the owning jar.
        override val sourcePath: Path? = null,
        private val readBytes: () -> ByteArray,
    ) : IndexInput {
        override fun bytes(): ByteArray = readBytes()
        override fun text(): String? = null
        override fun dom(): ParsedFile? = null
    }

    /** A `.java`/`.kt` entry from an attached SOURCE archive/dir: immutable, carries source text. */
    private class SourceArchiveInput(
        override val contentHash: ContentHash,
        override val unitName: String?,
        private val readBytes: () -> ByteArray,
    ) : IndexInput {
        override val origin = IndexOrigin.LIBRARY_SOURCE
        override val sourcePath: Path? = null
        private val bytes by lazy { readBytes() }
        override fun bytes(): ByteArray = bytes
        override fun text(): String = bytes.decodeToString()
        override fun dom(): ParsedFile? = null
    }

    private class SourceInput(
        private val file: Path,
        root: Path?,
        private val text: String,
        private val parse: (Path, String) -> ParsedFile?,
        override val fileId: Int = -1,
    ) : IndexInput {
        override val origin = IndexOrigin.SOURCE
        override val contentHash = ContentHash("src-${file}")
        override val unitName: String? =
            (root?.let { runCatching { it.relativize(file).toString() }.getOrNull() }
                ?: file.fileName?.toString())
        override val sourcePath: Path = file
        override fun bytes(): ByteArray = text.toByteArray()
        override fun text(): String = text
        override fun dom(): ParsedFile? = shared("dom") { parse(file, text) }

        // FileContent-style per-file memo: this input is handed to every extension for the file in one pass
        // (see indexSourceFile), which runs them sequentially on a single thread — so a plain map is safe, and
        // an AST parsed by the first Java/Kotlin index is reused by the rest instead of re-parsed per index.
        private val memo = HashMap<String, Any?>()

        @Suppress("UNCHECKED_CAST")
        override fun <T> shared(key: String, compute: () -> T): T {
            if (memo.containsKey(key)) return memo[key] as T
            val v = compute(); memo[key] = v; return v
        }
    }

    companion object {
        /** File-format sentinel for the persisted source cache (manifest + per-ext entry partitions); a
         *  mismatch (older format) is treated as a corrupt cache → discarded → full source rebuild. */
        private const val SOURCE_CACHE_MAGIC = 0x53524331 // "SRC1"

        /**
         * Default resident cap for hot index blocks (desktop). The static side's *entire* RAM cost is this
         * cap + each open segment's tiny sparse term index — flat no matter how large the on-disk indexes
         * grow. Misses just re-read from disk through the kernel page cache.
         */
        const val DEFAULT_BLOCK_CACHE_BYTES = 4L * 1024 * 1024

        /**
         * A tighter cap for memory-constrained devices (on-device/ART). 1 MB ≈ 256 hot 4 KB blocks — ample
         * for a completion query's working set — at a quarter of the desktop heap floor; cold blocks just
         * re-read. The caller, which knows the platform (`isMobilePlatform`), passes this for `blockCacheBytes`.
         */
        const val CONSTRAINED_BLOCK_CACHE_BYTES = 1L * 1024 * 1024

        /** Packages unqualified-exported (or open) by the JDK's modules — the accessible surface. */
        private fun exportedPackages(home: Path): Set<String> {
            val finder = runCatching {
                val current =
                    Paths.get(System.getProperty("java.home")).toAbsolutePath().normalize()
                if (home.toAbsolutePath()
                        .normalize() == current
                ) java.lang.module.ModuleFinder.ofSystem()
                else java.lang.module.ModuleFinder.of(home.resolve("jmods"))
            }.getOrNull() ?: return emptySet()
            val out = HashSet<String>()
            runCatching {
                for (ref in finder.findAll()) {
                    val d = ref.descriptor()
                    if (d.isAutomatic || d.isOpen) {
                        out.addAll(d.packages()); continue
                    }
                    for (e in d.exports()) if (!e.isQualified) out.add(e.source())
                }
            }
            return out
        }

        /** A class entry path "java/util/List.class" is accessible if its package is exported (or, with no
         *  export info, not an obvious internal package). */
        private fun accessible(entry: String, exported: Set<String>): Boolean {
            val pkg = entry.substringBeforeLast('/', "").replace('/', '.')
            if (pkg.isEmpty()) return false
            if (exported.isNotEmpty()) return pkg in exported
            return !(pkg.startsWith("sun.") || pkg == "sun" || pkg.startsWith("jdk.internal") || pkg.startsWith(
                "com.sun."
            ) || ".internal." in ".$pkg.")
        }

        /** Open the jrt image of [home]; returns (fs, weOwnIt). The current JVM's jrt FS must not be closed. */
        private fun openJrt(home: Path): Pair<FileSystem?, Boolean> = runCatching {
            val current = runCatching {
                Paths.get(System.getProperty("java.home")).toAbsolutePath().normalize()
            }.getOrNull()
            if (home.toAbsolutePath().normalize() == current) {
                FileSystems.getFileSystem(URI.create("jrt:/")) to false
            } else {
                FileSystems.newFileSystem(
                    URI.create("jrt:/"), mapOf("java.home" to home.toString())
                ) to true
            }
        }.getOrElse {
            runCatching { FileSystems.getFileSystem(URI.create("jrt:/")) to false }.getOrDefault(
                null to false
            )
        }
    }
}

/** A library jar's display label for the index-status worklist. An AAR is stored exploded as a generic
 *  `classes.jar` (its file name says nothing), so fall back to the exploded directory that holds it, which
 *  carries the artifact name + version: the Maven layout `<name>/<version>/<name>-<version>-exploded/` and the
 *  local layout `<aar-name>/` both name the library there. A plain jar keeps its own file name. */
private fun jarDisplayLabel(path: Path): String {
    val file = path.fileName?.toString() ?: return "jar"
    if (file != "classes.jar") return file
    val dir =
        path.parent?.fileName?.toString()?.removeSuffix("-exploded")?.takeIf { it.isNotBlank() }
    return dir ?: file
}

/** One value's representative hit in the merged set, plus its insertion index (the tie-break key). */
private class RankedHit(val hit: Hit<Any>, val index: Int)

/**
 * Merge the per-source hits into the final ranked result. Exactly equivalent to
 * `hits.sortedByDescending { it.score }.distinctBy { it.value }.take(limit)`, but BOUNDED: rather than fully
 * sorting the whole over-fetched union (each source contributes up to `limit * 8` hits, most of which lose),
 * it dedups by value in one pass — keeping each value's best hit (highest score; ties → earliest inserted) —
 * then selects the top [limit] survivors through a size-[limit] min-heap. So the expensive sort is over the
 * `limit` winners, not the union. Tie-breaking matches the stable sort + distinct: equal scores order by the
 * representative's insertion index ascending.
 */
internal fun rankMerged(hits: List<Hit<Any>>, limit: Int): List<Hit<Any>> {
    if (limit <= 0 || hits.isEmpty()) return emptyList()

    // Dedup pass: per value, the earliest-inserted maximum-score hit (and its insertion index for tie-breaks).
    val bestByValue = HashMap<Any, RankedHit>(hits.size * 2)
    var i = 0
    for (h in hits) {
        val cur = bestByValue[h.value]
        if (cur == null || h.score > cur.hit.score) bestByValue[h.value] = RankedHit(h, i)
        i++
    }

    // Bounded selection via a min-heap whose head is the WORST survivor (lower score, or equal score but later
    // insertion), capped at `limit`: each overflow drops the worst, leaving the top `limit`.
    val worstFirst = compareBy<RankedHit> { it.hit.score }.thenByDescending { it.index }
    val heap = java.util.PriorityQueue(minOf(limit, bestByValue.size) + 1, worstFirst)
    for (r in bestByValue.values) {
        heap.add(r)
        if (heap.size > limit) heap.poll()
    }

    // The heap pops worst-first; fill back-to-front so the result is best-first (score desc, insertion asc).
    val ordered = arrayOfNulls<RankedHit>(heap.size)
    var k = heap.size
    while (heap.isNotEmpty()) ordered[--k] = heap.poll()
    return ordered.map { it!!.hit }
}
