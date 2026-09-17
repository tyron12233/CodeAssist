package dev.ide.index.impl

import dev.ide.index.Externalizer
import dev.ide.index.IndexExtension
import dev.ide.index.IndexOrigin
import dev.ide.index.MatchingMode
import dev.ide.kotlin.classfile.createDirectories
import dev.ide.kotlin.classfile.deleteFile
import dev.ide.kotlin.classfile.moveFile
import dev.ide.kotlin.classfile.openFile
import dev.ide.kotlin.classfile.openFileForWrite
import dev.ide.platform.DataWriter

/**
 * Build a segment file from a full [entries] list.
 *
 * A convenience over [SegmentWriter] for callers that already hold every entry in memory (tests, small
 * whole-list builds); the engine streams via [SegmentWriter.add] instead, so a large artifact never buffers
 * all of its entries at once.
 */
internal fun writeSegment(file: String, ext: IndexExtension<*, *>, entries: List<IndexEntry>) {
    SegmentWriter(file, ext).use { w ->
        for (e in entries) w.add(e.term, e.value, e.origin)
        w.finish()
    }
}

/**
 * A [DataWriter] that transparently interns every [writeUTF]'d string into the segment's constant pool: it
 * writes a varint pool-id in place of the string's inline bytes, so a value externalizer needs no change to
 * benefit from pooling. Every other write delegates verbatim to the underlying payload stream.
 */
private class PoolingDataOutput(private val d: DataWriter, private val intern: (String) -> Int) : DataWriter {
    override fun writeUTF(s: String) { d.writeVarLong(intern(s).toLong()) }
    override fun writeBoolean(v: Boolean) = d.writeBoolean(v)
    override fun writeByte(v: Int) = d.writeByte(v)
    override fun writeShort(v: Int) = d.writeShort(v)
    override fun writeInt(v: Int) = d.writeInt(v)
    override fun writeLong(v: Long) = d.writeLong(v)
}

/**
 * An append-only byte sink that stays in memory until it exceeds [cap], then spills the remainder to a temp
 * file under [tmpDir] — so a small segment region never touches disk (fast path) while a large one (e.g.
 * android.jar's postings/names) stays bounded in RAM. [length] is exact after every write (there is no
 * buffering between the caller's `DataOutputStream` and this), so it doubles as the running region offset.
 * Single pass: write, then [copyTo] the final segment, then [close] (which deletes the temp file).
 */
private class SpillBuffer(private val cap: Int, private val tmpDir: String) : SegmentOut {
    private var mem: MemorySegmentOut? = MemorySegmentOut()
    private var file: String? = null
    private var fileOut: SinkSegmentOut? = null

    // Exact after every write, because it is the region offset the footer will point at: a spill carries the
    // bytes already buffered into the sink writer, so one side or the other holds all of them, never both.
    override val length: Long get() = fileOut?.length ?: mem?.length ?: 0L

    // Set when a spill could not open its file; the region stays in memory rather than retrying per write.
    private var cannotSpill = false

    override fun writeByte(value: Int) = withRoom(1) { it.writeByte(value) }
    override fun writeBoolean(value: Boolean) = withRoom(1) { it.writeBoolean(value) }
    override fun writeShort(value: Int) = withRoom(2) { it.writeShort(value) }
    override fun writeInt(value: Int) = withRoom(4) { it.writeInt(value) }
    override fun writeLong(value: Long) = withRoom(8) { it.writeLong(value) }
    override fun writeUTF(value: String) = withRoom(2 + value.length * 3) { it.writeUTF(value) }
    override fun writeBytes(value: ByteArray) = withRoom(value.size) { it.writeBytes(value) }

    private inline fun withRoom(n: Int, write: (SegmentOut) -> Unit) {
        val m = mem
        if (m != null && !cannotSpill && m.length + n > cap) spill(m)
        write(fileOut ?: mem!!)
    }

    private fun spill(m: MemorySegmentOut) {
        val f = "$tmpDir/${temporaryName("seg-region")}.tmp"
        val sink = openFileForWrite(f)
        if (sink == null) {
            // Nowhere to spill: keep going in memory rather than lose the region, and stop trying on every
            // subsequent write.
            cannotSpill = true
            return
        }
        val out = SinkSegmentOut(sink)
        out.writeBytes(m.toByteArray())
        mem = null
        file = f
        fileOut = out
    }

