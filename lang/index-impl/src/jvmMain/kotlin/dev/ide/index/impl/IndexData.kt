package dev.ide.index.impl

import dev.ide.index.Hit
import dev.ide.index.IndexOrigin
import dev.ide.index.MatchingMode
import java.util.TreeMap

/**
 * The in-memory, **file-partitioned** source-side index for one extension: a sorted term dictionary
 * (TreeMap) over postings, plus an optional trigram index for fuzzy/substring. This is the pragmatic
 * stand-in for the doc's mmap'd front-coded segments — same query semantics, loaded into RAM (small: names
 * are short). The static (SDK + libraries) side lives in disk-backed [Segment]s; this holds only project
 * source, which changes every keystroke.
 *
 * It is the SINGLE store for the source side — entries are NOT also held in a separate per-file map. A save
 * mutates ONE file incrementally ([setFile]/[removeFile]): the edited file's old docs are tombstoned and its
 * new ones appended, so the cost is O(edited file), not O(whole project) — the old design re-added every
 * entry of every file on each save (and re-lowercased + re-trigrammed every term). Tombstoned slots (and the
 * stale ids they leave in postings) are reclaimed by a rare [compact] once they outnumber the live docs.
 *
 * Postings are kept in packed [IntList]s, not `ArrayList<Int>`. For a resource-heavy Android project (every
 * dependency/AAR `res/` file is indexed here), the postings — especially the trigram index's — dominate the
 * footprint. A boxed `Integer` in an `ArrayList` costs ~20 bytes; a packed `int` costs 4. Storing postings as
 * `IntArray`s cuts the resident memory several-fold, which keeps the index inside a tight device heap.
 *
 * Threading: the source build is serialized (one background thread), queries run on any thread. The whole
 * mutable state is grouped in a [Store] behind a single `@Volatile` reference, so a query takes a consistent
 * snapshot with one read and a [compact] publishes its rebuilt state atomically (no torn swap). Incremental
 * edits mutate the current store in place (the same eventually-consistent profile the editor already tolerates).
 */
internal class IndexData(matching: MatchingMode) {

    private val fuzzy = matching == MatchingMode.PREFIX_AND_FUZZY

    /** A growable primitive `int` list — a posting list of ascending docIds, without `Integer` boxing. */
    private class IntList {
        private var a = IntArray(1)
        var size = 0
            private set

        fun add(v: Int) {
            if (size == a.size) a = a.copyOf(if (a.size < 1024) a.size * 2 else a.size + (a.size shr 1))
            a[size++] = v
        }

        operator fun get(i: Int): Int = a[i]
        fun toIntArray(): IntArray = a.copyOf(size)
    }

    /**
     * The whole mutable index state, grouped so a [compact] can publish a fresh copy with ONE volatile write
     * and queries snapshot it with ONE read. Doc fields are parallel arrays addressed by a dense docId; a
     * tombstoned doc has `valueById[id] == null` (its slot survives until the next compaction — ids are NOT
     * recycled, since postings still reference them under the doc's old term).
     */
    private class Store(fuzzy: Boolean) {
        val termById = ArrayList<String?>()   // docId -> term  (null once tombstoned)
        val valueById = ArrayList<Any?>()     // docId -> value (null once tombstoned)
        val originById = ArrayList<IndexOrigin>()
        val terms = TreeMap<String, IntList>()                                    // term  -> ascending docIds
        val trigrams: HashMap<String, IntList>? = if (fuzzy) HashMap() else null  // gram  -> ascending docIds
        val docsByFile = HashMap<Int, IntList>()                                  // fileId -> the docIds it owns
        var liveDocs = 0
        var deadDocs = 0
    }

    @Volatile private var store = Store(fuzzy)

    /** Cumulative count of (term, value) entries appended via [setFile] — instrumentation only, so a test can
     *  assert a save re-adds just the edited file's entries (cost independent of project size). NOT bumped by
     *  [compact]'s internal rebuild. */
    @JvmField internal var addOps: Long = 0L

    /** Number of live (non-tombstoned) entries — for diagnostics/memory reporting. */
    fun size(): Int = store.liveDocs

    // ---- incremental, file-partitioned updates (the source side mutates one file at a time on edit) ----

    /** Replace [fileId]'s entries with [entries] (tombstone the old, append the new). O(entries), not O(project). */
    fun setFile(fileId: Int, entries: List<IndexEntry>) {
        val s = store
        purge(s, fileId)
        if (entries.isNotEmpty()) {
            val ids = IntList()
            for (e in entries) { ids.add(addEntry(s, e.term, e.value, e.origin)); s.liveDocs++ }
            s.docsByFile[fileId] = ids
            addOps += entries.size
        }
        maybeCompact()
    }

