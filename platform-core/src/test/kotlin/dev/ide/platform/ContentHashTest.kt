package dev.ide.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * [ContentHash.of] is the single canonical content digest used by both the VFS (`VirtualFile.contentHash`)
 * and the event spine (`WorkspaceEvents`), so a file's hash is identical however it is computed. These
 * pin the algorithm (SHA-256 lowercase hex) and the text/bytes agreement that consistency relies on.
 */
class ContentHashTest {

    @Test
    fun textDigestEqualsItsUtf8Bytes() {
        val text = "package a;\nclass A {}\n"
        assertEquals(ContentHash.of(text), ContentHash.of(text.toByteArray(Charsets.UTF_8)),
            "of(text) must equal of(utf8 bytes) — the two sides must agree")
    }

    @Test
    fun knownSha256Vector() {
        // Empty input SHA-256, lowercase hex — locks the exact format callers persist/compare.
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            ContentHash.of(ByteArray(0)).value,
        )
    }

    @Test
    fun distinctContentHashesDiffer() {
        assertNotEquals(ContentHash.of(byteArrayOf(1, 2, 3)), ContentHash.of(byteArrayOf(1, 2, 4)))
    }
}
