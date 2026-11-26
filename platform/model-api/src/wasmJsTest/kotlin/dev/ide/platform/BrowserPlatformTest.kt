// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.platform

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BrowserPlatformTest {
    @Test fun sha256KnownVectors() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", ContentHash.of(ByteArray(0)).value)
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", ContentHash.of("abc").value)
        // 56 bytes: the padding spills into a second block.
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            ContentHash.of("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq").value,
        )
    }

    @Test fun memoryFileSystemRoundTrip() {
        assertTrue(writeFileAtomically("/t/a/b.txt", "hi".encodeToByteArray()))
        assertContentEquals("hi".encodeToByteArray(), readFile("/t//a/./b.txt"))
        assertEquals(listOf("/t/a/b.txt"), listDirectory("/t/a/"))
        assertTrue(fileInfo("/t/a")!!.isDirectory)
        assertEquals(2L, fileInfo("/t/a/b.txt")!!.size)
        assertFalse(createDirectories("/t/a/b.txt/x")) // a file blocks the path
        assertTrue(moveFile("/t/a/b.txt", "/t/c/d.txt"))
        assertNull(readFile("/t/a/b.txt"))
        val sink = openFileForWrite("/t/c/e.bin")!!
        sink.write(ByteArray(1000) { it.toByte() }); sink.write(byteArrayOf(1, 2), 0, 1); sink.close()
        assertEquals(1001L, fileInfo("/t/c/e.bin")!!.size)
        assertTrue(deleteFile("/t/c/e.bin"))
        assertFalse(deleteFile("/t/c")) // non-empty directory
    }
}