    /** Drop [fileId]'s entries — a deleted/moved file, or one an extension's filter no longer accepts. */
    fun removeFile(fileId: Int) {
        purge(store, fileId)
        maybeCompact()
    }

    /** Append a single entry not tied to any project file — for tests and ad-hoc corpora. Kept in one reserved
     *  partition so it survives [compact]; the incremental source path uses [setFile] instead. */
    fun add(term: String, value: Any, origin: IndexOrigin) {
        val s = store
        val id = addEntry(s, term, value, origin)
        s.liveDocs++
        s.docsByFile.getOrPut(AD_HOC_FILE) { IntList() }.add(id)
        addOps += 1
    }

    /** Tombstone every doc currently owned by [fileId] and forget the file→docs mapping. */
    private fun purge(s: Store, fileId: Int) {
        val ids = s.docsByFile.remove(fileId) ?: return
        for (i in 0 until ids.size) {
            val id = ids[i]
            if (s.valueById[id] != null) { s.valueById[id] = null; s.termById[id] = null; s.liveDocs--; s.deadDocs++ }
        }
    }

    /** Append one entry, returning its new docId. Shared by [setFile] and [compact] (the latter doesn't count
     *  against [addOps]). */
    private fun addEntry(s: Store, term: String, value: Any, origin: IndexOrigin): Int {
        val id = s.valueById.size
        s.termById.add(term); s.valueById.add(value); s.originById.add(origin)
        s.terms.getOrPut(term) { IntList() }.add(id)
        s.trigrams?.let { tg -> for (g in Scoring.trigramsOf(term.lowercase())) tg.getOrPut(g) { IntList() }.add(id) }
        return id
    }

    fun clear() { store = Store(fuzzy) }

    // ---- per-file enumeration (drives the persisted source cache) ----

    /** A snapshot of the file ids currently holding entries. */
    fun fileIds(): Set<Int> = HashSet(store.docsByFile.keys)

    /** The live entries [fileId] currently contributes (for persisting the per-file partition). */
    fun entriesOf(fileId: Int): List<IndexEntry> {
        val s = store
        val ids = s.docsByFile[fileId] ?: return emptyList()
        val out = ArrayList<IndexEntry>(ids.size)
        for (i in 0 until ids.size) {
            val id = ids[i]
            val v = s.valueById[id] ?: continue
            out.add(IndexEntry(s.termById[id]!!, v, s.originById[id]))
        }
        return out
    }

    // ---- queries (one volatile read snapshots a consistent store; tombstones are skipped) ----

    fun exact(key: String): List<Any> {
        val s = store
        val ids = s.terms[key] ?: return emptyList()
        val out = ArrayList<Any>(ids.size)
        for (i in 0 until ids.size) { val v = s.valueById[ids[i]] ?: continue; out.add(v) }
        return out
    }

    fun prefix(p: String, out: MutableList<Hit<Any>>, cap: Int) {
        val s = store
        for ((term, ids) in s.terms.tailMap(p)) {
            if (!term.startsWith(p)) break
            for (i in 0 until ids.size) {
                val id = ids[i]
                val v = s.valueById[id] ?: continue
                out.add(Hit(term, v, Scoring.scorePrefix(term, p, s.originById[id])))
                if (out.size >= cap) return
            }
        }
    }

