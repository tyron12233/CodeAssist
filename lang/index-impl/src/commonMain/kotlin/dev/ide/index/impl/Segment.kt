package dev.ide.index.impl

import dev.ide.index.Externalizer
import dev.ide.index.Hit
import dev.ide.index.IndexExtension
import dev.ide.index.IndexOrigin
import dev.ide.index.MatchingMode
import dev.ide.platform.FileSource
import dev.ide.platform.openFile
import dev.ide.platform.ByteArrayDataReader
import dev.ide.platform.DataReader

/**
 * An error the caller is already failing on, which must not be mistaken for a corrupt payload.
 *
 * A `StackOverflowError` raised because the CALLER ran out of stack says nothing about this segment, and
 * swallowing it to report a corrupt value is what turned an overflow in the Kotlin resolver into a hard
 * SIGSEGV: the report needs stack of its own, which a thread on its last frames does not have. There is no
 * common type for "the runtime itself is in trouble", so each platform names its own.
 */
internal expect fun rethrowIfFatal(t: Throwable)


/**
 * An immutable, on-disk index partition for one artifact — the disk-backed replacement for holding a
 * library/SDK index in RAM. Written once (per `(indexId, version, artifactHash)`), then queried in place:
 * everything (term dictionary, postings, trigram index, value payloads) lives on disk and is read on
 * demand through a shared [BlockCache]. **Only a sparse term index (every Nth term → file offset) stays
 * resident**, as parallel `String[]`/`long[]` arrays (no per-entry objects, no boxed postings) — so a
 * segment's heap cost is ~`numTerms / SPARSE_INTERVAL` strings, not the whole index.
 *
 * File layout (all offsets inside a region are region-relative; absolute = regionBase + relative):
 * ```
 *   [postings region]        per term: varint count, then count × { (originByte,) varint len, value bytes }
 *   [names region]           sorted; per term: varint termLen, term bytes, varint postingsRel
 *   [trigram names region]   sorted; per gram: varint gramLen, gram bytes, varint tgPostingsRel   (fuzzy only)
 *   [trigram postings region] per gram: varint count, then count × varint(delta of nameRel)        (fuzzy only)
 *   [pool strings region]    per pooled string (id order): varint len, UTF-8 bytes
 *   [pool table region]      numStrings × uN (big-endian; N = 1..4, the min width for the pool size): id → offset
 *   [footer]                 sparse term index + (sparse trigram index) + region bases/lens + magic
 *   [last 8 bytes]           footer start offset (big-endian long)
 * ```
 * Trigram postings store *name* offsets (monotonic, so delta+varint is compact): a fuzzy candidate
 * resolves straight to a name entry without any resident term→offset table.
 *
 * **String constant pool:** inside a value payload every string (written by the externalizer's `writeUTF`) is
 * a varint POOL-ID, not inline bytes. The distinct strings are held once in the pool strings region; the u32
 * pool table turns an id into an offset in O(1). Both pool regions are read on demand through the same
 * [BlockCache], so the pool adds ZERO resident memory — a string shared across many values (an owner FQN
 * repeated on every member of a type, a low-cardinality `kind`) costs one copy on disk instead of one per value.
 */
