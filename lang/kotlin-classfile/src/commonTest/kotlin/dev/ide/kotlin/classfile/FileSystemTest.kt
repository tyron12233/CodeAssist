package dev.ide.kotlin.classfile

import dev.ide.platform.ByteArrayDataReader
import dev.ide.platform.ByteArrayDataWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The file-system seam, run on each platform that claims it.
 *
 * Two implementations of six operations, one on `java.nio.file` and one on Foundation, and the classpath
 * reader above them cannot tell which it has. That only holds if both agree about the awkward cases: what a
 * missing file reports, what a directory's size is, and whether a half-written cache can ever be observed.
 */
class FileSystemTest {

    private fun scratchDirectory(name: String): String {
        // writeTempFile gives a path inside a writable directory; its parent is that directory.
        val file = assertNotNull(writeTempFile(name, ByteArray(0)), "this platform must have scratch space")
        val directory = file.substringBeforeLast('/') + "/" + name + "-dir"
        assertTrue(createDirectories(directory), "creating $directory")
        return directory
    }

    @Test
    fun aFileIsStattedAndReadBack() {
        val bytes = ByteArray(2048) { (it % 97).toByte() }
        val path = assertNotNull(writeTempFile("kotlin-classfile-fs", bytes))

        val info = assertNotNull(fileInfo(path), "stat of $path")
        assertEquals(path, info.path)
        assertTrue(!info.isDirectory)
        assertEquals(bytes.size.toLong(), info.size)
        // Milliseconds since the epoch on both platforms, which is what a content-keyed cache is keyed on.
        // Checked as a range rather than a value: the only wrong answers are seconds, or zero.
        assertTrue(info.lastModified > 1_600_000_000_000L, "mtime looks like ms: ${info.lastModified}")

        assertTrue(bytes.contentEquals(readFile(path)), "contents of $path")
    }

    @Test
    fun aMissingFileIsNullEverywhere() {
        val path = assertNotNull(writeTempFile("kotlin-classfile-gone", ByteArray(1))) + ".missing"
        assertNull(fileInfo(path), "stat of a missing file")
        assertNull(readFile(path), "reading a missing file")
        assertEquals(emptyList(), listDirectory(path), "listing a missing directory")
    }

    @Test
    fun aDirectoryIsCreatedListedAndDistinguished() {
        val directory = scratchDirectory("kotlin-classfile-list")
        assertTrue(createDirectories("$directory/nested/deeper"), "missing parents are created too")

        assertTrue(writeFileAtomically("$directory/b.txt", "second".encodeToByteArray()))
        assertTrue(writeFileAtomically("$directory/a.txt", "first".encodeToByteArray()))

        val entries = listDirectory(directory)
        assertEquals(
            listOf("$directory/a.txt", "$directory/b.txt", "$directory/nested"),
            entries,
            "full paths, sorted",
        )

        val info = assertNotNull(fileInfo("$directory/nested"))
        assertTrue(info.isDirectory, "a directory says so")
        assertEquals(0L, info.size, "a directory has no size worth reporting")
        assertTrue(!assertNotNull(fileInfo("$directory/a.txt")).isDirectory)
    }

    @Test
    fun anAtomicWriteReplacesAndCanBeDeleted() {
        val directory = scratchDirectory("kotlin-classfile-atomic")
        val path = "$directory/cache.bin"

        assertTrue(writeFileAtomically(path, "old".encodeToByteArray()))
        assertEquals("old", assertNotNull(readFile(path)).decodeToString())

        // Replacing has to work, not just creating: a cache is rewritten far more often than it is created.
        assertTrue(writeFileAtomically(path, "new and longer".encodeToByteArray()))
        assertEquals("new and longer", assertNotNull(readFile(path)).decodeToString())
        assertEquals(14L, assertNotNull(fileInfo(path)).size)

        assertTrue(writeFileAtomically(path, ByteArray(0)), "an empty write is still a write")
        assertEquals(0, assertNotNull(readFile(path)).size)

        assertTrue(deleteFile(path))
        assertNull(fileInfo(path), "deleted")
        assertTrue(!deleteFile(path), "deleting twice is false, not an exception")
    }

    @Test
    fun aPersistedCacheRoundTripsThroughTheDataFormat() {
        // The end this seam exists for: a scan written by one launch, read by the next, in the same bytes
        // java.io.DataOutputStream writes.
        val directory = scratchDirectory("kotlin-classfile-cache")
        val path = "$directory/scan.bin"

        val writer = ByteArrayDataWriter()
        writer.writeInt(2)
        writer.writeUTF("kotlin.collections.CollectionsKt")
        writer.writeBoolean(true)
        writer.writeUTF("listOf")
        writer.writeBoolean(false)
        assertTrue(writeFileAtomically(path, writer.toByteArray()))

        val reader = ByteArrayDataReader(assertNotNull(readFile(path)))
        assertEquals(2, reader.readInt())
        assertEquals("kotlin.collections.CollectionsKt", reader.readUTF())
        assertEquals(true, reader.readBoolean())
        assertEquals("listOf", reader.readUTF())
        assertEquals(false, reader.readBoolean())
        assertTrue(!reader.hasMore)
    }
}
