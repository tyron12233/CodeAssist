package dev.ide.index.impl

import dev.ide.kotlin.classfile.FileSink
import dev.ide.kotlin.classfile.FileSource
import dev.ide.platform.ByteArrayDataReader
import dev.ide.platform.ByteArrayDataWriter
import dev.ide.platform.DataReader
import dev.ide.platform.DataWriter
import kotlin.random.Random

/**
 * The byte plumbing a segment is built and read back through, once `java.io` is not available.
 *
 * `DataOutputStream` over a `BufferedOutputStream` over a file is four separate things in one expression:
 * the big-endian encoding, the modified-UTF-8 string format, a buffer so that writing a varint is not a
 * syscall, and a byte count. [ByteArrayDataWriter] already owns the first two, exactly and by diff against
 * the real thing, so what is here is the other two: a buffer that drains into a [FileSink], and a running
 * length.
 *
 * The length has to be exact after every single write, not eventually: the segment's region offsets ARE the
 * lengths of the regions written before them, so a count that lagged a buffer would produce a file whose
 * footer points at the wrong bytes — which reads back as a plausible, wrong index rather than as an error.
 */
internal interface SegmentOut : DataWriter {
    /** Bytes written so far, buffered ones included. */
    val length: Long

    fun writeBytes(value: ByteArray)
}

/** Reading back what [SegmentOut] wrote, sequentially. */
internal interface SegmentIn : DataReader {
    fun readBytes(count: Int): ByteArray
}

/** Unsigned LEB128 — small values cost one byte; offsets, counts and deltas in a segment are non-negative. */
internal fun DataWriter.writeVarLong(v0: Long) {
    var v = v0
    while (true) {
        val b = (v and 0x7F).toInt()
        v = v ushr 7
        if (v != 0L) writeByte(b or 0x80) else return writeByte(b)
    }
}

/** Write [v] as a fixed [width]-byte big-endian unsigned int (a pool-table entry). */
internal fun DataWriter.writeFixedUInt(v: Int, width: Int) {
    var shift = (width - 1) * 8
    while (shift >= 0) {
        writeByte((v ushr shift) and 0xFF)
        shift -= 8
    }
}

/** An in-memory [SegmentOut]; [toByteArray] is everything written. */
internal class MemorySegmentOut : SegmentOut {
    private val bytes = ByteArrayDataWriter()

    override val length: Long get() = bytes.size.toLong()

    fun toByteArray(): ByteArray = bytes.toByteArray()

    override fun writeByte(value: Int) = bytes.writeByte(value)
    override fun writeBoolean(value: Boolean) = bytes.writeBoolean(value)
    override fun writeShort(value: Int) = bytes.writeShort(value)
    override fun writeInt(value: Int) = bytes.writeInt(value)
    override fun writeLong(value: Long) = bytes.writeLong(value)
    override fun writeUTF(value: String) = bytes.writeUTF(value)
    override fun writeBytes(value: ByteArray) = bytes.writeBytes(value)
}

/**
 * A [SegmentOut] that drains into a [FileSink] once it has enough to be worth a write.
 *
 * The buffer is a [ByteArrayDataWriter], so the encoding is the same code the in-memory path uses rather
 * than a second implementation of it.
 */
internal class SinkSegmentOut(
    private val sink: FileSink,
    private val bufferSize: Int = 64 * 1024,
) : SegmentOut {

    private var buffer = ByteArrayDataWriter()
    private var flushed = 0L

    override val length: Long get() = flushed + buffer.size

    override fun writeByte(value: Int) = drainIfFull { buffer.writeByte(value) }
    override fun writeBoolean(value: Boolean) = drainIfFull { buffer.writeBoolean(value) }
    override fun writeShort(value: Int) = drainIfFull { buffer.writeShort(value) }
    override fun writeInt(value: Int) = drainIfFull { buffer.writeInt(value) }
    override fun writeLong(value: Long) = drainIfFull { buffer.writeLong(value) }
    override fun writeUTF(value: String) = drainIfFull { buffer.writeUTF(value) }
    override fun writeBytes(value: ByteArray) = drainIfFull { buffer.writeBytes(value) }

    private inline fun drainIfFull(write: () -> Unit) {
        write()
        if (buffer.size >= bufferSize) flush()
    }

    /** Drain the buffer AND the sink, so the bytes are on disk and a reader opening the path sees them all. */
    fun flush() {
        if (buffer.size > 0) {
            val bytes = buffer.toByteArray()
            sink.write(bytes)
            flushed += bytes.size
            buffer = ByteArrayDataWriter()
        }
        sink.flush()
    }

    fun close() {
        flush()
        sink.close()
    }
}

