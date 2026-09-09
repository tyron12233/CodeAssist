package dev.ide.index.impl

import dev.ide.index.Externalizer
import dev.ide.index.Hit
import dev.ide.index.IndexExtension
import dev.ide.index.IndexOrigin
import dev.ide.index.MatchingMode
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInput
import java.io.DataInputStream
import java.io.DataOutput
import java.io.DataOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.PriorityQueue
import java.util.UUID

/** One (term, value, origin) entry handed to the indexer — the unit both the segment and the source side store. */
internal class IndexEntry(val term: String, val value: Any, val origin: IndexOrigin)

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
) : Closeable {

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
                DataInputStream(ByteArrayInputStream(payload)).use { dis ->
                    externalizer.read(PoolingDataInput(dis) { id -> poolString(id) })
                }
            } catch (e: VirtualMachineError) {
                // A StackOverflowError (or OOM) raised because the CALLER is already out of stack/heap says
                // nothing about this segment, and mistaking it for a corrupt payload is what turned an
                // overflow in the Kotlin resolver into a hard SIGSEGV: [warnCorruptOnce] logs, and the log's
                // native `println` needs stack of its own, which a thread on its last frames doesn't have.
                // Propagate to the caller's own guard instead of swallowing it and logging.
                throw e
            } catch (t: Throwable) {
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
        return String(sc.readBytes(sc.readVarLong().toInt()), Charsets.UTF_8)
    }

    /** Log the first unreadable value in this segment (throttled to once); the query then skips it and continues. */
    private fun warnCorruptOnce(t: Throwable) {
        if (warnedCorrupt) return
        warnedCorrupt = true
        LOG.warn("index segment $segId has unreadable value(s) (stale/corrupt payload) — skipping; a re-index will rebuild it", t)
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
                System.arraycopy(blk!!, off, out, i, take)
                i += take; pos += take
            }
            return out
        }

        fun readString(): String = String(readBytes(readVarLong().toInt()), Charsets.UTF_8)

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
        private val LOG = dev.ide.platform.log.Log.logger("index")

        /** Open an existing segment file: read the footer (resident sparse index), keep the channel for reads. */
        fun open(file: Path, ext: IndexExtension<*, *>, cache: BlockCache, segId: Int): Segment =
            // The footer is read through a transient channel, closed immediately by `use`; later block reads
            // reopen the file lazily through the cache's bounded channel pool (see [BlockCache]).
            FileChannel.open(file, StandardOpenOption.READ).use { channel ->
                val size = channel.size()
                val footerStart = readLongAt(channel, size - 8)
                val footer = readFully(channel, footerStart, (size - 8 - footerStart).toInt())
                val r = ByteReader(footer)

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
                Segment(
                    cache, segId, ext.valueExternalizer as Externalizer<Any>,
                    fuzzy, numTerms, uniformOrigin, segOrigin, minTerm, maxTerm,
                    numStrings, poolTableWidth, poolStringsBase, poolTableBase,
                    postingsBase, namesBase, namesLen,
                    tgNamesBase, tgNamesLen, tgPostingsBase,
                    sparseTerms, sparseGrams,
                )
            }

        /** Build a segment file from a full [entries] list. A convenience over [SegmentWriter] for callers that
         *  already hold every entry in memory (tests, small/whole-list builds); the engine streams via
         *  [SegmentWriter.add] instead so a large artifact never buffers all its entries at once. */
        fun write(file: Path, ext: IndexExtension<*, *>, entries: List<IndexEntry>) {
            SegmentWriter(file, ext).use { w ->
                for (e in entries) w.add(e.term, e.value, e.origin)
                w.finish()
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

        private fun readLongAt(channel: FileChannel, pos: Long): Long {
            val buf = ByteBuffer.allocate(8)
            readInto(channel, buf, pos)
            buf.flip(); return buf.long
        }

        private fun readFully(channel: FileChannel, pos: Long, len: Int): ByteArray {
            val buf = ByteBuffer.allocate(len)
            readInto(channel, buf, pos)
            return buf.array()
        }

        private fun readInto(channel: FileChannel, buf: ByteBuffer, pos: Long) {
            var p = pos
            while (buf.hasRemaining()) {
                val n = channel.read(buf, p)
                if (n <= 0) break
                p += n
            }
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

/** Write [v] as a fixed [width]-byte big-endian unsigned int (a pool-table entry). */
internal fun DataOutputStream.writeFixedUInt(v: Int, width: Int) {
    var shift = (width - 1) * 8
    while (shift >= 0) { writeByte((v ushr shift) and 0xFF); shift -= 8 }
}

/** Unsigned LEB128 — small values cost one byte; offsets/counts/deltas in a segment are all non-negative. */
internal fun DataOutputStream.writeVarLong(v0: Long) {
    var v = v0
    while (true) {
        val b = (v and 0x7F).toInt()
        v = v ushr 7
        if (v != 0L) writeByte(b or 0x80) else { writeByte(b); return }
    }
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
        return String(a, p, n, Charsets.UTF_8).also { p += n }
    }
}

/** Unsigned LEB128 read, the mirror of [writeVarLong] — used to read back the spill temp files. */
internal fun DataInputStream.readVarLong(): Long {
    var shift = 0; var r = 0L
    while (true) {
        val b = readUnsignedByte()
        r = r or ((b.toLong() and 0x7F) shl shift)
        if (b < 0x80) return r
        shift += 7
    }
}

/**
 * A [DataOutput] that transparently interns every [writeUTF]'d string into the segment's constant pool: it
 * writes a varint pool-id in place of the string's inline bytes, so a value externalizer needs no change to
 * benefit from pooling. Every other write delegates verbatim to the underlying payload stream.
 */
private class PoolingDataOutput(private val d: DataOutputStream, private val intern: (String) -> Int) : DataOutput {
    override fun writeUTF(s: String) { d.writeVarLong(intern(s).toLong()) }
    override fun write(b: Int) = d.write(b)
    override fun write(b: ByteArray) = d.write(b)
    override fun write(b: ByteArray, off: Int, len: Int) = d.write(b, off, len)
    override fun writeBoolean(v: Boolean) = d.writeBoolean(v)
    override fun writeByte(v: Int) = d.writeByte(v)
    override fun writeShort(v: Int) = d.writeShort(v)
    override fun writeChar(v: Int) = d.writeChar(v)
    override fun writeInt(v: Int) = d.writeInt(v)
    override fun writeLong(v: Long) = d.writeLong(v)
    override fun writeFloat(v: Float) = d.writeFloat(v)
    override fun writeDouble(v: Double) = d.writeDouble(v)
    override fun writeBytes(s: String) = d.writeBytes(s)
    override fun writeChars(s: String) = d.writeChars(s)
}

/**
 * The read mirror of [PoolingDataOutput]: [readUTF] reads a varint pool-id and resolves it to the pooled
 * string via [deref]; every other read delegates to the payload stream. Reading past the framed payload
 * (a drifted/corrupt value) throws exactly as before, so the skip-and-continue path is preserved.
 */
private class PoolingDataInput(private val d: DataInputStream, private val deref: (Int) -> String) : DataInput {
    override fun readUTF(): String = deref(d.readVarLong().toInt())
    override fun readFully(b: ByteArray) = d.readFully(b)
    override fun readFully(b: ByteArray, off: Int, len: Int) = d.readFully(b, off, len)
    override fun skipBytes(n: Int): Int = d.skipBytes(n)
    override fun readBoolean(): Boolean = d.readBoolean()
    override fun readByte(): Byte = d.readByte()
    override fun readUnsignedByte(): Int = d.readUnsignedByte()
    override fun readShort(): Short = d.readShort()
    override fun readUnsignedShort(): Int = d.readUnsignedShort()
    override fun readChar(): Char = d.readChar()
    override fun readInt(): Int = d.readInt()
    override fun readLong(): Long = d.readLong()
    override fun readFloat(): Float = d.readFloat()
    override fun readDouble(): Double = d.readDouble()
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun readLine(): String? = d.readLine()
}

/**
 * An append-only byte sink that stays in memory until it exceeds [cap], then spills the remainder to a temp
 * file under [tmpDir] — so a small segment region never touches disk (fast path) while a large one (e.g.
 * android.jar's postings/names) stays bounded in RAM. [length] is exact after every write (there is no
 * buffering between the caller's `DataOutputStream` and this), so it doubles as the running region offset.
 * Single pass: write, then [copyTo] the final segment, then [close] (which deletes the temp file).
 */
private class SpillBuffer(private val cap: Int, private val tmpDir: Path) : OutputStream() {
    private var mem: ByteArrayOutputStream? = ByteArrayOutputStream()
    private var file: Path? = null
    private var fileOut: OutputStream? = null
    private var len = 0L

    override fun write(b: Int) { ensureRoom(1); target().write(b); len++ }
    override fun write(b: ByteArray, off: Int, l: Int) { ensureRoom(l); target().write(b, off, l); len += l }

    private fun ensureRoom(n: Int) {
        val m = mem ?: return
        if (m.size() + n <= cap) return
        val f = tmpDir.resolve("seg-region-${UUID.randomUUID()}.tmp")
        val out = BufferedOutputStream(Files.newOutputStream(f))
        m.writeTo(out)
        mem = null; file = f; fileOut = out
    }

    private fun target(): OutputStream = fileOut ?: mem!!
    fun length(): Long = len

    /** Append every written byte to [out], in order. Call once, before [close]. */
    fun copyTo(out: OutputStream) {
        val m = mem
        if (m != null) { m.writeTo(out); return }
        fileOut?.let { it.flush(); it.close(); fileOut = null }
        Files.newInputStream(file!!).use { it.copyTo(out) }
    }

    override fun close() {
        runCatching { fileOut?.close() }; fileOut = null
        file?.let { runCatching { Files.deleteIfExists(it) } }; file = null
        mem = null
    }
}

/**
 * An external merge sort: [add] items into an in-memory buffer; when it reaches [cap] items, sort and spill it
 * to a temp run file, then clear. [sortedIterator] returns every added item in [cmp] order — iterating the
 * in-memory buffer directly when nothing spilled (the small-artifact fast path), else k-way merging the runs.
 * Live memory is bounded to one [cap]-sized buffer regardless of total count. Single use; [close] deletes the
 * run files.
 */
private class Sorter<T>(
    private val cap: Int,
    private val tmpDir: Path,
    private val cmp: Comparator<T>,
    private val writeT: (DataOutputStream, T) -> Unit,
    private val readT: (DataInputStream) -> T,
) : Closeable {
    private var buf = ArrayList<T>()
    private val runs = ArrayList<Path>()
    private val open = ArrayList<DataInputStream>()

    fun add(t: T) { buf.add(t); if (buf.size >= cap) spill() }

    private fun spill() {
        if (buf.isEmpty()) return
        buf.sortWith(cmp)
        val run = tmpDir.resolve("seg-run-${UUID.randomUUID()}.tmp")
        DataOutputStream(BufferedOutputStream(Files.newOutputStream(run))).use { out ->
            out.writeVarLong(buf.size.toLong())
            for (t in buf) writeT(out, t)
        }
        runs.add(run)
        buf = ArrayList()
    }

    @Suppress("UNCHECKED_CAST")
    fun sortedIterator(): Iterator<T> {
        if (runs.isEmpty()) { buf.sortWith(cmp); return buf.iterator() }
        spill() // flush the tail buffer as a final run so all data is on disk and read uniformly
        val readers = runs.map { DataInputStream(BufferedInputStream(Files.newInputStream(it))).also { r -> open.add(r) } }
        val remaining = LongArray(readers.size) { readers[it].readVarLong() }
        val heads = arrayOfNulls<Any?>(readers.size)
        val pq = PriorityQueue<Int>(maxOf(1, readers.size)) { a, b -> cmp.compare(heads[a] as T, heads[b] as T) }
        for (i in readers.indices) if (remaining[i] > 0) { heads[i] = readT(readers[i]); remaining[i]--; pq.add(i) }
        return object : Iterator<T> {
            override fun hasNext() = pq.isNotEmpty()
            override fun next(): T {
                val i = pq.poll()
                val v = heads[i] as T
                if (remaining[i] > 0) { heads[i] = readT(readers[i]); remaining[i]--; pq.add(i) } else heads[i] = null
                return v
            }
        }
    }

    override fun close() {
        open.forEach { runCatching { it.close() } }; open.clear()
        runs.forEach { runCatching { Files.deleteIfExists(it) } }; runs.clear()
        buf = ArrayList()
    }
}

/**
 * Streams a [Segment] to disk without ever holding the whole artifact's entries (or its built regions) in
 * RAM — the memory-bounded counterpart to [Segment.write], and the path the index engine uses so a large
 * artifact (android.jar buffered ~95k entries × every extension before a byte was written) no longer drives
 * the build-time heap peak. [add] serializes each value immediately and feeds it to an external [Sorter];
 * [finish] merges the sorted entries, writing the postings/names (and, for a fuzzy index, the trigram)
 * regions through spill-aware [SpillBuffer]s, then concatenates them with the footer.
 *
 * The on-disk byte layout is identical to the old in-memory builder (terms in `String` order; values in
 * insertion order within a term, preserved by the monotonic [Rec.seq]; grams in order with ascending
 * name-offset deltas), so readers are unchanged and the deterministic-bytes / last-writer-wins atomic move is
 * preserved. Locked in by `SegmentWriterTest` (a spilling build equals a non-spilling build byte-for-byte).
 */
internal class SegmentWriter(
    private val file: Path,
    private val ext: IndexExtension<*, *>,
    private val maxBufferedEntries: Int = 50_000,
    private val maxBufferedTrigrams: Int = 200_000,
    private val regionSpillBytes: Int = 8 * 1024 * 1024,
) : Closeable {
    private val fuzzy = ext.matching == MatchingMode.PREFIX_AND_FUZZY
    @Suppress("UNCHECKED_CAST")
    private val ser = ext.valueExternalizer as Externalizer<Any>
    private val tmpDir: Path = file.parent
    private var seq = 0L
    private var added = 0
    // Origin is a per-artifact property, so every entry of a real segment shares one; track that so [finish]
    // can fold it into a single footer byte instead of repeating it on every posting (a mixed corpus — only
    // tests today — flips [uniformOrigin] off and keeps the per-posting byte).
    private var firstOrigin = -1
    private var uniformOrigin = true

    // The string constant pool, built as values are serialized: [poolIds] assigns each distinct string a
    // first-seen id and [poolStrings] holds them in id order. Bounded by the DISTINCT-string count (far below
    // the entry count), so it stays small even for a big artifact; written to disk once by [finish].
    private val poolIds = HashMap<String, Int>()
    private val poolStrings = ArrayList<String>()

    private fun intern(s: String): Int = poolIds.getOrPut(s) { poolStrings.size.also { poolStrings.add(s) } }

    /** One indexed entry, pre-serialized; [seq] preserves insertion order within equal terms (a stable sort). */
    private class Rec(val term: String, val seq: Long, val origin: Int, val value: ByteArray)
    /** A (trigram, name-offset) pairing; sorting by (gram, nameRel) reproduces the gram-sorted, ascending-offset
     *  trigram postings the in-memory builder produced. */
    private class Tri(val gram: String, val nameRel: Long)

    private val entries: Sorter<Rec> = run {
        Files.createDirectories(tmpDir)
        Sorter(
            maxBufferedEntries, tmpDir,
            compareBy({ it.term }, { it.seq }),
            { out, r -> writeBytes(out, r.term.toByteArray(Charsets.UTF_8)); out.writeVarLong(r.seq); out.writeByte(r.origin); writeBytes(out, r.value) },
            { din -> Rec(String(readBytes(din), Charsets.UTF_8), din.readVarLong(), din.readUnsignedByte(), readBytes(din)) },
        )
    }

    /** The number of entries added — the per-artifact entry count for the index.perf probe. */
    val count: Int get() = added

    fun add(term: String, value: Any, origin: IndexOrigin) {
        val o = origin.ordinal
        if (firstOrigin == -1) firstOrigin = o else if (o != firstOrigin) uniformOrigin = false
        // Serialize through [PoolingDataOutput] so each string field becomes a varint pool-id (deduped) rather
        // than inline bytes; the id is stable (first-seen) and identical whether or not the build spills.
        val vb = ByteArrayOutputStream().also { bos ->
            DataOutputStream(bos).use { dos -> ser.write(PoolingDataOutput(dos) { s -> intern(s) }, value) }
        }.toByteArray()
        entries.add(Rec(term, seq++, o, vb))
        added++
    }

    fun finish() {
        val postings = SpillBuffer(regionSpillBytes, tmpDir); val pOut = DataOutputStream(postings)
        val names = SpillBuffer(regionSpillBytes, tmpDir); val nOut = DataOutputStream(names)
        val tgNames = SpillBuffer(regionSpillBytes, tmpDir); val tnOut = DataOutputStream(tgNames)
        val tgPostings = SpillBuffer(regionSpillBytes, tmpDir); val tpOut = DataOutputStream(tgPostings)
        val tri = if (fuzzy) Sorter<Tri>(
            maxBufferedTrigrams, tmpDir,
            compareBy({ it.gram }, { it.nameRel }),
            { out, t -> writeBytes(out, t.gram.toByteArray(Charsets.UTF_8)); out.writeVarLong(t.nameRel) },
            { din -> Tri(String(readBytes(din), Charsets.UTF_8), din.readVarLong()) },
        ) else null
        val sparseTerms = ArrayList<String>(); val sparseTermOff = ArrayList<Long>()
        val sparseGrams = ArrayList<String>(); val sparseGramOff = ArrayList<Long>()
        var numTerms = 0
        // Terms are emitted in sorted order, so the first is the segment's min term and the last its max.
        var minTerm: String? = null
        var maxTerm = ""
        try {
            // Pass 1: merge entries in (term, seq) order → postings + names regions, emitting trigram tuples.
            val it = entries.sortedIterator()
            var head: Rec? = if (it.hasNext()) it.next() else null
            while (head != null) {
                val term = head.term
                if (minTerm == null) minTerm = term
                maxTerm = term
                val postingsRel = postings.length()
                val group = ArrayList<Rec>()
                while (head != null && head.term == term) { group.add(head); head = if (it.hasNext()) it.next() else null }
                pOut.writeVarLong(group.size.toLong())
                // Omit the per-posting origin byte for a uniform segment (the common case); it lives in the footer.
                for (r in group) {
                    if (!uniformOrigin) pOut.writeByte(r.origin)
                    pOut.writeVarLong(r.value.size.toLong()); pOut.write(r.value)
                }

                val nameRel = names.length()
                val tb = term.toByteArray(Charsets.UTF_8)
                nOut.writeVarLong(tb.size.toLong()); nOut.write(tb); nOut.writeVarLong(postingsRel)

                if (numTerms % Segment.SPARSE_INTERVAL == 0) { sparseTerms.add(term); sparseTermOff.add(nameRel) }
                if (tri != null) for (g in HashSet(Scoring.trigramsOf(term.lowercase()))) tri.add(Tri(g, nameRel))
                numTerms++
            }
            pOut.flush(); nOut.flush()

            // Pass 2: merge trigram tuples in (gram, nameRel) order → trigram names + postings (delta-encoded).
            if (tri != null) {
                val tit = tri.sortedIterator()
                var th: Tri? = if (tit.hasNext()) tit.next() else null
                var gi = 0
                while (th != null) {
                    val gram = th.gram
                    val tgPostingsRel = tgPostings.length()
                    val rels = ArrayList<Long>()
                    while (th != null && th.gram == gram) { rels.add(th.nameRel); th = if (tit.hasNext()) tit.next() else null }
                    tpOut.writeVarLong(rels.size.toLong())
                    var prev = 0L; for (nr in rels) { tpOut.writeVarLong(nr - prev); prev = nr }

                    val gb = gram.toByteArray(Charsets.UTF_8)
                    val tgNameRel = tgNames.length()
                    tnOut.writeVarLong(gb.size.toLong()); tnOut.write(gb); tnOut.writeVarLong(tgPostingsRel)
                    if (gi % Segment.SPARSE_INTERVAL == 0) { sparseGrams.add(gram); sparseGramOff.add(tgNameRel) }
                    gi++
                }
                tpOut.flush(); tnOut.flush()
            }

            // Assemble: concatenate the four regions then the footer into a unique temp, atomic-move into place.
            // Deterministic bytes ⇒ two concurrent writers of the same content-addressed segment can't corrupt
            // each other (last-writer-wins is a no-op overwrite).
            val tmp = file.resolveSibling("${file.fileName}.${UUID.randomUUID()}.tmp")
            try {
                DataOutputStream(BufferedOutputStream(Files.newOutputStream(tmp))).use { out ->
                    var pos = 0L
                    val postingsBase = pos; postings.copyTo(out); pos += postings.length()
                    val namesBase = pos; names.copyTo(out); pos += names.length()
                    val tgNamesBase = pos; tgNames.copyTo(out); pos += tgNames.length()
                    val tgPostingsBase = pos; tgPostings.copyTo(out); pos += tgPostings.length()

                    // Pool: the distinct strings (length-framed, in id order) followed by a fixed-width u32 table
                    // mapping id → its offset within the strings region. The strings are deduped, so this buffers
                    // far less than the inline copies it replaces; the table is numStrings × 4 bytes.
                    val poolStringsBase = pos
                    val poolOffsets = IntArray(poolStrings.size)
                    val poolBuf = ByteArrayOutputStream()
                    DataOutputStream(poolBuf).use { pd ->
                        for (i in poolStrings.indices) {
                            poolOffsets[i] = poolBuf.size()
                            val sb = poolStrings[i].toByteArray(Charsets.UTF_8)
                            pd.writeVarLong(sb.size.toLong()); pd.write(sb)
                        }
                    }
                    val poolBytes = poolBuf.toByteArray()
                    out.write(poolBytes); pos += poolBytes.size
                    // Table entries are the fewest bytes that hold any offset into the strings region — 1 byte
                    // for a <256B pool, up to 4 — so the table doesn't pay a flat u32 on a small segment.
                    val poolTableWidth = poolOffsetWidth(poolBytes.size)
                    val poolTableBase = pos
                    for (o in poolOffsets) out.writeFixedUInt(o, poolTableWidth)
                    pos += poolOffsets.size.toLong() * poolTableWidth

                    val footerStart = pos

                    out.writeVarLong(sparseTerms.size.toLong())
                    for (i in sparseTerms.indices) {
                        val sb = sparseTerms[i].toByteArray(Charsets.UTF_8)
                        out.writeVarLong(sb.size.toLong()); out.write(sb); out.writeVarLong(sparseTermOff[i])
                    }
                    out.writeByte(if (fuzzy) 1 else 0)
                    if (fuzzy) {
                        out.writeVarLong(sparseGrams.size.toLong())
                        for (i in sparseGrams.indices) {
                            val gb = sparseGrams[i].toByteArray(Charsets.UTF_8)
                            out.writeVarLong(gb.size.toLong()); out.write(gb); out.writeVarLong(sparseGramOff[i])
                        }
                    }
                    out.writeInt(ext.version)
                    out.writeVarLong(numTerms.toLong())
                    out.writeByte(if (uniformOrigin) 1 else 0)
                    out.writeByte(if (uniformOrigin) firstOrigin.coerceAtLeast(0) else 0)
                    val minB = (minTerm ?: "").toByteArray(Charsets.UTF_8)
                    out.writeVarLong(minB.size.toLong()); out.write(minB)
                    val maxB = maxTerm.toByteArray(Charsets.UTF_8)
                    out.writeVarLong(maxB.size.toLong()); out.write(maxB)
                    out.writeVarLong(poolStrings.size.toLong())
                    out.writeByte(poolTableWidth)
                    out.writeVarLong(poolStringsBase); out.writeVarLong(poolTableBase)
                    out.writeVarLong(postingsBase); out.writeVarLong(postings.length())
                    out.writeVarLong(namesBase); out.writeVarLong(names.length())
                    out.writeVarLong(tgNamesBase); out.writeVarLong(tgNames.length())
                    out.writeVarLong(tgPostingsBase); out.writeVarLong(tgPostings.length())
                    out.writeInt(Segment.MAGIC)
                    out.writeLong(footerStart)
                }
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } finally {
                Files.deleteIfExists(tmp)
            }
        } finally {
            postings.close(); names.close(); tgNames.close(); tgPostings.close()
            tri?.close(); entries.close()
        }
    }

    override fun close() { runCatching { entries.close() } }

    private companion object {
        fun writeBytes(out: DataOutputStream, b: ByteArray) { out.writeVarLong(b.size.toLong()); out.write(b) }
        fun readBytes(din: DataInputStream): ByteArray { val n = din.readVarLong().toInt(); val b = ByteArray(n); din.readFully(b); return b }
    }
}
