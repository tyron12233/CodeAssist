package dev.ide.kotlin.classfile

import dev.ide.platform.openFile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The one platform-specific thing in the module, run on each platform it claims to support.
 *
 * Compiling for a target says nothing about whether its `fopen` was called correctly, and a seek-and-read
 * seam that silently returns zeros is the kind of thing that surfaces as a corrupt class file much later.
 */
class FileSourceTest {

    @Test
    fun aFileIsReadAtArbitraryOffsets() {
        val bytes = ByteArray(8192) { (it * 31 % 251).toByte() }
        val path = writeTempFile("kotlin-classfile-source", bytes)
        assertNotNull(path, "this platform must be able to write a scratch file")

        val source = assertNotNull(openFile(path), "opening $path")
        try {
            assertEquals(bytes.size.toLong(), source.size)
            // The access pattern a zip forces: the end first, then scattered middles, never front to back.
            assertTrue(bytes.copyOfRange(8_000, 8_192).contentEquals(source.read(8_000, 192)), "the tail")
            assertTrue(bytes.copyOfRange(0, 16).contentEquals(source.read(0, 16)), "the head")
            assertTrue(bytes.copyOfRange(4_095, 4_100).contentEquals(source.read(4_095, 5)), "the middle")
            assertTrue(source.read(1_000, 0).isEmpty(), "an empty read")
            // Reading the same range twice must give the same answer: a handle that is never rewound would
            // pass every assertion above and fail this one.
            assertTrue(source.read(0, 16).contentEquals(source.read(0, 16)), "a repeated read")
        } finally {
            source.close()
        }
    }

    @Test
    fun anEmptyFileOpensAndAMissingOneDoesNot() {
        val path = assertNotNull(writeTempFile("kotlin-classfile-empty", ByteArray(0)))
        val source = assertNotNull(openFile(path))
        assertEquals(0L, source.size)
        source.close()

        assertNull(openFile("$path.does-not-exist"), "a missing file must be null, not an exception")
    }

    @Test
    fun anArchiveIsReadThroughTheFileSeamWithoutLoadingIt() {
        // The end the seam exists for. Everything above this line works on a byte array; a 43 MB android.jar
        // does not want to be one.
        val path = assertNotNull(writeTempFile("kotlin-classfile-archive", MINIMAL_ZIP))
        val source = assertNotNull(openFile(path))
        try {
            val archive = assertNotNull(ZipArchive.open(source), "opening the archive over a file")
            assertEquals(listOf("hello.txt"), archive.entries.map { it.name })
            val entry = assertNotNull(archive.entry("hello.txt"))
            assertEquals("Hello, zip.", assertNotNull(archive.read(entry)).decodeToString())
        } finally {
            source.close()
        }
    }

    companion object {
        /**
         * A stored single-entry archive, byte for byte.
         *
         * Written out rather than generated because generating one needs a zip WRITER, which this module
         * deliberately does not have, and because a fixed archive is the same on every platform the test
         * runs on. Produced by a real writer and pasted, NOT assembled by hand: the first hand-assembled
         * one had the directory offset wrong and read back as an archive of nothing.
         */
        private val MINIMAL_ZIP: ByteArray = intArrayOf(
            // Local file header, then "Hello, zip." stored, then the central directory, then the end record.
            0x50, 0x4B, 0x03, 0x04, 0x14, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x21, 0x00, 0x1D, 0x53,
            0xED, 0x71, 0x0B, 0x00, 0x00, 0x00, 0x0B, 0x00, 0x00, 0x00, 0x09, 0x00, 0x00, 0x00, 0x68, 0x65,
            0x6C, 0x6C, 0x6F, 0x2E, 0x74, 0x78, 0x74, 0x48, 0x65, 0x6C, 0x6C, 0x6F, 0x2C, 0x20, 0x7A, 0x69,
            0x70, 0x2E, 0x50, 0x4B, 0x01, 0x02, 0x14, 0x00, 0x14, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x21, 0x00, 0x1D, 0x53, 0xED, 0x71, 0x0B, 0x00, 0x00, 0x00, 0x0B, 0x00, 0x00, 0x00, 0x09, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x80, 0x01, 0x00, 0x00, 0x00, 0x00,
            0x68, 0x65, 0x6C, 0x6C, 0x6F, 0x2E, 0x74, 0x78, 0x74, 0x50, 0x4B, 0x05, 0x06, 0x00, 0x00, 0x00,
            0x00, 0x01, 0x00, 0x01, 0x00, 0x37, 0x00, 0x00, 0x00, 0x32, 0x00, 0x00, 0x00, 0x00, 0x00,
        ).map { it.toByte() }.toByteArray()
    }
}
