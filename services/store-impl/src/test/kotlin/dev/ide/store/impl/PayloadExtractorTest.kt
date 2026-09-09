package dev.ide.store.impl

import dev.ide.store.StoreResult
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The extractor unpacks an **untrusted** archive from a public bucket into the user's workspace, so these
 * tests are attacks, not happy paths. Each asserts against the real filesystem afterwards — that nothing
 * escaped, and that a rejected archive left nothing behind.
 */
class PayloadExtractorTest {

    private val temps = mutableListOf<File>()

    @AfterTest
    fun cleanUp() = temps.forEach { it.deleteRecursively() }

    private fun tempDir(prefix: String) =
        kotlin.io.path.createTempDirectory(prefix).toFile().also { temps += it }

    /** Build a zip with arbitrary entry names — including ones a normal zip tool would refuse. */
    private fun zipOf(vararg entries: Pair<String, String>): File {
        val f = File.createTempFile("payload-", ".zip").also { temps += it }
        ZipOutputStream(f.outputStream().buffered()).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return f
    }

    @Test
    fun extractsAWellFormedProject() {
        val parent = tempDir("ext-ok-")
        val zip = zipOf(
            "settings.gradle.kts" to "include(\":app\")",
            "app/src/main/kotlin/Main.kt" to "fun main() {}",
            "README.md" to "# Hi",
        )
        val r = PayloadExtractor().extract(zip, parent, "My Project")
        assertTrue(r is StoreResult.Ok, "expected success, got $r")
        val dir = (r as StoreResult.Ok).value
        assertTrue(File(dir, "settings.gradle.kts").isFile)
        assertTrue(File(dir, "app/src/main/kotlin/Main.kt").isFile)
        assertEquals("my-project", dir.name, "the directory name must be filesystem-safe")
    }

    /** Zip slip via `../`: the classic. Must be refused and must not write outside. */
    @Test
    fun refusesPathTraversalEntries() {
        val parent = tempDir("ext-slip-")
        val canary = File(parent.parentFile, "ESCAPED-${System.nanoTime()}.txt")
        val zip = zipOf(
            "ok.txt" to "fine",
            "../${canary.name}" to "escaped!",
        )
        val r = PayloadExtractor().extract(zip, parent, "evil")
        assertTrue(r is StoreResult.Failed, "traversal must be refused, got $r")
        assertTrue((r as StoreResult.Failed).message.contains("unsafe path"), r.message)
        assertFalse(canary.exists(), "SECURITY: an entry escaped the destination to ${canary.absolutePath}")
    }

    /** Deeper traversal, and one that only escapes after resolving several segments. */
    @Test
    fun refusesDeepAndDisguisedTraversal() {
        val parent = tempDir("ext-slip2-")
        listOf(
            "../../etc-passwd-probe",
            "a/b/../../../outside.txt",
            "./../sneaky.txt",
        ).forEach { name ->
            val r = PayloadExtractor().extract(zipOf(name to "x"), parent, "p")
            assertTrue(r is StoreResult.Failed, "'$name' should be refused, got $r")
        }
    }

    /** A rejected archive must leave no staging directory and no partial project. */
    @Test
    fun rejectedArchiveLeavesNothingBehind() {
        val parent = tempDir("ext-clean-")
        val r = PayloadExtractor().extract(zipOf("../escape" to "x"), parent, "p")
        assertTrue(r is StoreResult.Failed)
        val leftovers = parent.listFiles()?.map { it.name }.orEmpty()
        assertTrue(leftovers.isEmpty(), "staging or partial output left behind: $leftovers")
    }

    /** A zip bomb is tiny on disk; the cap has to be on the UNCOMPRESSED total. */
    @Test
    fun refusesAnArchiveThatUnpacksTooLarge() {
        val parent = tempDir("ext-bomb-")
        // Highly compressible: small archive, large expansion.
        val big = "0".repeat(1_000_000)
        val zip = zipOf("a.txt" to big, "b.txt" to big, "c.txt" to big)
        val r = PayloadExtractor(maxUncompressedBytes = 1_500_000).extract(zip, parent, "bomb")
        assertTrue(r is StoreResult.Failed, "expected rejection, got $r")
        assertTrue((r as StoreResult.Failed).message.contains("unpacks to more than"), r.message)
        assertTrue(parent.listFiles()?.isEmpty() ?: true, "partial extraction left behind")
    }

    @Test
    fun refusesTooManyEntries() {
        val parent = tempDir("ext-many-")
        val entries = (1..20).map { "f$it.txt" to "x" }.toTypedArray()
        val r = PayloadExtractor(maxEntries = 5).extract(zipOf(*entries), parent, "many")
        assertTrue(r is StoreResult.Failed)
        assertTrue((r as StoreResult.Failed).message.contains("too many files"), r.message)
    }

    @Test
    fun refusesAnEmptyArchive() {
        val parent = tempDir("ext-empty-")
        val f = File.createTempFile("empty-", ".zip").also { temps += it }
        ZipOutputStream(f.outputStream()).use { }
        val r = PayloadExtractor().extract(f, parent, "empty")
        assertTrue(r is StoreResult.Failed, "got $r")
    }

    @Test
    fun missingArchiveIsAFailureNotAnException() {
        val r = PayloadExtractor().extract(File("/nope/missing.zip"), tempDir("ext-miss-"), "x")
        assertTrue(r is StoreResult.Failed)
    }

    /** An install must never overwrite an existing project. */
    @Test
    fun suffixesTheDirectoryRatherThanOverwriting() {
        val parent = tempDir("ext-dup-")
        val zip = zipOf("a.txt" to "one")
        val first = (PayloadExtractor().extract(zip, parent, "Same Name") as StoreResult.Ok).value
        val second = (PayloadExtractor().extract(zip, parent, "Same Name") as StoreResult.Ok).value
        assertEquals("same-name", first.name)
        assertEquals("same-name-2", second.name)
        assertTrue(first.isDirectory && second.isDirectory)
    }

    /** The name comes from a catalog row a stranger wrote, so it must be sanitised. */
    @Test
    fun sanitisesHostileProjectNames() {
        assertEquals("etc-passwd", PayloadExtractor.safeName("../../etc/passwd"))
        assertEquals("store-project", PayloadExtractor.safeName("///"))
        assertEquals("store-project", PayloadExtractor.safeName(""))
        assertEquals("my-app-2", PayloadExtractor.safeName("My App 2!"))
        assertTrue(PayloadExtractor.safeName("x".repeat(200)).length <= 48)
        assertTrue(
            PayloadExtractor.safeName("a/b\\c:d").all { it.isLetterOrDigit() || it == '-' || it == '_' },
        )
    }
}
