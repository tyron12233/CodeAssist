package dev.ide.index.impl

import dev.ide.index.Externalizer
import dev.ide.index.IndexExtension
import dev.ide.index.IndexOrigin
import dev.ide.index.MatchingMode
import dev.ide.platform.DataReader
import dev.ide.platform.DataWriter
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.PriorityQueue
import java.util.UUID

/**
 * Building a segment, which is the half of the format that has not crossed to common code.
 *
 * Reading one has: [Segment] needs positioned reads and a byte decoder, and both exist on every platform.
 * Writing one needs an external merge sort that spills its runs to temp files, and that needs streaming file
 * output, which the portable file seam does not have. Splitting the file here is what let the query path off
 * the JVM without waiting for the build path.
 */

/**
 * Open an existing segment named by a [Path], for the JVM callers that already hold one.
 *
 * The member takes a path string, because that is what the portable file seam takes. This keeps every
 * existing call site spelled the way it was.
 */
internal fun Segment.Companion.open(file: Path, ext: IndexExtension<*, *>, cache: BlockCache, segId: Int): Segment =
    Segment.open(file.toString(), ext, cache, segId) { t ->
        LOG.warn("an index segment has unreadable value(s) (stale/corrupt payload) — skipping; a re-index will rebuild it", t)
    }

/**
 * Build a segment file from a full [entries] list.
 *
 * A convenience over [SegmentWriter] for callers that already hold every entry in memory (tests, small
 * whole-list builds); the engine streams via [SegmentWriter.add] instead, so a large artifact never buffers
 * all of its entries at once.
 */
internal fun Segment.Companion.write(file: Path, ext: IndexExtension<*, *>, entries: List<IndexEntry>) {
    SegmentWriter(file, ext).use { w ->
        for (e in entries) w.add(e.term, e.value, e.origin)
        w.finish()
    }
}

private val LOG = dev.ide.platform.log.Log.logger("index")

/**
 * The varint decode again, against a stream.
 *
 * A second copy of eight lines, deliberately: the common one is on [DataReader], and reaching it from here
 * means either a wrapper allocated per call on the k-way merge's hot path, or reading payload bytes one
 * interface call at a time. The run files this reads are written by [DataOutputStream.writeVarLong] directly
 * above, so the two that have to agree sit together.
 */
private fun DataInputStream.readVarLong(): Long {
    var shift = 0
    var result = 0L
    while (true) {
        val b = readUnsignedByte()
        result = result or ((b.toLong() and 0x7F) shl shift)
        if (b < 0x80) return result
        shift += 7
    }
}

/** The runtime's own errors, which a value-payload guard must never swallow. */
internal actual fun rethrowIfFatal(t: Throwable) {
    if (t is VirtualMachineError) throw t
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

/**
 * A [DataWriter] that transparently interns every [writeUTF]'d string into the segment's constant pool: it
 * writes a varint pool-id in place of the string's inline bytes, so a value externalizer needs no change to
 * benefit from pooling. Every other write delegates verbatim to the underlying payload stream.
 */
private class PoolingDataOutput(private val d: DataOutputStream, private val intern: (String) -> Int) : DataWriter {
    override fun writeUTF(s: String) { d.writeVarLong(intern(s).toLong()) }
    override fun writeBoolean(v: Boolean) = d.writeBoolean(v)
    override fun writeByte(v: Int) = d.writeByte(v)
    override fun writeShort(v: Int) = d.writeShort(v)
    override fun writeInt(v: Int) = d.writeInt(v)
    override fun writeLong(v: Long) = d.writeLong(v)
}

/**
 * The plain adapters, for the paths that do NOT pool: the source-entry cache writes and reads values
 * straight onto a stream. Same six operations, no interning.
 */
internal class StreamDataWriter(private val d: DataOutputStream) : DataWriter {
    override fun writeUTF(value: String) = d.writeUTF(value)
    override fun writeBoolean(value: Boolean) = d.writeBoolean(value)
    override fun writeByte(value: Int) = d.writeByte(value)
    override fun writeShort(value: Int) = d.writeShort(value)
    override fun writeInt(value: Int) = d.writeInt(value)
    override fun writeLong(value: Long) = d.writeLong(value)
}

internal class StreamDataReader(private val d: DataInputStream) : DataReader {
    override fun readUTF(): String = d.readUTF()
    override fun readBoolean(): Boolean = d.readBoolean()
    override fun readByte(): Int = d.readByte().toInt()
    override fun readUnsignedByte(): Int = d.readUnsignedByte()
    override fun readShort(): Int = d.readShort().toInt()
    override fun readInt(): Int = d.readInt()
    override fun readLong(): Long = d.readLong()
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
