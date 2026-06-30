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
import java.io.DataInputStream
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
 *   [postings region]        per term: varint count, then count × { originByte, varint len, value bytes }
 *   [names region]           sorted; per term: varint termLen, term bytes, varint postingsRel
 *   [trigram names region]   sorted; per gram: varint gramLen, gram bytes, varint tgPostingsRel   (fuzzy only)
 *   [trigram postings region] per gram: varint count, then count × varint(delta of nameRel)        (fuzzy only)
 *   [footer]                 sparse term index + (sparse trigram index) + region bases/lens + magic
 *   [last 8 bytes]           footer start offset (big-endian long)
 * ```
 * Trigram postings store *name* offsets (monotonic, so delta+varint is compact): a fuzzy candidate
 * resolves straight to a name entry without any resident term→offset table.
 */
internal class Segment private constructor(
    private val cache: BlockCache,
    private val segId: Int,
    private val externalizer: Externalizer<Any>,
    private val fuzzyEnabled: Boolean,
    private val numTerms: Int,
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

    // ---- queries (mirror IndexData's semantics so ranking is identical) ----

    /** Append every value stored under [key] exactly. */
    fun exact(key: String, out: MutableList<Any>) {
        if (numTerms == 0) return
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

    /** Append up to [cap] fuzzy/substring hits, scored as [Scoring.scoreFuzzy]. */
    fun fuzzy(pattern: String, out: MutableList<Hit<Any>>, cap: Int) {
        if (numTerms == 0) return
        if (!fuzzyEnabled || pattern.length < 3) { prefix(pattern, out, cap); return }
        val grams = Scoring.trigramsOf(pattern.lowercase())
        if (grams.isEmpty()) return

        // Each gram's posting list is the ascending name offsets that contain it; intersect them. A gram
        // absent from the corpus is SKIPPED (not treated as an empty intersection) — same as IndexData, so a
        // pattern with a never-seen trigram still yields candidates via its other grams + the scorer.
        var candidates: LongArray? = null
        for (g in grams) {
            val list = trigramPostings(g) ?: continue
            candidates = if (candidates == null) list else intersectSorted(candidates, list)
            if (candidates.isEmpty()) return
        }
        val names = candidates ?: return // every gram absent ⇒ no candidates

        for (nameRel in names) {
            val cur = Cursor(namesBase + nameRel)
            val term = cur.readString()
            val postingsRel = cur.readVarLong()
            readPostings(postingsRel) { v, origin ->
                val s = Scoring.scoreFuzzy(term, pattern, origin)
                if (s > 0) {
                    out.add(Hit(term, v, s))
                    if (out.size >= cap) return
                }
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
            val origin = IndexOrigin.entries[cur.readByte()]
            val value = DataInputStream(ByteArrayInputStream(cur.readBytes(cur.readVarLong().toInt()))).use {
                externalizer.read(it)
            }
            emit(value, origin)
        }
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
        internal const val MAGIC = 0x49445831 // "IDX1"
        const val SPARSE_INTERVAL = 64

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
                    fuzzy, numTerms, postingsBase, namesBase, namesLen,
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
        val vb = ByteArrayOutputStream().also { bos -> DataOutputStream(bos).use { ser.write(it, value) } }.toByteArray()
        entries.add(Rec(term, seq++, origin.ordinal, vb))
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
        try {
            // Pass 1: merge entries in (term, seq) order → postings + names regions, emitting trigram tuples.
            val it = entries.sortedIterator()
            var head: Rec? = if (it.hasNext()) it.next() else null
            while (head != null) {
                val term = head.term
                val postingsRel = postings.length()
                val group = ArrayList<Rec>()
                while (head != null && head.term == term) { group.add(head); head = if (it.hasNext()) it.next() else null }
                pOut.writeVarLong(group.size.toLong())
                for (r in group) { pOut.writeByte(r.origin); pOut.writeVarLong(r.value.size.toLong()); pOut.write(r.value) }

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