/**
 * A buffered sequential [SegmentIn] over a [FileSource].
 *
 * The source reads at an absolute offset, which is what the block cache wants and the opposite of what
 * reading a spilled run wants: a run is read once, front to back. So this keeps the position and refills a
 * window, and a read that straddles the window falls back to going straight to the file.
 */
internal class SourceSegmentIn(
    private val source: FileSource,
    private val windowSize: Int = 64 * 1024,
) : SegmentIn {

    private var window = ByteArray(0)
    private var windowBase = 0L
    private var at = 0

    private fun position(): Long = windowBase + at

    private fun ensure(count: Int) {
        if (at + count <= window.size) return
        val from = position()
        val wanted = minOf(maxOf(count, windowSize).toLong(), source.size - from).toInt()
        window = if (wanted <= 0) ByteArray(0) else source.read(from, wanted)
        windowBase = from
        at = 0
    }

    override fun readByte(): Int = readUnsignedByte().toByte().toInt()

    override fun readUnsignedByte(): Int {
        ensure(1)
        return window[at++].toInt() and 0xFF
    }

    override fun readBoolean(): Boolean = readUnsignedByte() != 0
    override fun readShort(): Int = (readUnsignedByte() shl 8) or readUnsignedByte()
    override fun readInt(): Int = (readShort() shl 16) or readShort()
    override fun readLong(): Long = (readInt().toLong() shl 32) or (readInt().toLong() and 0xFFFFFFFFL)

    override fun readBytes(count: Int): ByteArray {
        if (count == 0) return ByteArray(0)
        ensure(count)
        val slice = window.copyOfRange(at, at + count)
        at += count
        return slice
    }

    /** Delegated so the modified-UTF-8 decode is the one place it already is. */
    override fun readUTF(): String {
        val length = readShort()
        return ByteArrayDataReader(byteArrayOf((length ushr 8).toByte(), length.toByte()) + readBytes(length)).readUTF()
    }

    fun close() = source.close()
}

/**
 * A min-heap of run indices, ordered by whatever is at each run's head.
 *
 * `java.util.PriorityQueue` is what this replaces, in the k-way merge that reads the spilled runs back. A
 * linear scan over the heads would be simpler and is the wrong shape: k is the number of runs and n the
 * number of entries in the artifact, so it trades O(n log k) for O(n k) on the one loop that touches every
 * indexed entry.
 */
internal class IntHeap(private val lessThan: (Int, Int) -> Boolean) {

    private var items = IntArray(16)
    private var count = 0

    val isEmpty: Boolean get() = count == 0

    fun add(value: Int) {
        if (count == items.size) items = items.copyOf(items.size * 2)
        items[count] = value
        siftUp(count)
        count++
    }

    fun poll(): Int {
        val top = items[0]
        count--
        if (count > 0) {
            items[0] = items[count]
            siftDown(0)
        }
        return top
    }

    private fun siftUp(from: Int) {
        var i = from
        while (i > 0) {
            val parent = (i - 1) / 2
            if (!lessThan(items[i], items[parent])) return
            swap(i, parent)
            i = parent
        }
    }

    private fun siftDown(from: Int) {
        var i = from
        while (true) {
            val left = i * 2 + 1
            if (left >= count) return
            val right = left + 1
            val child = if (right < count && lessThan(items[right], items[left])) right else left
            if (!lessThan(items[child], items[i])) return
            swap(i, child)
            i = child
        }
    }

    private fun swap(a: Int, b: Int) {
        val t = items[a]
        items[a] = items[b]
        items[b] = t
    }
}

/**
 * A name no concurrent writer will pick too.
 *
 * `UUID.randomUUID()` is what this replaces, and uniqueness is all that was ever wanted from it: two builds
 * of the same content-addressed segment run at once, and each needs its own temporaries.
 */
internal fun temporaryName(prefix: String): String =
    prefix + "-" + Random.nextLong().toULong().toString(16) + "-" + Random.nextInt().toUInt().toString(16)
