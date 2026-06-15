package dev.ide.index.impl

import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * A bounded, process-shared LRU of fixed-size file blocks — the only thing that bounds the indexing
 * subsystem's resident memory. [Segment]s read everything (term dictionary, postings, trigrams, values)
 * through this cache via positioned channel reads, so the JVM heap holds at most [maxBlocks] blocks of
 * hot data **regardless of how large the on-disk indexes grow**. That is the "reads from disk, not RAM"
 * property: a 9 MB members partition costs a handful of cached blocks, not 9 MB of heap.
 *
 * Blocks are keyed by `(segId, blockIndex)`; [evictSegment] drops a closed/invalidated segment's blocks.
 * Reads use [FileChannel.read] at an absolute position (does not touch the channel's own position, so it
 * is safe for concurrent readers) and happen OUTSIDE the lock — the lock only guards the map.
 */
internal class BlockCache(maxBytes: Long, val blockSize: Int = 4096) {

    private val maxBlocks = maxOf(1, (maxBytes / blockSize).toInt())
    private val lock = Any()

    // accessOrder=true → get() moves the entry to the MRU end; removeEldestEntry evicts the LRU end.
    private val map = object : LinkedHashMap<Long, ByteArray>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?): Boolean = size > maxBlocks
    }

    private fun keyOf(segId: Int, blockIndex: Long): Long = (segId.toLong() shl 40) or blockIndex

    /** The block (≤ [blockSize] bytes; shorter only for the final block) containing the byte at [fileOffset]. */
    fun block(segId: Int, channel: FileChannel, fileOffset: Long): ByteArray {
        val blockIndex = fileOffset / blockSize
        val key = keyOf(segId, blockIndex)
        synchronized(lock) { map[key]?.let { return it } }

        // Miss: read off-lock so a slow disk read never stalls other queries.
        val base = blockIndex * blockSize
        val buf = ByteBuffer.allocate(blockSize)
        var read = 0
        while (read < blockSize) {
            val n = channel.read(buf, base + read)
            if (n <= 0) break // EOF (or nothing left): the final block is shorter than blockSize
            read += n
        }
        val arr = if (read == blockSize) buf.array() else buf.array().copyOf(read)

        synchronized(lock) {
            map[key]?.let { return it } // another thread won the race; reuse its block
            map[key] = arr
            return arr
        }
    }

    /** Drop every block belonging to [segId] (called when its segment is closed or the index is invalidated). */
    fun evictSegment(segId: Int) {
        val hi = segId.toLong()
        synchronized(lock) { map.keys.removeAll { (it ushr 40) == hi } }
    }

    fun clear() { synchronized(lock) { map.clear() } }
}