internal class Segment private constructor(
    private val cache: BlockCache,
    private val segId: Int,
    private val externalizer: Externalizer<Any>,
    private val fuzzyEnabled: Boolean,
    private val numTerms: Int,
    // Origin is a per-artifact property (a jar is all LIBRARY, the SDK all SDK), so a segment's postings almost
    // always share ONE origin; when they do ([uniformOrigin]) the postings drop the per-entry origin byte and
    // every value takes [segOrigin]. A mixed-origin corpus (only tests today) falls back to the per-posting byte.
    private val uniformOrigin: Boolean,
    private val segOrigin: IndexOrigin,
    // The (inclusive) term range this segment covers — [minTerm]..[maxTerm], both empty when [numTerms] == 0.
    // An exact/prefix query whose key can't fall inside it skips the whole segment without any disk read.
    private val minTerm: String,
    private val maxTerm: String,
    // String constant pool: a payload's strings are varint ids into it. [numStrings] pool ids exist; the u32
    // table at [poolTableBase] maps an id to its byte offset within the strings region at [poolStringsBase].
    private val numStrings: Int,
    private val poolTableWidth: Int,
    private val poolStringsBase: Long,
    private val poolTableBase: Long,
    private val postingsBase: Long,
    private val namesBase: Long,
    private val namesLen: Long,
    private val tgNamesBase: Long,
    private val tgNamesLen: Long,
    private val tgPostingsBase: Long,
    // resident — the only heap the segment holds at rest (each a single packed string + primitive arrays,
    // not ~numTerms/SPARSE_INTERVAL separate String objects):
    private val sparseTerms: SparseIndex,
    private val sparseGrams: SparseIndex,
    // Reported once per segment when a value payload will not deserialize. A callback rather than a logger
    // because the log facade is still JVM-only; the JVM opener passes one that logs.
    private val onCorruptValue: (Throwable) -> Unit,
) : AutoCloseable {

    // Set once a value payload in this segment fails to deserialize (see [readPostings]); throttles the warning.
    private var warnedCorrupt = false

    // ---- queries (mirror IndexData's semantics so ranking is identical) ----

    /** Append every value stored under [key] exactly. */
    fun exact(key: String, out: MutableList<Any>) {
        if (numTerms == 0) return
        if (key < minTerm || key > maxTerm) return // outside this segment's term range — nothing to read
        val cur = Cursor(namesBase + sparseTerms.offAt(sparseTerms.floor(key)))
        val end = namesBase + namesLen
        while (cur.pos < end) {
            val term = cur.readString()
            val postingsRel = cur.readVarLong()
            val cmp = term.compareTo(key)
            if (cmp == 0) { readPostings(postingsRel) { v, _ -> out.add(v) }; return }
            if (cmp > 0) return // sorted: we've passed where key would be
        }
    }

    /** Append up to [cap] prefix hits, scored as [Scoring.scorePrefix]. */
    fun prefix(p: String, out: MutableList<Hit<Any>>, cap: Int) {
        if (numTerms == 0) return
        // Skip the whole segment when the prefix window can't overlap [minTerm, maxTerm]: either p sorts past the
        // last term, or the first term already lies beyond p's window (it sorts after p yet doesn't start with p,
        // so it — and everything after it — is >= the window's exclusive upper bound). No disk read either way.
        if (p.isNotEmpty() && (p > maxTerm || (p < minTerm && !minTerm.startsWith(p)))) return
        val cur = Cursor(namesBase + sparseTerms.offAt(sparseTerms.floor(p)))
        val end = namesBase + namesLen
        while (cur.pos < end) {
            val term = cur.readString()
            val postingsRel = cur.readVarLong()
            when {
                term < p -> {} // still before the prefix window (sparse landed us just before it)
                term.startsWith(p) -> readPostings(postingsRel) { v, origin ->
                    out.add(Hit(term, v, Scoring.scorePrefix(term, p, origin)))
                    if (out.size >= cap) return
                }
                else -> return // term > p and not a prefix → past the window
            }
        }
    }

    /** Append up to [cap] fuzzy/substring/camel-hump hits, scored as [Scoring.scoreFuzzy]. */
    fun fuzzy(pattern: String, out: MutableList<Hit<Any>>, cap: Int) {
        if (numTerms == 0) return
        val hump = Scoring.humpQuery(pattern)
        val useTrigrams = fuzzyEnabled && pattern.length >= 3
        if (!useTrigrams && !hump) {
            // Below trigram length there is no posting list to intersect; scan the two first-character
            // windows so a short pattern still matches case-insensitively (prefix tiers only, no noise).
            if (pattern.length < 3) {
                val lo = pattern[0].lowercaseChar()
                val up = pattern[0].uppercaseChar()
                windowScan(lo, out, cap, null) { t, o -> Scoring.scorePrefixCi(t, pattern, o) }
                if (up != lo) windowScan(up, out, cap, null) { t, o -> Scoring.scorePrefixCi(t, pattern, o) }
            } else prefix(pattern, out, cap)
            return
        }

        // A camel-hump match shares no contiguous trigram with the pattern, so trigram intersection can
        // never find it; every hump match is anchored at the term's first char, so scan those two windows
        // (skipping terms the trigram pass already scored).
        val seen: MutableSet<String>? = if (hump && useTrigrams) HashSet() else null
        if (useTrigrams) {
            // Each gram's posting list is the ascending name offsets that contain it; intersect them. A gram
            // absent from the corpus is SKIPPED (not treated as an empty intersection) — same as IndexData, so a
            // pattern with a never-seen trigram still yields candidates via its other grams + the scorer.
            var candidates: LongArray? = null
            for (g in Scoring.trigramsOf(pattern.lowercase())) {
                val list = trigramPostings(g) ?: continue
                candidates = if (candidates == null) list else intersectSorted(candidates, list)
                if (candidates.isEmpty()) break
            }
            for (nameRel in candidates ?: LongArray(0)) {
                val cur = Cursor(namesBase + nameRel)
                val term = cur.readString()
                val postingsRel = cur.readVarLong()
                seen?.add(term)
                readPostings(postingsRel) { v, origin ->
                    val s = Scoring.scoreFuzzy(term, pattern, origin)
                    if (s > 0) {
                        out.add(Hit(term, v, s))
                        if (out.size >= cap) return
                    }
                }
            }
        }
        if (hump && out.size < cap) {
            val lo = pattern[0].lowercaseChar()
            val up = pattern[0].uppercaseChar()
            windowScan(lo, out, cap, seen) { t, o -> Scoring.scoreFuzzy(t, pattern, o) }
            if (up != lo) windowScan(up, out, cap, seen) { t, o -> Scoring.scoreFuzzy(t, pattern, o) }
        }
    }

    /** Scan the window of terms starting with [first], scoring each via [score] (skipping [seen] terms). */
    private inline fun windowScan(
        first: Char, out: MutableList<Hit<Any>>, cap: Int, seen: Set<String>?,
        score: (String, IndexOrigin) -> Int,
    ) {
        if (numTerms == 0 || out.size >= cap) return
        val p = first.toString()
        val cur = Cursor(namesBase + sparseTerms.offAt(sparseTerms.floor(p)))
        val end = namesBase + namesLen
        while (cur.pos < end) {
            val term = cur.readString()
            val postingsRel = cur.readVarLong()
            when {
                term < p -> {} // still before the window (sparse landed us just before it)
                term.startsWith(p) -> if (seen == null || term !in seen) readPostings(postingsRel) { v, origin ->
                    val s = score(term, origin)
                    if (s > 0) {
                        out.add(Hit(term, v, s))
                        if (out.size >= cap) return
                    }
                }
                else -> return // past the window
            }
        }
    }

    override fun close() {
        // Drops this segment's cached blocks AND closes + forgets its pooled channel.
        cache.evictSegment(segId)
    }

    // ---- on-disk reads ----

    /** The ascending name offsets whose term contains trigram [g], or null if [g] is absent. */
    private fun trigramPostings(g: String): LongArray? {
        if (sparseGrams.size == 0) return null // no trigram region (e.g. all terms shorter than a trigram)
        val cur = Cursor(tgNamesBase + sparseGrams.offAt(sparseGrams.floor(g)))
        val end = tgNamesBase + tgNamesLen
        while (cur.pos < end) {
            val gram = cur.readString()
            val tgPostingsRel = cur.readVarLong()
            val cmp = gram.compareTo(g)
            if (cmp == 0) {
                val pc = Cursor(tgPostingsBase + tgPostingsRel)
                val n = pc.readVarLong().toInt()
                val arr = LongArray(n)
                var prev = 0L
                for (i in 0 until n) { prev += pc.readVarLong(); arr[i] = prev }
                return arr
            }
            if (cmp > 0) return null
        }
        return null
    }

    /** Decode the value payloads at [postingsRel] and feed each to [emit] (non-local return supported). */
    private inline fun readPostings(postingsRel: Long, emit: (Any, IndexOrigin) -> Unit) {
        val cur = Cursor(postingsBase + postingsRel)
        val count = cur.readVarLong().toInt()
        repeat(count) {
            // A uniform-origin segment stored its origin once in the footer; only a mixed one carries a byte here.
            val origin = if (uniformOrigin) segOrigin else IndexOrigin.entries[cur.readByte()]
            // Each payload is independently length-framed, so [cur] is advanced past this value BEFORE the
            // externalizer runs — a value that fails to deserialize (a stale/corrupt segment: e.g. a shared
            // externalizer whose format drifted without a version bump) is skipped, not fatal, and the cursor
            // stays aligned for the next value. Degrading a query beats crashing the caller (e.g. completion).
            val payload = cur.readBytes(cur.readVarLong().toInt())
            val value = try {
                // The externalizer reads its strings via [PoolingDataInput.readUTF], which turns the payload's
                // varint pool-id back into the pooled string; every other field is read inline as before.
                externalizer.read(PoolingDataInput(ByteArrayDataReader(payload)) { id -> poolString(id) })
            } catch (t: Throwable) {
                // A StackOverflowError (or OOM) raised because the CALLER is already out of stack/heap says
                // nothing about this segment, and mistaking it for a corrupt payload is what turned an
                // overflow in the Kotlin resolver into a hard SIGSEGV: the warning below reports, and a
                // report needs stack of its own, which a thread on its last frames does not have. Propagate
                // to the caller's own guard instead of swallowing it and reporting.
                rethrowIfFatal(t)
                warnCorruptOnce(t)
                return@repeat
            }
            emit(value, origin)
        }
    }

    /** The pooled string for [id]: read its offset from the u32 table, then the length-framed bytes. Both reads
     *  are served by the [BlockCache] (no resident pool), and hot strings keep their blocks cache-warm. */
    private fun poolString(id: Int): String {
        if (id < 0 || id >= numStrings) return "" // a corrupt id degrades to empty rather than crashing the query
        val off = Cursor(poolTableBase + id.toLong() * poolTableWidth).readFixedUInt(poolTableWidth)
        val sc = Cursor(poolStringsBase + off)
        return sc.readBytes(sc.readVarLong().toInt()).decodeToString()
    }

    /** Log the first unreadable value in this segment (throttled to once); the query then skips it and continues. */
    private fun warnCorruptOnce(t: Throwable) {
        if (warnedCorrupt) return
        warnedCorrupt = true
        onCorruptValue(t)
    }

    /** A forward byte reader over the channel, served block-by-block from the [cache]. */
    private inner class Cursor(var pos: Long) {
        private var blk: ByteArray? = null
        private var blkBase = -1L

        private fun ensure() {
            val base = pos / cache.blockSize * cache.blockSize
            if (blk == null || base != blkBase) { blk = cache.block(segId, pos); blkBase = base }
        }

        fun readByte(): Int { ensure(); val b = blk!![(pos - blkBase).toInt()]; pos++; return b.toInt() and 0xFF }

        fun readVarLong(): Long {
            var shift = 0; var r = 0L
            while (true) {
                val b = readByte()
                r = r or ((b.toLong() and 0x7F) shl shift)
                if (b < 0x80) return r
                shift += 7
            }
        }

        fun readBytes(n: Int): ByteArray {
            val out = ByteArray(n)
            var i = 0
            while (i < n) {
                ensure()
                val off = (pos - blkBase).toInt()
                val take = minOf(blk!!.size - off, n - i)
                blk!!.copyInto(out, destinationOffset = i, startIndex = off, endIndex = off + take)
                i += take; pos += take
            }
            return out
        }

        fun readString(): String = readBytes(readVarLong().toInt()).decodeToString()

        /** A big-endian unsigned int of [width] bytes (a pool-table entry), returned widened to Long. */
        fun readFixedUInt(width: Int): Long {
            var r = 0L
            repeat(width) { r = (r shl 8) or readByte().toLong() }
            return r
        }
    }

    /**
     * The resident sparse term/gram index: every [SPARSE_INTERVAL]-th key → its file offset. Keys are packed
     * into ONE compact [String] (all keys concatenated) plus an int start table, NOT an `Array<String>` of
     * ~`numTerms / SPARSE_INTERVAL` separate String objects — so a segment's at-rest heap is one string + two
     * primitive arrays, regardless of how many sparse keys it has. Comparison is char-by-char against the
     * concatenated buffer, identical to [String.compareTo] (the order the on-disk names are sorted in), so the
     * binary search lands exactly where the per-String version did.
     */
    private class SparseIndex(private val concat: String, private val starts: IntArray, private val off: LongArray) {
        val size: Int get() = off.size

        fun offAt(i: Int): Long = off[i]

        /** Largest index `i` whose key is `<= key`, clamped to 0 (callers only invoke this on a non-empty index). */
        fun floor(key: String): Int {
            var lo = 0; var hi = off.size - 1; var ans = 0
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (compareKey(mid, key) <= 0) { ans = mid; lo = mid + 1 } else hi = mid - 1
            }
            return ans
        }

        /** `concat[starts[i], starts[i+1])` compared to [key] char-by-char (no substring) — == [String.compareTo]. */
        private fun compareKey(i: Int, key: String): Int {
            val s = starts[i]; val e = starts[i + 1]
            val n = minOf(e - s, key.length)
            var k = 0
            while (k < n) {
                val c = concat[s + k].compareTo(key[k])
                if (c != 0) return c
                k++
            }
            return (e - s) - key.length
        }

        companion object {
            val EMPTY = SparseIndex("", intArrayOf(0), LongArray(0))
        }
    }

    companion object {
        // "IDX3": v3 adds a per-segment string constant pool — value payloads store a varint pool-id in place
        // of each inline string, so a string repeated across values (an owner FQN, a package prefix, a `kind`)
        // is stored ONCE. v2 folded a uniform postings origin into one footer byte and added the [minTerm]..
        // [maxTerm] skip range; v1 was the original. Each bump so a stale segment fails [open] (require) and is
        // transparently rebuilt (indexArtifact's runCatching skips the failed open → the artifact needs a build).
        internal const val MAGIC = 0x49445833 // "IDX3"
        const val SPARSE_INTERVAL = 64
        /** Open an existing segment file: read the footer (resident sparse index), keep the file for reads. */
        fun open(
            file: String,
            ext: IndexExtension<*, *>,
            cache: BlockCache,
            segId: Int,
            onCorruptValue: (Throwable) -> Unit = {},
        ): Segment {
            // The footer is read through a transient handle, closed immediately; later block reads reopen the
            // file lazily through the cache's bounded handle pool (see [BlockCache]).
            val source = openFile(file) ?: throw SegmentClosedException()
            val r = try {
                val size = source.size
                val footerStart = readLongAt(source, size - 8)
                ByteReader(source.read(footerStart, (size - 8 - footerStart).toInt()))
            } finally {
                source.close()
            }
            run {

                val sparseTerms = readSparseIndex(r)

                val fuzzy = r.readByte() != 0
                val sparseGrams = if (fuzzy) readSparseIndex(r) else SparseIndex.EMPTY

                r.readInt() // ext.version — informational; the cache path already keys on it
                val numTerms = r.readVarLong().toInt()
                val uniformOrigin = r.readByte() != 0
                val segOrigin = IndexOrigin.entries[r.readByte()]
                val minTerm = r.readString()
                val maxTerm = r.readString()
                val numStrings = r.readVarLong().toInt()
                val poolTableWidth = r.readByte()
                val poolStringsBase = r.readVarLong()
                val poolTableBase = r.readVarLong()
                val postingsBase = r.readVarLong()
                r.readVarLong() // postingsLen (unused at read time)
                val namesBase = r.readVarLong(); val namesLen = r.readVarLong()
                val tgNamesBase = r.readVarLong(); val tgNamesLen = r.readVarLong()
                val tgPostingsBase = r.readVarLong(); r.readVarLong() // tgPostingsLen (unused)
                require(r.readInt() == MAGIC) { "bad index segment magic in $file" }

                cache.registerSegment(segId, file)
                @Suppress("UNCHECKED_CAST")
                return Segment(
                    cache, segId, ext.valueExternalizer as Externalizer<Any>,
                    fuzzy, numTerms, uniformOrigin, segOrigin, minTerm, maxTerm,
                    numStrings, poolTableWidth, poolStringsBase, poolTableBase,
                    postingsBase, namesBase, namesLen,
                    tgNamesBase, tgNamesLen, tgPostingsBase,
                    sparseTerms, sparseGrams, onCorruptValue,
                )
            }
        }

        /** Read a footer sparse block (the count, then each `key` + its file offset) into a packed [SparseIndex]. */
        private fun readSparseIndex(r: ByteReader): SparseIndex {
            val n = r.readVarLong().toInt()
            val sb = StringBuilder()
            val starts = IntArray(n + 1)
            val off = LongArray(n)
            for (i in 0 until n) { starts[i] = sb.length; sb.append(r.readString()); off[i] = r.readVarLong() }
            starts[n] = sb.length
            return SparseIndex(sb.toString(), starts, off)
        }

        /** Two-pointer intersection of two ascending arrays (no boxing). */
        private fun intersectSorted(a: LongArray, b: LongArray): LongArray {
            val out = LongArray(minOf(a.size, b.size))
            var i = 0; var j = 0; var k = 0
            while (i < a.size && j < b.size) {
                val x = a[i]; val y = b[j]
                when {
                    x < y -> i++
                    x > y -> j++
                    else -> { out[k++] = x; i++; j++ }
                }
            }
            return if (k == out.size) out else out.copyOf(k)
        }

        private fun readLongAt(source: FileSource, pos: Long): Long {
            val b = source.read(pos, 8)
            var v = 0L
            for (i in 0 until 8) v = (v shl 8) or (b[i].toLong() and 0xFF)
            return v
        }
    }
}

