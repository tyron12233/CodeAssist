package dev.ide.index.impl

import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap

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
 *
 * The cache ALSO owns the segments' open file channels, as a second bounded LRU ([maxOpenChannels]). A
 * segment used to hold its `FileChannel` open for its whole life; with one segment per artifact per index
 * extension, a large (e.g. Compose) classpath opened hundreds of channels at once and exhausted the
 * process file-descriptor limit (≈1024 on Android), surfacing as "Too many open files" on the next
 * unrelated `open()`. Channels are now opened lazily on a block miss and the least-recently-used one is
 * closed when over the cap; an evicted segment is simply reopened on its next miss (hits never touch a
 * channel). Segments register their file via [registerSegment] and never see a channel directly.
 */
internal class BlockCache(
    maxBytes: Long,
    val blockSize: Int = 4096,
    private val maxOpenChannels: Int = DEFAULT_MAX_OPEN_CHANNELS,
) {

    private val maxBlocks = maxOf(1, (maxBytes / blockSize).toInt())
    private val lock = Any()

    // accessOrder=true → get() moves the entry to the MRU end; removeEldestEntry evicts the LRU end.
    private val map = object : LinkedHashMap<Long, ByteArray>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?): Boolean = size > maxBlocks
    }

    private val channelLock = Any()
    private val segFiles = ConcurrentHashMap<Int, Path>()
    private val openChannels = object : LinkedHashMap<Int, FileChannel>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, FileChannel>): Boolean {
            if (size <= maxOpenChannels) return false
            runCatching { eldest.value.close() }
            return true
        }
    }

    private fun keyOf(segId: Int, blockIndex: Long): Long = (segId.toLong() shl 40) or blockIndex

    /** Record the file backing [segId] so its channel can be (re)opened on demand. */
    fun registerSegment(segId: Int, file: Path) { segFiles[segId] = file }

    /** The open channel for [segId] from the bounded LRU, opening it if absent. Null only if the segment was
     *  closed/invalidated (its file deregistered). */
    private fun channelFor(segId: Int): FileChannel? = synchronized(channelLock) {
        openChannels[segId]?.let { if (it.isOpen) return it else openChannels.remove(segId) }
        val file = segFiles[segId] ?: return null
        val ch = runCatching { FileChannel.open(file, StandardOpenOption.READ) }.getOrNull() ?: return null
        openChannels[segId] = ch // may evict + close the least-recently-used channel
        ch
    }

    /** The block (≤ [blockSize] bytes; shorter only for the final block) containing the byte at [fileOffset]. */
    fun block(segId: Int, fileOffset: Long): ByteArray {
        val blockIndex = fileOffset / blockSize
        val key = keyOf(segId, blockIndex)
        synchronized(lock) { map[key]?.let { return it } }

        // Miss: read off-lock so a slow disk read never stalls other queries.
        val arr = readBlock(segId, blockIndex * blockSize)

        synchronized(lock) {
            map[key]?.let { return it } // another thread won the race; reuse its block
            map[key] = arr
            return arr
        }
    }

    private fun readBlock(segId: Int, base: Long): ByteArray {
        var attempt = 0
        while (true) {
            // A null channel means the segment was closed/invalidated; propagate as before (callers iterate a
            // snapshot of the live segments, so this only races a concurrent reset).
            val channel = channelFor(segId) ?: throw ClosedChannelException()
            try {
                val buf = ByteBuffer.allocate(blockSize)
                var read = 0
                while (read < blockSize) {
                    val n = channel.read(buf, base + read)
                    if (n <= 0) break // EOF (or nothing left): the final block is shorter than blockSize
                    read += n
                }
                return if (read == blockSize) buf.array() else buf.array().copyOf(read)
            } catch (e: ClosedChannelException) {
                // The LRU evicted + closed this handle between channelFor and the read (only possible under
                // heavy cross-segment contention, since we just made it MRU). Drop it and retry with a fresh
                // one; bounded so a genuinely vanished file can't spin forever.
                synchronized(channelLock) { openChannels.remove(segId) }
                if (++attempt >= 4) throw e
            }
        }
    }

    /** Drop every block belonging to [segId] (called when its segment is closed or the index is invalidated),
     *  and close + forget its channel. */
    fun evictSegment(segId: Int) {
        val hi = segId.toLong()
        synchronized(lock) { map.keys.removeAll { (it ushr 40) == hi } }
        synchronized(channelLock) {
            openChannels.remove(segId)?.let { runCatching { it.close() } }
            segFiles.remove(segId)
        }
    }

    fun clear() {
        synchronized(lock) { map.clear() }
        synchronized(channelLock) {
            openChannels.values.forEach { runCatching { it.close() } }
            openChannels.clear()
            segFiles.clear()
        }
    }

    companion object {
        // Cap on simultaneously-open segment channels (file descriptors). Generous enough that a query's
        // working set of segments stays open, small enough to leave headroom under the process FD limit.
        const val DEFAULT_MAX_OPEN_CHANNELS = 128
    }
}
