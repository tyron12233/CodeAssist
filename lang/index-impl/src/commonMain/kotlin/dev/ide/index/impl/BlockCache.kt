package dev.ide.index.impl

import dev.ide.platform.FileSource
import dev.ide.platform.Lock
import dev.ide.platform.openFile

/** Thrown when a block is asked for from a segment that has been closed or invalidated. */
internal class SegmentClosedException : RuntimeException("segment closed")

/**
 * A bounded, process-shared LRU of fixed-size file blocks — the only thing that bounds the indexing
 * subsystem's resident memory. [Segment]s read everything (term dictionary, postings, trigrams, values)
 * through this cache via positioned reads, so the heap holds at most [maxBlocks] blocks of hot data
 * **regardless of how large the on-disk indexes grow**. That is the "reads from disk, not RAM" property: a
 * 9 MB members partition costs a handful of cached blocks, not 9 MB of heap.
 *
 * Blocks are keyed by `(segId, blockIndex)`; [evictSegment] drops a closed/invalidated segment's blocks.
 * Reads are at an absolute offset and happen OUTSIDE the lock — the lock only guards the map — so a slow
 * disk read never stalls another query and concurrent readers never disturb each other's position.
 *
 * The cache ALSO owns the segments' open file handles, as a second bounded LRU ([maxOpenHandles]). A segment
 * used to hold its handle open for its whole life; with one segment per artifact per index extension, a
 * large (e.g. Compose) classpath opened hundreds at once and exhausted the process file-descriptor limit
 * (~1024 on Android), surfacing as "Too many open files" on the next unrelated `open()`. Handles are now
 * opened lazily on a block miss and the least-recently-used one is closed when over the cap; an evicted
 * segment is simply reopened on its next miss (hits never touch a handle). Segments register their file via
 * [registerSegment] and never see a handle directly.
 */
