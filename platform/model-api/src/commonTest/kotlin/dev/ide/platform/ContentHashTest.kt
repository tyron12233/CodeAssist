package dev.ide.platform

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The digest, pinned to published vectors, on every platform.
 *
 * `ContentHash` is a cache key: the build, the VFS and the indexes all decide whether work can be reused by
 * comparing one. Two platforms that disagree about a file's hash would either rebuild everything or reuse
 * the wrong output, and neither failure says what it is. So the JVM's `MessageDigest` and Apple's
 * `CC_SHA256` are both checked against the same known answers rather than against each other.
 */
class ContentHashTest {

    @Test
    fun theDigestMatchesTheStandardVectors() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            ContentHash.of("").value,
            "SHA-256 of the empty input",
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            ContentHash.of("abc").value,
        )
        assertEquals(
            "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9",
            ContentHash.of("hello world").value,
        )
    }

    @Test
    fun textAndBytesAgree() {
        // `of(String)` has to equal `of(ByteArray)` over the bytes a UTF-8 write would persist, or a file's
        // hash depends on which overload happened to be called.
        val text = "package dev.ide\n\nclass Ünïcødé // 😀\n"
        assertEquals(ContentHash.of(text), ContentHash.of(text.encodeToByteArray()))
    }

    @Test
    fun aLongInputIsNotTruncated() {
        // Past one SHA-256 block, so the compression function runs more than once.
        val long = "x".repeat(10_000)
        assertEquals(
            "0f21b2bd8d5a36e6b41c4bd8cb4b2b28c0ad0d4e3ce5c0e0b5f96a0e60d5c9a1".length,
            ContentHash.of(long).value.length,
            "a digest is always 64 hex characters",
        )
        assertEquals(ContentHash.of(long), ContentHash.of(long))
        assertEquals(false, ContentHash.of(long) == ContentHash.of(long + "y"))
    }
}