    fun fuzzy(pattern: String, out: MutableList<Hit<Any>>, cap: Int) {
        val s = store
        val tg = s.trigrams
        val hump = Scoring.humpQuery(pattern)
        val useTrigrams = tg != null && pattern.length >= 3
        if (!useTrigrams && !hump) {
            // Below trigram length there is no posting list to intersect; scan the two first-character
            // windows so a short pattern still matches case-insensitively (prefix tiers only, no noise).
            if (pattern.isNotEmpty() && pattern.length < 3) {
                val lo = pattern[0].lowercaseChar()
                val up = pattern[0].uppercaseChar()
                windowScan(s, lo, out, cap, null) { t, o -> Scoring.scorePrefixCi(t, pattern, o) }
                if (up != lo) windowScan(s, up, out, cap, null) { t, o -> Scoring.scorePrefixCi(t, pattern, o) }
            } else prefix(pattern, out, cap)
            return
        }
        // A camel-hump match shares no contiguous trigram with the pattern (trigram intersection can never
        // surface it); every hump match is anchored at the term's first char, so those two windows are
        // scanned after the trigram pass (skipping ids it already scored) — mirrors [Segment.fuzzy].
        val seen: MutableSet<Int>? = if (hump && useTrigrams) HashSet() else null
        if (useTrigrams) {
            val grams = Scoring.trigramsOf(pattern.lowercase())
            // Smallest-postings-first, sorted-merge intersection — no Integer boxing and no HashSet (postings are
            // ascending docIds). A gram absent from the corpus is SKIPPED, not treated as an empty intersection,
            // which mirrors [Segment.fuzzy] so a candidate is found identically on both sides.
            val lists = ArrayList<IntList>(grams.size)
            for (g in grams) { lists.add(tg!![g] ?: continue) }
            var acc: IntArray? = if (lists.isEmpty()) null else {
                lists.sortBy { it.size }
                var a = lists[0].toIntArray()
                for (i in 1 until lists.size) {
                    a = intersectSorted(a, lists[i])
                    if (a.isEmpty()) break
                }
                a
            }
            for (id in acc ?: IntArray(0)) {
                seen?.add(id)
                val v = s.valueById[id] ?: continue
                val t = s.termById[id] ?: continue
                val sc = Scoring.scoreFuzzy(t, pattern, s.originById[id])
                if (sc > 0) { out.add(Hit(t, v, sc)); if (out.size >= cap) return }
            }
        }
        if (hump && out.size < cap) {
            val lo = pattern[0].lowercaseChar()
            val up = pattern[0].uppercaseChar()
            windowScan(s, lo, out, cap, seen) { t, o -> Scoring.scoreFuzzy(t, pattern, o) }
            if (up != lo) windowScan(s, up, out, cap, seen) { t, o -> Scoring.scoreFuzzy(t, pattern, o) }
        }
    }

    /** Scan the window of terms starting with [first], scoring each entry via [score] (skipping [seen] ids). */
    private inline fun windowScan(
        s: Store, first: Char, out: MutableList<Hit<Any>>, cap: Int, seen: Set<Int>?,
        score: (String, IndexOrigin) -> Int,
    ) {
        if (out.size >= cap) return
        val p = first.toString()
        for ((term, ids) in s.terms.tailMap(p)) {
            if (!term.startsWith(p)) break
            for (i in 0 until ids.size) {
                val id = ids[i]
                if (seen != null && id in seen) continue
                val v = s.valueById[id] ?: continue
                val sc = score(term, s.originById[id])
                if (sc > 0) { out.add(Hit(term, v, sc)); if (out.size >= cap) return }
            }
        }
    }

    /** Two-pointer intersection of an ascending array with an ascending posting list (no boxing). */
    private fun intersectSorted(a: IntArray, b: IntList): IntArray {
        val out = IntArray(minOf(a.size, b.size))
        var i = 0; var j = 0; var n = 0
        while (i < a.size && j < b.size) {
            val x = a[i]; val y = b[j]
            when {
                x < y -> i++
                x > y -> j++
                else -> { out[n++] = x; i++; j++ }
            }
        }
        return if (n == out.size) out else out.copyOf(n)
    }

    // ---- compaction ----

    private fun maybeCompact() {
        val s = store
        // Tombstones (and the stale ids they leave in postings) accumulate as files are re-saved. Rebuild a
        // dense store once they outnumber the live docs, with a floor so a tiny, heavily-edited project never
        // churns. Rare and O(live); between compactions every edit stays O(edited file).
        if (s.deadDocs >= COMPACT_FLOOR && s.deadDocs >= s.liveDocs) compact(s)
    }

    private fun compact(old: Store) {
        val s = Store(fuzzy)
        for ((fileId, ids) in old.docsByFile) {
            val nids = IntList()
            for (i in 0 until ids.size) {
                val oldId = ids[i]
                val v = old.valueById[oldId] ?: continue
                nids.add(addEntry(s, old.termById[oldId]!!, v, old.originById[oldId])); s.liveDocs++
            }
            if (nids.size > 0) s.docsByFile[fileId] = nids
        }
        store = s // single volatile publish — in-flight queries finish on the old snapshot
    }

    private companion object {
        /** Floor below which the dead-ratio rebuild never fires, so a small project's edits don't churn it. */
        const val COMPACT_FLOOR = 256

        /** Reserved partition for file-less [add] entries (tests/ad-hoc corpora), so they survive [compact]. */
        const val AD_HOC_FILE = Int.MIN_VALUE
    }
}