/** The fewest bytes (1–4) that can hold any offset in `[0, size)` — the pool table's per-entry width. */
internal fun poolOffsetWidth(size: Int): Int = when {
    size <= 0x100 -> 1
    size <= 0x10000 -> 2
    size <= 0x1000000 -> 3
    else -> 4
}

/** A cursor over an in-memory byte array (the segment footer) with the same varint encoding as [Segment.Cursor]. */
private class ByteReader(private val a: ByteArray) {
    private var p = 0
    fun readByte(): Int = a[p++].toInt() and 0xFF
    fun readInt(): Int = (readByte() shl 24) or (readByte() shl 16) or (readByte() shl 8) or readByte()
    fun readVarLong(): Long {
        var shift = 0; var r = 0L
        while (true) {
            val b = readByte()
            r = r or ((b.toLong() and 0x7F) shl shift)
            if (b < 0x80) return r
            shift += 7
        }
    }
    fun readString(): String {
        val n = readVarLong().toInt()
        return a.decodeToString(p, p + n).also { p += n }
    }
}

/** Unsigned LEB128 read, the mirror of [writeVarLong] — used to read back the spill temp files. */
internal fun DataReader.readVarLong(): Long {
    var shift = 0; var r = 0L
    while (true) {
        val b = readUnsignedByte()
        r = r or ((b.toLong() and 0x7F) shl shift)
        if (b < 0x80) return r
        shift += 7
    }
}

/**
 * The read mirror of [PoolingDataOutput]: [readUTF] reads a varint pool-id and resolves it to the pooled
 * string via [deref]; every other read delegates to the payload stream. Reading past the framed payload
 * (a drifted/corrupt value) throws exactly as before, so the skip-and-continue path is preserved.
 */
private class PoolingDataInput(private val d: DataReader, private val deref: (Int) -> String) : DataReader {
    override fun readUTF(): String = deref(d.readVarLong().toInt())
    override fun readBoolean(): Boolean = d.readBoolean()
    override fun readByte(): Int = d.readByte().toInt()
    override fun readUnsignedByte(): Int = d.readUnsignedByte()
    override fun readShort(): Int = d.readShort().toInt()
    override fun readInt(): Int = d.readInt()
    override fun readLong(): Long = d.readLong()
}
