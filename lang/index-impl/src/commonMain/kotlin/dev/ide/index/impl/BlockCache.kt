package dev.ide.index.impl

import dev.ide.kotlin.classfile.FileSource
import dev.ide.platform.Lock
import dev.ide.kotlin.classfile.openFile

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
        lock.withLock { blocks[key] }?.let { return it }

        // Miss: read off-lock so a slow disk read never stalls other queries.
        val arr = readBlock(segId, blockIndex * blockSize)

        return lock.withLock {
            blocks[key]?.let { return@withLock it } // another thread won the race; reuse its block
            blocks[key] = arr
            arr
        }
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
        handleLock.withLock {
            openHandles.remove(segId)?.let { runCatching { it.close() } }
            segFiles.remove(segId)
        }
    }

    fun clear() {
        lock.withLock { blocks.clear() }
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
    }
}
