package dev.ide.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The streaming write seam, run on each platform that claims it.
 *
 * Two implementations, one on `java.nio` and one on `stdio`, and the segment writer above them cannot tell
 * which it has. That only holds if both agree about the cases that are easy to get subtly wrong: that many
 * small appends concatenate in order, that a partial-array write takes the right slice, that opening an
 * existing file truncates rather than appends, and that the move replaces a target that is already there.
 */
class FileSinkTest {

    private fun scratch(name: String): String =
        assertNotNull(writeTempFile(name, ByteArray(0)), "this platform must have scratch space")

    @Test
    fun manySmallAppendsConcatenateInOrder() {
        // The shape the segment writer actually has: a varint, a term, a length, thousands of times over.
        val path = scratch("sink-appends")
        val sink = assertNotNull(openFileForWrite(path), "opening $path")
        val expected = ArrayList<Byte>()
        for (i in 0 until 500) {
            val chunk = ByteArray(1 + (i % 7)) { (i + it).toByte() }
            sink.write(chunk)
            expected.addAll(chunk.toList())
        }
        sink.close()

        assertTrue(expected.toByteArray().contentEquals(readFile(path)), "contents of $path")
    }

    @Test
    fun aPartialWriteTakesTheRequestedSlice() {
        val path = scratch("sink-slice")
        val sink = assertNotNull(openFileForWrite(path))
        val buffer = byteArrayOf(9, 9, 1, 2, 3, 9, 9)
        sink.write(buffer, offset = 2, length = 3)
        sink.close()

        assertTrue(byteArrayOf(1, 2, 3).contentEquals(readFile(path)))
    }

    @Test
    fun anEmptyWriteIsNotAnError() {
        val path = scratch("sink-empty")
        val sink = assertNotNull(openFileForWrite(path))
        sink.write(ByteArray(0))
        sink.write(byteArrayOf(1, 2, 3), offset = 1, length = 0)
        sink.close()

        assertEquals(0, assertNotNull(readFile(path)).size)
    }

    @Test
    fun openingAnExistingFileTruncatesIt() {
        // A run file is reused by name across builds; appending to the old one would silently corrupt it.
        val path = scratch("sink-truncate")
        assertTrue(writeFileAtomically(path, ByteArray(4096) { 7 }))

        val sink = assertNotNull(openFileForWrite(path))
        sink.write(byteArrayOf(1))
        sink.close()

        assertEquals(1, assertNotNull(readFile(path)).size, "the old contents are gone, not appended to")
    }

    @Test
    fun aMissingDirectoryIsNullRatherThanAnException() {
        val path = scratch("sink-missing") + "-nodir/inner/file.bin"
        assertNull(openFileForWrite(path))
    }

    @Test
    fun aMoveReplacesTheTargetAndLeavesNothingBehind() {
        val from = scratch("sink-move-from")
        val to = scratch("sink-move-to")
        assertTrue(writeFileAtomically(from, "new".encodeToByteArray()))
        assertTrue(writeFileAtomically(to, "old and longer".encodeToByteArray()))

        assertTrue(moveFile(from, to))
        assertEquals("new", assertNotNull(readFile(to)).decodeToString())
        assertNull(fileInfo(from), "the source is gone")
    }

    @Test
    fun aMoveOntoAFreshPathWorksToo() {
        val from = scratch("sink-move-fresh-from")
        val to = scratch("sink-move-fresh-to")
        assertTrue(deleteFile(to) || fileInfo(to) == null)
        assertTrue(writeFileAtomically(from, "payload".encodeToByteArray()))

        assertTrue(moveFile(from, to))
        assertEquals("payload", assertNotNull(readFile(to)).decodeToString())
    }

    @Test
    fun aMoveOfSomethingThatIsNotThereIsFalse() {
        val from = scratch("sink-move-absent") + ".missing"
        val to = scratch("sink-move-absent-target")
        assertFalse(moveFile(from, to), "false, not an exception")
    }
}