internal class BlockCache(
    maxBytes: Long,
    val blockSize: Int = 4096,
    private val maxOpenHandles: Int = DEFAULT_MAX_OPEN_HANDLES,
) {

    private val maxBlocks = maxOf(1, (maxBytes / blockSize).toInt())
    private val lock = Lock()

    /**
     * Cumulative count of physical block reads (cache misses that reached disk).
     *
     * Test instrumentation for the segment-skip optimization: a query the segment can prove empty from its
     * resident term range must add zero. Guarded by [countLock] rather than an atomic so the whole file stays
     * in common code; it is touched once per disk read, which is several orders of magnitude slower.
     */
    private val countLock = Lock()
    private var reads = 0L
    val blockReads: Long get() = countLock.withLock { reads }

    private val blocks = LruMap<Long, ByteArray>(maxBlocks)

    private val handleLock = Lock()
    private val segFiles = HashMap<Int, String>()
    private val openHandles = LruMap<Int, FileSource>(maxOpenHandles) { _, source -> runCatching { source.close() } }

    private fun keyOf(segId: Int, blockIndex: Long): Long = (segId.toLong() shl 40) or blockIndex

    /**
     * A small direct-mapped front for [blocks], read without the lock. An index query touches a block for
     * nearly every term and value it reads, and going through the LRU on each one cost a lock-lambda and a
     * boxed `Long` key per lookup: a third of the editor's garbage while typing (a device profile put the
     * index at ~190 MB per 30 s of typing). A hit here allocates nothing.
     *
     * Each slot holds an immutable [Hot] (key and block together), so a racing reader sees one whole entry or
     * another, never a key from one and a block from another. A stale slot is harmless: blocks never change
     * (segments are immutable and segment ids are never reused), and [evictSegment] clears a closed
     * segment's slots so they do not pin its memory. It can keep up to [HOT_SLOTS] blocks alive beyond the
     * LRU's budget.
     */
    private class Hot(val key: Long, val block: ByteArray)
    private val hot = arrayOfNulls<Hot>(HOT_SLOTS)

    private fun hotSlot(key: Long): Int = ((key * -7046029254386353131L) ushr (64 - HOT_BITS)).toInt()

    /** Decoded pool strings ([Segment]'s string constant pool), direct-mapped like [hot] and keyed by
     *  `(segId, poolId)`. A pooled string (an owner FQN, a `kind`) is read again for every value that names
     *  it, and decoding it each time was the index's largest source of garbage. */
    private class PooledString(val key: Long, val value: String)
    private val strings = arrayOfNulls<PooledString>(STRING_SLOTS)

    private fun stringSlot(key: Long): Int = ((key * -7046029254386353131L) ushr (64 - STRING_BITS)).toInt()

    /** The pooled string [id] of [segId], decoding it with [decode] only when it is not cached. */
    inline fun pooledString(segId: Int, id: Int, decode: () -> String): String {
        val key = (segId.toLong() shl 32) or (id.toLong() and 0xFFFFFFFFL)
        val slot = stringSlotOf(key)
        cachedString(slot, key)?.let { return it }
        val value = decode()
        putString(slot, key, value)
        return value
    }

    @PublishedApi internal fun stringSlotOf(key: Long): Int = stringSlot(key)
    @PublishedApi internal fun cachedString(slot: Int, key: Long): String? = strings[slot]?.takeIf { it.key == key }?.value
    @PublishedApi internal fun putString(slot: Int, key: Long, value: String) { strings[slot] = PooledString(key, value) }

    /** Record the file backing [segId] so its handle can be (re)opened on demand. */
    fun registerSegment(segId: Int, file: String) {
        handleLock.withLock { segFiles[segId] = file }
    }

    /**
     * The open handle for [segId] from the bounded LRU, opening it if absent.
     *
     * Null only if the segment was closed/invalidated (its file deregistered).
     */
    private fun handleFor(segId: Int): FileSource? = handleLock.withLock {
        openHandles[segId]?.let { return@withLock it }
        val file = segFiles[segId] ?: return@withLock null
        val source = openFile(file) ?: return@withLock null
        openHandles[segId] = source // may evict + close the least-recently-used handle
        source
    }

    /** The block (<= [blockSize] bytes; shorter only for the final block) containing the byte at [fileOffset]. */
    fun block(segId: Int, fileOffset: Long): ByteArray {
        val blockIndex = fileOffset / blockSize
        val key = keyOf(segId, blockIndex)
        val slot = hotSlot(key)
        hot[slot]?.let { if (it.key == key) return it.block }
        lock.withLock { blocks[key] }?.let { hot[slot] = Hot(key, it); return it }

        // Miss: read off-lock so a slow disk read never stalls other queries.
        val arr = readBlock(segId, blockIndex * blockSize)

        val result = lock.withLock {
            blocks[key]?.let { return@withLock it } // another thread won the race; reuse its block
            blocks[key] = arr
            arr
        }
        hot[slot] = Hot(key, result)
        return result
    }

    private fun readBlock(segId: Int, base: Long): ByteArray {
        var attempt = 0
        while (true) {
            // A null handle means the segment was closed/invalidated; propagate as before (callers iterate a
            // snapshot of the live segments, so this only races a concurrent reset).
            val source = handleFor(segId) ?: throw SegmentClosedException()
            countLock.withLock { reads++ }

            // Never ask past the end: the JVM's seek-and-read throws on a short read and the POSIX one pads
            // silently, so the only way the two agree about the final block is not to over-ask.
            val remaining = source.size - base
            if (remaining <= 0) return ByteArray(0)
            val wanted = minOf(blockSize.toLong(), remaining).toInt()

            val result = runCatching { source.read(base, wanted) }
            result.getOrNull()?.let { return it }

            // The LRU evicted + closed this handle between handleFor and the read (only possible under heavy
            // cross-segment contention, since we just made it most-recently-used). Drop it and retry with a
            // fresh one. A closed handle is not distinguishable from a real I/O failure here — the two
            // platforms raise different things — so this retries either, and a real failure is rethrown
            // rather than reported as a closed segment once the attempts run out.
            handleLock.withLock { openHandles.remove(segId)?.let { runCatching { it.close() } } }
            if (++attempt >= 4) throw result.exceptionOrNull() ?: SegmentClosedException()
        }
    }

    /**
     * Drop every block belonging to [segId] (called when its segment is closed or the index is invalidated),
     * and close + forget its handle.
     */
    fun evictSegment(segId: Int) {
        val hi = segId.toLong()
        lock.withLock { blocks.removeKeys { (it ushr 40) == hi } }
        for (i in hot.indices) if (hot[i]?.let { (it.key ushr 40) == hi } == true) hot[i] = null
        for (i in strings.indices) if (strings[i]?.let { (it.key ushr 32) == hi } == true) strings[i] = null
        handleLock.withLock {
            openHandles.remove(segId)?.let { runCatching { it.close() } }
            segFiles.remove(segId)
        }
    }

    fun clear() {
        lock.withLock { blocks.clear() }
        hot.fill(null)
        strings.fill(null)
        handleLock.withLock {
            openHandles.values.forEach { runCatching { it.close() } }
            openHandles.clear()
            segFiles.clear()
        }
    }

    companion object {
        // Cap on simultaneously-open segment handles (file descriptors). Generous enough that a query's
        // working set of segments stays open, small enough to leave headroom under the process FD limit.
        const val DEFAULT_MAX_OPEN_HANDLES = 128

        private const val HOT_BITS = 8
        private const val HOT_SLOTS = 1 shl HOT_BITS
        private const val STRING_BITS = 12
        private const val STRING_SLOTS = 1 shl STRING_BITS
    }
}
