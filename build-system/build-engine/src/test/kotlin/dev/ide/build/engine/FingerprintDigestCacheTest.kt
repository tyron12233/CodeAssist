package dev.ide.build.engine

import dev.ide.build.TaskInputsImpl
import dev.ide.testkit.withTempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Input fingerprints reuse a file's digest while its size and modification time are unchanged, and hash
 * it again whenever either moves or the file was modified too recently to trust its timestamp.
 */
class FingerprintDigestCacheTest {

    private fun fingerprint(file: Path): String =
        TaskInputsImpl().apply { filePaths("in", listOf(file)) }.fingerprint().value

    @Test
    fun aStableFileIsNotReadAgainWhileSizeAndTimeAreUnchanged() {
        withTempDir("fp") { dir ->
            val f = dir.resolve("lib.jar")
            val old = FileTime.fromMillis(System.currentTimeMillis() - 60_000)
            Files.writeString(f, "aaaa")
            Files.setLastModifiedTime(f, old)
            val first = fingerprint(f)

            // Same size, same timestamp: indistinguishable by stat, so the cached digest is used.
            Files.writeString(f, "bbbb")
            Files.setLastModifiedTime(f, old)
            assertEquals(first, fingerprint(f))
        }
    }

    @Test
    fun aRecentlyWrittenFileIsHashedAgainEvenWithAnUnchangedTimestamp() {
        withTempDir("fp") { dir ->
            val f = dir.resolve("Main.java")
            val now = FileTime.fromMillis(System.currentTimeMillis())
            Files.writeString(f, "aaaa")
            Files.setLastModifiedTime(f, now)
            val first = fingerprint(f)

            Files.writeString(f, "bbbb")
            Files.setLastModifiedTime(f, now)
            assertNotEquals(first, fingerprint(f))
        }
    }

    @Test
    fun aChangeInSizeOrTimeIsAlwaysSeen() {
        withTempDir("fp") { dir ->
            val f = dir.resolve("res.xml")
            val old = FileTime.fromMillis(System.currentTimeMillis() - 60_000)
            Files.writeString(f, "aaaa")
            Files.setLastModifiedTime(f, old)
            val first = fingerprint(f)

            Files.writeString(f, "aaaaa")
            Files.setLastModifiedTime(f, old)
            val grown = fingerprint(f)
            assertNotEquals(first, grown)

            Files.writeString(f, "bbbbb")
            Files.setLastModifiedTime(f, FileTime.fromMillis(old.toMillis() + 5_000))
            assertNotEquals(grown, fingerprint(f))
        }
    }

    @Test
    fun unchangedContentKeepsTheSameFingerprint() {
        withTempDir("fp") { dir ->
            val f = dir.resolve("a.txt")
            Files.writeString(f, "same")
            val first = fingerprint(f)
            Files.writeString(f, "same")
            assertEquals(first, fingerprint(f))
        }
    }
}
