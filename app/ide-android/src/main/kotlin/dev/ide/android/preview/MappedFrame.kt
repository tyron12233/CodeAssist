package dev.ide.android.preview

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * One frame's pixels, shared between the IDE and the `:preview` process through a file both map.
 *
 * The renderer copies its bitmap straight into the mapping and the IDE copies straight out of it, so a frame
 * crosses the process boundary with no Java-heap buffer on either side (it used to cost a fresh heap buffer
 * the size of the frame in each process, plus a file written and deleted per render). The file is kept and
 * reused: the caller reads it before it asks for the next render, so one file is enough.
 *
 * The file only ever GROWS. Truncating a file that another process has mapped makes that process fault
 * (SIGBUS) on its next read, so a smaller frame uses the front of the existing file instead.
 *
 * Each side maps whatever file is at the path NOW: the mapping is redone when the file there is a different
 * one (its inode changed). The system may clear the cache directory, and a restarted `:preview` creates the
 * file afresh; a side still mapping the old, unlinked file would otherwise read or write frames nobody sees.
 */
internal class MappedFrame(private val file: File, private val writable: Boolean) : Closeable {

    private var raf: RandomAccessFile? = null
    private var mapped: MappedByteBuffer? = null
    private var inode: Long = -1L

    /** A buffer over the first [bytes] bytes of the file, at position 0, mapping it again only when it is too small. */
    @Synchronized
    fun buffer(bytes: Int): ByteBuffer {
        val current = mapped
        if (current == null || current.capacity() < bytes || inodeNow() != inode) remap(bytes)
        return mapped!!.duplicate().apply { clear(); limit(bytes) }
    }

    private fun remap(bytes: Int) {
        close()
        if (writable) file.parentFile?.mkdirs()
        val f = RandomAccessFile(file, if (writable) "rw" else "r")
        if (writable && f.length() < bytes) f.setLength(bytes.toLong())
        val size = f.length()
        check(size >= bytes) { "frame file holds $size bytes, need $bytes" }
        raf = f
        mapped = f.channel.map(if (writable) FileChannel.MapMode.READ_WRITE else FileChannel.MapMode.READ_ONLY, 0, size)
        inode = inodeNow()
    }

    /** The inode of the file at the path, or -1 when there is none. */
    private fun inodeNow(): Long = runCatching { android.system.Os.stat(file.path).st_ino }.getOrDefault(-1L)

    /** Drops the mapping's file handle; the mapping itself goes when it is collected. */
    @Synchronized
    override fun close() {
        mapped = null
        inode = -1L
        runCatching { raf?.close() }
        raf = null
    }
}