    /** Append every written byte to [out], in order. Call once, before [close]. */
    fun copyTo(out: SegmentOut) {
        val m = mem
        if (m != null) {
            out.writeBytes(m.toByteArray())
            return
        }
        fileOut?.flush()
        val source = openFile(file!!) ?: return
        try {
            var at = 0L
            while (at < source.size) {
                val take = minOf(COPY_CHUNK.toLong(), source.size - at).toInt()
                out.writeBytes(source.read(at, take))
                at += take
            }
        } finally {
            source.close()
        }
    }

    fun close() {
        runCatching { fileOut?.close() }; fileOut = null
        file?.let { runCatching { deleteFile(it) } }; file = null
        mem = null
    }

    private companion object {
        const val COPY_CHUNK = 256 * 1024
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
    private val tmpDir: String,
    private val cmp: Comparator<T>,
    private val writeT: (SegmentOut, T) -> Unit,
    private val readT: (SegmentIn) -> T,
) : AutoCloseable {
    private var buf = ArrayList<T>()
    private val runs = ArrayList<String>()
    private val open = ArrayList<SourceSegmentIn>()

    fun add(t: T) { buf.add(t); if (buf.size >= cap) spill() }

    private fun spill() {
        if (buf.isEmpty()) return
        buf.sortWith(cmp)
        val run = "$tmpDir/${temporaryName("seg-run")}.tmp"
        val sink = openFileForWrite(run) ?: return
        val out = SinkSegmentOut(sink)
        out.writeVarLong(buf.size.toLong())
        for (t in buf) writeT(out, t)
        out.close()
        runs.add(run)
        buf = ArrayList()
    }

    @Suppress("UNCHECKED_CAST")
    fun sortedIterator(): Iterator<T> {
        if (runs.isEmpty()) { buf.sortWith(cmp); return buf.iterator() }
        spill() // flush the tail buffer as a final run so all data is on disk and read uniformly
        val readers = runs.mapNotNull { openFile(it) }.map { SourceSegmentIn(it).also { r -> open.add(r) } }
        val remaining = LongArray(readers.size) { readers[it].readVarLong() }
        val heads = arrayOfNulls<Any?>(readers.size)
        val heap = IntHeap { a, b -> cmp.compare(heads[a] as T, heads[b] as T) < 0 }
        for (i in readers.indices) if (remaining[i] > 0) { heads[i] = readT(readers[i]); remaining[i]--; heap.add(i) }
        return object : Iterator<T> {
            override fun hasNext() = !heap.isEmpty
            override fun next(): T {
                val i = heap.poll()
                val v = heads[i] as T
                if (remaining[i] > 0) { heads[i] = readT(readers[i]); remaining[i]--; heap.add(i) } else heads[i] = null
                return v
            }
        }
    }

    override fun close() {
        open.forEach { runCatching { it.close() } }; open.clear()
        runs.forEach { runCatching { deleteFile(it) } }; runs.clear()
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
    private val file: String,
    private val ext: IndexExtension<*, *>,
    private val maxBufferedEntries: Int = 50_000,
    private val maxBufferedTrigrams: Int = 200_000,
    private val regionSpillBytes: Int = 8 * 1024 * 1024,
) : AutoCloseable {
    private val fuzzy = ext.matching == MatchingMode.PREFIX_AND_FUZZY
    @Suppress("UNCHECKED_CAST")
    private val ser = ext.valueExternalizer as Externalizer<Any>
    private val tmpDir: String = file.substringBeforeLast('/', ".")
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
        createDirectories(tmpDir)
        Sorter(
            maxBufferedEntries, tmpDir,
            compareBy({ it.term }, { it.seq }),
            { out, r -> writeFramed(out, r.term.encodeToByteArray()); out.writeVarLong(r.seq); out.writeByte(r.origin); writeFramed(out, r.value) },
            { din -> Rec(readFramed(din).decodeToString(), din.readVarLong(), din.readUnsignedByte(), readFramed(din)) },
        )
    }

    /** The number of entries added — the per-artifact entry count for the index.perf probe. */
    val count: Int get() = added

    fun add(term: String, value: Any, origin: IndexOrigin) {
        val o = origin.ordinal
        if (firstOrigin == -1) firstOrigin = o else if (o != firstOrigin) uniformOrigin = false
        // Serialize through [PoolingDataOutput] so each string field becomes a varint pool-id (deduped) rather
        // than inline bytes; the id is stable (first-seen) and identical whether or not the build spills.
        val vb = MemorySegmentOut().also { out -> ser.write(PoolingDataOutput(out) { s -> intern(s) }, value) }.toByteArray()
        entries.add(Rec(term, seq++, o, vb))
        added++
    }

    fun finish() {
        val postings = SpillBuffer(regionSpillBytes, tmpDir); val pOut: SegmentOut = postings
        val names = SpillBuffer(regionSpillBytes, tmpDir); val nOut: SegmentOut = names
        val tgNames = SpillBuffer(regionSpillBytes, tmpDir); val tnOut: SegmentOut = tgNames
        val tgPostings = SpillBuffer(regionSpillBytes, tmpDir); val tpOut: SegmentOut = tgPostings
        val tri = if (fuzzy) Sorter<Tri>(
            maxBufferedTrigrams, tmpDir,
            compareBy({ it.gram }, { it.nameRel }),
            { out, t -> writeFramed(out, t.gram.encodeToByteArray()); out.writeVarLong(t.nameRel) },
            { din -> Tri(readFramed(din).decodeToString(), din.readVarLong()) },
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
                val postingsRel = postings.length
                val group = ArrayList<Rec>()
                while (head != null && head.term == term) { group.add(head); head = if (it.hasNext()) it.next() else null }
                pOut.writeVarLong(group.size.toLong())
                // Omit the per-posting origin byte for a uniform segment (the common case); it lives in the footer.
                for (r in group) {
                    if (!uniformOrigin) pOut.writeByte(r.origin)
                    pOut.writeVarLong(r.value.size.toLong()); pOut.writeBytes(r.value)
                }

                val nameRel = names.length
                val tb = term.encodeToByteArray()
                nOut.writeVarLong(tb.size.toLong()); nOut.writeBytes(tb); nOut.writeVarLong(postingsRel)

                if (numTerms % Segment.SPARSE_INTERVAL == 0) { sparseTerms.add(term); sparseTermOff.add(nameRel) }
                if (tri != null) for (g in HashSet(Scoring.trigramsOf(term.lowercase()))) tri.add(Tri(g, nameRel))
                numTerms++
            }
            // Pass 2: merge trigram tuples in (gram, nameRel) order → trigram names + postings (delta-encoded).
            if (tri != null) {
                val tit = tri.sortedIterator()
                var th: Tri? = if (tit.hasNext()) tit.next() else null
                var gi = 0
                while (th != null) {
                    val gram = th.gram
                    val tgPostingsRel = tgPostings.length
                    val rels = ArrayList<Long>()
                    while (th != null && th.gram == gram) { rels.add(th.nameRel); th = if (tit.hasNext()) tit.next() else null }
                    tpOut.writeVarLong(rels.size.toLong())
                    var prev = 0L; for (nr in rels) { tpOut.writeVarLong(nr - prev); prev = nr }

                    val gb = gram.encodeToByteArray()
                    val tgNameRel = tgNames.length
                    tnOut.writeVarLong(gb.size.toLong()); tnOut.writeBytes(gb); tnOut.writeVarLong(tgPostingsRel)
                    if (gi % Segment.SPARSE_INTERVAL == 0) { sparseGrams.add(gram); sparseGramOff.add(tgNameRel) }
                    gi++
                }
            }

            // Assemble: concatenate the four regions then the footer into a unique temp, atomic-move into place.
            // Deterministic bytes ⇒ two concurrent writers of the same content-addressed segment can't corrupt
            // each other (last-writer-wins is a no-op overwrite).
            val tmp = "$file.${temporaryName("build")}.tmp"
            try {
                val sink = openFileForWrite(tmp) ?: error("cannot write $tmp")
                SinkSegmentOut(sink).let { out ->
                    var pos = 0L
                    val postingsBase = pos; postings.copyTo(out); pos += postings.length
                    val namesBase = pos; names.copyTo(out); pos += names.length
                    val tgNamesBase = pos; tgNames.copyTo(out); pos += tgNames.length
                    val tgPostingsBase = pos; tgPostings.copyTo(out); pos += tgPostings.length

                    // Pool: the distinct strings (length-framed, in id order) followed by a fixed-width u32 table
                    // mapping id → its offset within the strings region. The strings are deduped, so this buffers
                    // far less than the inline copies it replaces; the table is numStrings × 4 bytes.
                    val poolStringsBase = pos
                    val poolOffsets = IntArray(poolStrings.size)
                    val poolBuf = MemorySegmentOut()
                    for (i in poolStrings.indices) {
                        poolOffsets[i] = poolBuf.length.toInt()
                        val sb = poolStrings[i].encodeToByteArray()
                        poolBuf.writeVarLong(sb.size.toLong()); poolBuf.writeBytes(sb)
                    }
                    val poolBytes = poolBuf.toByteArray()
                    out.writeBytes(poolBytes); pos += poolBytes.size
                    // Table entries are the fewest bytes that hold any offset into the strings region — 1 byte
                    // for a <256B pool, up to 4 — so the table doesn't pay a flat u32 on a small segment.
                    val poolTableWidth = poolOffsetWidth(poolBytes.size)
                    val poolTableBase = pos
                    for (o in poolOffsets) out.writeFixedUInt(o, poolTableWidth)
                    pos += poolOffsets.size.toLong() * poolTableWidth

                    val footerStart = pos

                    out.writeVarLong(sparseTerms.size.toLong())
                    for (i in sparseTerms.indices) {
                        val sb = sparseTerms[i].encodeToByteArray()
                        out.writeVarLong(sb.size.toLong()); out.writeBytes(sb); out.writeVarLong(sparseTermOff[i])
                    }
                    out.writeByte(if (fuzzy) 1 else 0)
                    if (fuzzy) {
                        out.writeVarLong(sparseGrams.size.toLong())
                        for (i in sparseGrams.indices) {
                            val gb = sparseGrams[i].encodeToByteArray()
                            out.writeVarLong(gb.size.toLong()); out.writeBytes(gb); out.writeVarLong(sparseGramOff[i])
                        }
                    }
                    out.writeInt(ext.version)
                    out.writeVarLong(numTerms.toLong())
                    out.writeByte(if (uniformOrigin) 1 else 0)
                    out.writeByte(if (uniformOrigin) firstOrigin.coerceAtLeast(0) else 0)
                    val minB = (minTerm ?: "").encodeToByteArray()
                    out.writeVarLong(minB.size.toLong()); out.writeBytes(minB)
                    val maxB = maxTerm.encodeToByteArray()
                    out.writeVarLong(maxB.size.toLong()); out.writeBytes(maxB)
                    out.writeVarLong(poolStrings.size.toLong())
                    out.writeByte(poolTableWidth)
                    out.writeVarLong(poolStringsBase); out.writeVarLong(poolTableBase)
                    out.writeVarLong(postingsBase); out.writeVarLong(postings.length)
                    out.writeVarLong(namesBase); out.writeVarLong(names.length)
                    out.writeVarLong(tgNamesBase); out.writeVarLong(tgNames.length)
                    out.writeVarLong(tgPostingsBase); out.writeVarLong(tgPostings.length)
                    out.writeInt(Segment.MAGIC)
                    out.writeLong(footerStart)
                    out.close()
                }
                check(moveFile(tmp, file)) { "cannot move $tmp into place" }
            } finally {
                deleteFile(tmp)
            }
        } finally {
            postings.close(); names.close(); tgNames.close(); tgPostings.close()
            tri?.close(); entries.close()
        }
    }

    override fun close() { runCatching { entries.close() } }

    private companion object {
        /** A length-framed blob, the shape every run-file field takes. */
        fun writeFramed(out: SegmentOut, b: ByteArray) { out.writeVarLong(b.size.toLong()); out.writeBytes(b) }
        fun readFramed(din: SegmentIn): ByteArray = din.readBytes(din.readVarLong().toInt())
    }
}
