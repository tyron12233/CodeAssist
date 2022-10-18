package com.tyron.builder.files

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.util.Collections
import java.lang.Short.BYTES as SHORT_BYTES

/**
 * Class parsing and representing a Zip file Central Directory Record (CDR)
 *
 * This class expects that the Zip is properly formed and does not provide validation
 * on the format.
 *
 * The underlying file is read lazily as needed, so it is light weight to create an instance.
 */
class ZipCentralDirectory(
    val file: File
) {

    /**
     * The entries of the Zip archive.
     */
    val entries: Map<String, DirectoryEntry> by lazy { readZipEntries() }

    private val directoryBuffer: ByteBuffer by lazy { initBuffer() }

    /**
     * Writes the CDR only to a new Zip file.
     */
    fun writeTo(file: File) {
        val eocd = ByteBuffer.wrap(ByteArray(22))
        eocd.order(ByteOrder.LITTLE_ENDIAN)
        eocd.putInt(EOCD_SIGNATURE)
        eocd.putShort(0) // number of disk
        eocd.putShort(0) // disk where CDR starts
        eocd.putShort(1) // number of CDR on this disk
        eocd.putShort(1) // total number of CDR
        eocd.putInt(directoryBuffer.capacity()) // size of eocd
        eocd.putInt(0) // offset to start of CDR
        eocd.putShort(0) // comment length

        directoryBuffer.position(0)

        FileOutputStream(file).use { fos ->
            BufferedOutputStream(fos).use { bos ->
                bos.write(directoryBuffer.array())
                bos.write(eocd.array())
            }
        }
    }

    private fun initBuffer() : ByteBuffer {
        return RandomAccessFile(file.absolutePath, "r").use { raf ->
            val info = readCentralDirectoryRecord(raf)

            val cdContent = ByteArray(info.cdSize.toInt())
            raf.seek(info.cdOffset)
            raf.readFully(cdContent)
            val buffer = ByteBuffer.wrap(cdContent)
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            buffer
        }
    }

    private class CdrInfo {
        var cdOffset: Long = UNINITIALIZED
        var cdSize: Long = UNINITIALIZED
    }

    private fun readCentralDirectoryRecord(randomAccessFile: RandomAccessFile): CdrInfo {

        // Search the End of Central Directory Record
        // The End of Central Directory record size is 22 bytes if the comment section size is zero.
        // The comment section can be of any size, up to 65535 since it is stored on two bytes.
        // We start at the likely position of the beginning of the EoCD position and backtrack toward the
        // beginning of the buffer.

        // first, quickly read the last 22 bytes only for the case where there is no coment.
        // If this fails, we'll read the max needed which is 22 + 65K

        var (buffer, offset) = readBuffer(22, randomAccessFile)

        val signature = buffer.int
        val eocdOffset:Long = if (signature == EOCD_SIGNATURE) {
            offset
        } else {
            // read previous 65K and try from that instead
            readBuffer(65535 + 22, randomAccessFile).also {
                buffer = it.first
                offset = it.second
            }

            // position at the end minus EOCD size (22) and then minus 1 since we just tested
            // the position at -22 above.
            buffer.position(buffer.capacity() - 23)

            while (buffer.int != EOCD_SIGNATURE) {
                // move 4 because of the read above and then an extra one to test the previous byte.
                val position = buffer.position() - Integer.BYTES - 1
                if (buffer.position() == 0) {
                    throw RuntimeException("Failed to find EOCD in $file")
                }
                buffer.position(position)
            }

            buffer.position() + offset - Integer.BYTES
        }

        val info = readEOCDFromBuffer(buffer)

        /*
         * Look for the Zip64 central directory locator. If we find it, then this file is a Zip64
         * file and we do not support it.
         */
        val zip64LocatorStart = eocdOffset - ZIP64_EOCD_LOCATOR_SIZE
        if (zip64LocatorStart >= 0) {
            randomAccessFile.seek(zip64LocatorStart)
            for (byte in ZIP64_EOCD_LOCATOR_SIGNATURE) {
                if (randomAccessFile.readByte() != byte) {
                    return info
                }
            }
            throw Zip64NotSupportedException(
                "Zip64 EOCD locator found but Zip64 format is not supported: $file"
            )
        }

        return info
    }

    /**
     * Reads the random file and returns a buffer that contains the file.
     *
     * This will read the given number of bytes at the end of the file, and returns a [ByteBuffer]
     * containing the content, set to [ByteOrder.LITTLE_ENDIAN].
     *
     * Since this is meant to read the ZIP's EOCD, the size to read (and therefore the min size of
     * the file) must be at least 22.
     *
     * @param size the size to read. This is always at the end of the file
     * @param randomAccessFile the file to read from
     */
    private fun readBuffer(
        size: Int,
        randomAccessFile: RandomAccessFile
    ): Pair<ByteBuffer, Long> {
        val length = randomAccessFile.length()

        val sizeToRead = Math.min(length, size.toLong()).toInt()
        if (sizeToRead < 22) {
            throw java.lang.RuntimeException("Zip file smaller than EOCD size: $file")
        }

        val offset = length - sizeToRead
        randomAccessFile.seek(offset)
        val byteArray = ByteArray(sizeToRead)
        randomAccessFile.read(byteArray)

        val buffer: ByteBuffer = ByteBuffer.wrap(byteArray)
        buffer.order(ByteOrder.LITTLE_ENDIAN)

        return Pair(buffer, offset)
    }

    /**
     * Reads the EOCD from the buffer.
     *
     * @param buffer the little-endian buffer to read from. The position of the buffer should be
     * right after the EOCD signature.
     */
    private fun readEOCDFromBuffer(buffer: ByteBuffer): CdrInfo {
        val info = CdrInfo()

        // Read the End of Central Directory Record and record its position in the map. For now skip fields we don't use.
        buffer.position(buffer.position() + SHORT_BYTES * 4)
        //short numDisks = bytes.getShort();
        //short cdStartDisk = bytes.getShort();
        //short numCDRonDisk = bytes.getShort();
        //short numCDRecords = buffer.getShort();
        info.cdSize = uintToLong(buffer.int)
        info.cdOffset = uintToLong(buffer.int)
        //short sizeComment = bytes.getShort();

        return info
    }

    private fun readZipEntries(): Map<String, DirectoryEntry> {
        val buffer = directoryBuffer
        val entries = mutableMapOf<String, DirectoryEntry>()
        while (buffer.remaining() >= CENTRAL_DIRECTORY_FILE_HEADER_SIZE && buffer.int == CENTRAL_DIRECTORY_FILE_HEADER_MAGIC) {
            // Read all the data
            /*
            skip those and go over the 12 bytes directly
            val version = buf.short
            val versionNeeded = buf.short
            val flags = buf.short
            val compression = buf.short
            val modTime = buf.short
            val modDate = buf.short*/
            buffer.position(buffer.position() + 12)

            val crc: Long = uintToLong(buffer.int)
            /*val compressedSize =*/ uintToLong(buffer.int)
            val decompressedSize = uintToLong(buffer.int)
            val pathLength = ushortToInt(buffer.short)
            val extraLength = ushortToInt(buffer.short)
            val commentLength = ushortToInt(buffer.short)
            // Skip 2 (disk number) + 2 (internal attributes)+ 4 (external attributes)
            buffer.position(buffer.position() + 8)
            /*val start =*/ uintToLong(buffer.int) // offset to local file entry header

            // Read the filename
            val pathBytes = ByteArray(pathLength)
            buffer.get(pathBytes)
            val name = String(pathBytes, Charset.forName("UTF-8"))

            buffer.position(buffer.position() + extraLength + commentLength)

            // only add files, not directories
            if (decompressedSize > 0 || !name.endsWith("/")) {
                val entry = DirectoryEntry(name, crc, decompressedSize)
                entries[entry.name] = entry
            }
        }

        return Collections.unmodifiableMap(entries)
    }

    private fun uintToLong(i: Int) : Long {
        return (0xFF_FF_FF_FFL) and i.toLong()
    }

    private fun ushortToInt(i: Short) : Int{
        return (0xFF_FF) and i.toInt()
    }
}

data class DirectoryEntry(
    val name: String,
    val crc32: Long,
    val size: Long
)

private const val EOCD_SIGNATURE: Int = 0x06054b50
/** Signature of the Zip64 EOCD locator record.  */
private val ZIP64_EOCD_LOCATOR_SIGNATURE = byteArrayOf(0x50, 0x4B, 0x06, 0x07)

/** Number of bytes of the Zip64 EOCD locator record.  */
private const val ZIP64_EOCD_LOCATOR_SIZE = 20
private const val UNINITIALIZED: Long = -1
private const val CENTRAL_DIRECTORY_FILE_HEADER_MAGIC = 0x02014b50
private const val CENTRAL_DIRECTORY_FILE_HEADER_SIZE = 46
