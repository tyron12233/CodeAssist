package dev.ide.index.impl

import dev.ide.index.Externalizer
import dev.ide.index.Hit
import dev.ide.index.IndexExtension
import dev.ide.index.IndexOrigin
import dev.ide.index.MatchingMode
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.TreeMap

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
    // resident — the only heap the segment holds at rest:
    private val sparseTerms: Array<String>,
    private val sparseTermOff: LongArray,
    private val sparseGrams: Array<String>,
    private val sparseGramOff: LongArray,
) : Closeable {

    // ---- queries (mirror IndexData's semantics so ranking is identical) ----

    /** Append every value stored under [key] exactly. */
    fun exact(key: String, out: MutableList<Any>) {
        if (numTerms == 0) return
        val cur = Cursor(namesBase + sparseTermOff[floor(sparseTerms, key)])
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
        val cur = Cursor(namesBase + sparseTermOff[floor(sparseTerms, p)])
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
        val cur = Cursor(tgNamesBase + sparseGramOff[floor(sparseGrams, g)])
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

    companion object {
        private const val MAGIC = 0x49445831 // "IDX1"
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

                val nSparse = r.readVarLong().toInt()
                val sparseTerms = Array(nSparse) { "" }
                val sparseTermOff = LongArray(nSparse)
                for (i in 0 until nSparse) { sparseTerms[i] = r.readString(); sparseTermOff[i] = r.readVarLong() }

                val fuzzy = r.readByte() != 0
                var sparseGrams = emptyArray<String>()
                var sparseGramOff = LongArray(0)
                if (fuzzy) {
                    val ng = r.readVarLong().toInt()
                    sparseGrams = Array(ng) { "" }
                    sparseGramOff = LongArray(ng)
                    for (i in 0 until ng) { sparseGrams[i] = r.readString(); sparseGramOff[i] = r.readVarLong() }
                }

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
                    sparseTerms, sparseTermOff, sparseGrams, sparseGramOff,
                )
            }

        /** Build a segment file from [entries] (sorted + front-loaded into the immutable layout above). */
        @Suppress("UNCHECKED_CAST")
        fun write(file: Path, ext: IndexExtension<*, *>, entries: List<IndexEntry>) {
            val fuzzy = ext.matching == MatchingMode.PREFIX_AND_FUZZY
            val ser = ext.valueExternalizer as Externalizer<Any>

            // Group by term (sorted), serializing each value to bytes once.
            val byTerm = TreeMap<String, MutableList<Pair<ByteArray, IndexOrigin>>>()
            for (e in entries) {
                val vb = ByteArrayOutputStream().also { bos -> DataOutputStream(bos).use { ser.write(it, e.value) } }.toByteArray()
                byTerm.getOrPut(e.term) { ArrayList(1) }.add(vb to e.origin)
            }
            val numTerms = byTerm.size

            val postings = ByteArrayOutputStream(); val pOut = DataOutputStream(postings)
            val names = ByteArrayOutputStream(); val nOut = DataOutputStream(names)
            val termOrder = ArrayList<String>(numTerms)
            val nameRels = LongArray(numTerms)
            val sparseTerms = ArrayList<String>(); val sparseTermOff = ArrayList<Long>()
            var ti = 0
            for ((term, vals) in byTerm) {
                val postingsRel = postings.size().toLong()
                pOut.writeVarLong(vals.size.toLong())
                for ((vb, origin) in vals) { pOut.writeByte(origin.ordinal); pOut.writeVarLong(vb.size.toLong()); pOut.write(vb) }

                val nameRel = names.size().toLong()
                val tb = term.toByteArray(Charsets.UTF_8)
                nOut.writeVarLong(tb.size.toLong()); nOut.write(tb); nOut.writeVarLong(postingsRel)

                termOrder.add(term); nameRels[ti] = nameRel
                if (ti % SPARSE_INTERVAL == 0) { sparseTerms.add(term); sparseTermOff.add(nameRel) }
                ti++
            }
            pOut.flush(); nOut.flush()

            var tgNamesBytes = ByteArray(0); var tgPostingsBytes = ByteArray(0)
            val sparseGrams = ArrayList<String>(); val sparseGramOff = ArrayList<Long>()
            if (fuzzy) {
                // gram → ascending term indices (added in term order, so naturally sorted).
                val gramToTerms = TreeMap<String, MutableList<Int>>()
                for (t in 0 until numTerms) {
                    for (g in HashSet(Scoring.trigramsOf(termOrder[t].lowercase()))) {
                        gramToTerms.getOrPut(g) { ArrayList(1) }.add(t)
                    }
                }
                val tgPost = ByteArrayOutputStream(); val tpOut = DataOutputStream(tgPost)
                val tgNam = ByteArrayOutputStream(); val tnOut = DataOutputStream(tgNam)
                var gi = 0
                for ((gram, termIdxs) in gramToTerms) {
                    val tgPostingsRel = tgPost.size().toLong()
                    tpOut.writeVarLong(termIdxs.size.toLong())
                    var prev = 0L
                    for (t in termIdxs) { val nr = nameRels[t]; tpOut.writeVarLong(nr - prev); prev = nr }

                    val gb = gram.toByteArray(Charsets.UTF_8)
                    val tgNameRel = tgNam.size().toLong()
                    tnOut.writeVarLong(gb.size.toLong()); tnOut.write(gb); tnOut.writeVarLong(tgPostingsRel)
                    if (gi % SPARSE_INTERVAL == 0) { sparseGrams.add(gram); sparseGramOff.add(tgNameRel) }
                    gi++
                }
                tpOut.flush(); tnOut.flush()
                tgNamesBytes = tgNam.toByteArray(); tgPostingsBytes = tgPost.toByteArray()
            }

            val postingsBytes = postings.toByteArray(); val namesBytes = names.toByteArray()

            Files.createDirectories(file.parent)
            val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
            DataOutputStream(BufferedOutputStream(Files.newOutputStream(tmp))).use { out ->
                var pos = 0L
                val postingsBase = pos; out.write(postingsBytes); pos += postingsBytes.size
                val namesBase = pos; out.write(namesBytes); pos += namesBytes.size
                val tgNamesBase = pos; out.write(tgNamesBytes); pos += tgNamesBytes.size
                val tgPostingsBase = pos; out.write(tgPostingsBytes); pos += tgPostingsBytes.size
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
                out.writeVarLong(postingsBase); out.writeVarLong(postingsBytes.size.toLong())
                out.writeVarLong(namesBase); out.writeVarLong(namesBytes.size.toLong())
                out.writeVarLong(tgNamesBase); out.writeVarLong(tgNamesBytes.size.toLong())
                out.writeVarLong(tgPostingsBase); out.writeVarLong(tgPostingsBytes.size.toLong())
                out.writeInt(MAGIC)
                out.writeLong(footerStart)
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }

        /** Largest index `i` with `keys[i] <= key`, clamped to 0 (the array is sorted and non-empty here). */
        private fun floor(keys: Array<String>, key: String): Int {
            var lo = 0; var hi = keys.size - 1; var ans = 0
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (keys[mid] <= key) { ans = mid; lo = mid + 1 } else hi = mid - 1
            }
            return ans
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
