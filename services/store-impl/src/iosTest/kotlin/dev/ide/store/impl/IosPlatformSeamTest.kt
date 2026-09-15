package dev.ide.store.impl

import dev.ide.store.StoreResult
import dev.ide.store.impl.platform.StoreFs
import dev.ide.store.impl.platform.joinPath
import dev.ide.store.impl.platform.openZip
import dev.ide.store.impl.platform.sha256Hex
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The three things iOS supplies itself and the JVM got from its standard library: SHA-256, a filesystem,
 * and a ZIP reader.
 *
 * Everything above them — which RPC, what JSON, which status means offline — is shared code the JVM suite
 * already covers, so it is not re-asserted here. What is worth proving on the simulator is that a payload
 * downloaded on a phone unpacks to the same bytes it would on a desktop, because that is the one place
 * where iOS runs a different implementation of the same idea, and it is the implementation standing
 * between an untrusted archive and the user's workspace.
 *
 * The archives are built here rather than committed, with a hand-written writer: iOS has no zip writer
 * (which is exactly why publishing is unavailable there), so a fixture the reader is checked against
 * cannot be produced by the platform it runs on.
 */
class IosPlatformSeamTest {

    private val scratch = StoreFs.tempPath("ca-store-seam-", "")

    @AfterTest
    fun cleanUp() {
        StoreFs.deleteRecursively(scratch)
    }

    // ---- SHA-256 -------------------------------------------------------------------------------------

    /** The published test vectors, so this is checked against the standard rather than against itself. */
    @Test
    fun sha256MatchesTheKnownVectors() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            sha256Hex(ByteArray(0)),
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256Hex("abc".encodeToByteArray()),
        )
    }

    /** A payload is verified by comparing hex, so the case and width of every byte matters. */
    @Test
    fun sha256IsLowerCaseHexWithLeadingZeroesKept() {
        val digest = sha256Hex(byteArrayOf(0, 1, 2, 3))
        assertEquals(64, digest.length)
        assertEquals(digest.lowercase(), digest)
    }

    // ---- filesystem ----------------------------------------------------------------------------------

    @Test
    fun writesReadsAndDeletesThroughTheSeam() {
        StoreFs.mkdirs(scratch)
        val file = joinPath(scratch, "nested/deep/a.txt")
        StoreFs.mkdirsForFile(file)
        assertTrue(StoreFs.writeText(file, "hello"))
        assertEquals("hello", StoreFs.readText(file))
        assertEquals(5, StoreFs.size(file))
        assertTrue(StoreFs.isFile(file))
        assertFalse(StoreFs.isDirectory(file))
        assertTrue(StoreFs.isDirectory(joinPath(scratch, "nested/deep")))
        assertContains(StoreFs.list(joinPath(scratch, "nested/deep")), "a.txt")
        assertTrue(StoreFs.delete(file))
        assertFalse(StoreFs.exists(file))
    }

    /**
     * The extractor asks for the canonical path of a file it has NOT created yet — that is the whole
     * point of checking containment before writing — so a resolver that only worked on existing paths
     * would silently answer with the unresolved input and let a `..` through.
     */
    @Test
    fun canonicalPathFoldsDotDotForAPathThatDoesNotExistYet() {
        StoreFs.mkdirs(scratch)
        val real = StoreFs.canonicalPath(scratch)
        assertEquals(real, StoreFs.canonicalPath(joinPath(scratch, "sub/..")))
        assertEquals(joinPath(real, "a"), StoreFs.canonicalPath(joinPath(scratch, "sub/../a")))
        assertFalse(StoreFs.canonicalPath(joinPath(scratch, "../escape")).startsWith("$real/"))
    }

    // ---- zip -----------------------------------------------------------------------------------------

    /** Stored entries: no compression, so this isolates the directory parsing from the inflater. */
    @Test
    fun readsStoredEntries() {
        val archive = writeZip(
            "settings.gradle.kts" to "include(\":app\")",
            "app/src/main/kotlin/Main.kt" to "fun main() {}",
        )
        val zip = assertNotNull(openZip(archive), "the archive should open")
        val names = zip.entries().map { it.name }
        assertEquals(listOf("settings.gradle.kts", "app/src/main/kotlin/Main.kt"), names)

        val out = joinPath(scratch, "out.txt")
        val entry = zip.entries().first()
        assertEquals(15, zip.extractTo(entry, out))
        assertEquals("include(\":app\")", StoreFs.readText(out))
        zip.close()
    }

    /**
     * A directory entry has no flag of its own in the format; the trailing separator is the only signal,
     * and the extractor branches on it before it tries to write a file.
     */
    @Test
    fun marksDirectoryEntries() {
        val archive = writeZip("app/" to "", "app/a.txt" to "x")
        val zip = assertNotNull(openZip(archive))
        assertEquals(listOf(true, false), zip.entries().map { it.isDirectory })
        zip.close()
    }

    @Test
    fun refusesSomethingThatIsNotAnArchive() {
        StoreFs.mkdirs(scratch)
        val notAZip = joinPath(scratch, "nope.zip")
        StoreFs.writeText(notAZip, "this is not a zip file, it is a sentence")
        assertNull(openZip(notAZip))
        assertNull(openZip(joinPath(scratch, "missing.zip")))
    }

    /**
     * The whole install path end to end on the platform that has no zip library: build an archive, hash
     * it the way the catalog row does, and unpack it through the same [PayloadExtractor] the JVM runs.
     */
    @Test
    fun extractsAProjectAndKeepsItInsideTheDestination() {
        val archive = writeZip(
            "settings.gradle.kts" to "include(\":app\")",
            "app/src/main/kotlin/Main.kt" to "fun main() {}",
            "README.md" to "# Hi",
        )
        val parent = joinPath(scratch, "projects")
        StoreFs.mkdirs(parent)

        val result = PayloadExtractor().extract(archive, parent, "My Project")
        assertTrue(result is StoreResult.Ok, "extract should succeed: $result")
        val dir = result.value
        assertEquals(joinPath(parent, "my-project"), dir, "the directory name must be filesystem-safe")
        assertEquals("# Hi", StoreFs.readText(joinPath(dir, "README.md")))
        assertEquals("fun main() {}", StoreFs.readText(joinPath(dir, "app/src/main/kotlin/Main.kt")))

        // A second install of the same project must not overwrite the first.
        val again = PayloadExtractor().extract(archive, parent, "My Project")
        assertEquals(joinPath(parent, "my-project-2"), (again as StoreResult.Ok).value)
    }

    /** Zip slip. The archive is untrusted, and this is the check that makes unpacking it safe. */
    @Test
    fun refusesAnEntryThatEscapesTheDestination() {
        val archive = writeZip("../escape.txt" to "owned")
        val parent = joinPath(scratch, "projects")
        StoreFs.mkdirs(parent)

        val result = PayloadExtractor().extract(archive, parent, "evil")
        assertTrue(result is StoreResult.Failed, "path traversal must be refused: $result")
        assertFalse(StoreFs.exists(joinPath(scratch, "escape.txt")), "nothing may be written outside")
    }

    /** Host state from the submitter's device is dropped on the way in, on every platform. */
    @Test
    fun stripsHostStateOnInstall() {
        val archive = writeZip(
            ".platform/workspace.json" to "{}",
            ".platform/libraries.json" to "{\"libraries\":[]}",
            ".platform/.deps-reconciled" to "v=4",
        )
        val parent = joinPath(scratch, "projects")
        StoreFs.mkdirs(parent)

        val dir = (PayloadExtractor().extract(archive, parent, "stripped") as StoreResult.Ok).value
        assertTrue(StoreFs.exists(joinPath(dir, ".platform/workspace.json")), "the model must survive")
        for (stripped in PayloadExtractor.STRIPPED_ON_INSTALL) {
            assertFalse(StoreFs.exists(joinPath(dir, stripped)), "$stripped must not be installed")
        }
    }

    // ---- a zip writer, for the fixtures ---------------------------------------------------------------

    /**
     * Writes a STORED (uncompressed) zip: local headers, a central directory and an end record.
     *
     * Uncompressed on purpose — the deflate half of the reader is exercised by
     * [readsADeflatedEntryWrittenElsewhere], against bytes produced by a real compressor, because a
     * fixture written by the same hand that wrote the reader proves only that the two agree.
     */
    private fun writeZip(vararg entries: Pair<String, String>): String {
        StoreFs.mkdirs(scratch)
        val out = ArrayList<Byte>()
        val directory = ArrayList<Byte>()
        var offset = 0

        for ((name, content) in entries) {
            val nameBytes = name.encodeToByteArray()
            val data = content.encodeToByteArray()
            val crc = crc32(data)
            val local = ArrayList<Byte>()
            local.int(0x04034b50); local.short(20); local.short(0); local.short(0)
            local.short(0); local.short(0)
            local.int(crc); local.int(data.size); local.int(data.size)
            local.short(nameBytes.size); local.short(0)
            local.addAll(nameBytes.toList())
            local.addAll(data.toList())

            val central = ArrayList<Byte>()
            central.int(0x02014b50); central.short(20); central.short(20); central.short(0); central.short(0)
            central.short(0); central.short(0)
            central.int(crc); central.int(data.size); central.int(data.size)
            central.short(nameBytes.size); central.short(0); central.short(0)
            central.short(0); central.short(0); central.int(0)
            central.int(offset)
            central.addAll(nameBytes.toList())

            offset += local.size
            out.addAll(local)
            directory.addAll(central)
        }

        val directoryOffset = out.size
        out.addAll(directory)
        out.int(0x06054b50); out.short(0); out.short(0)
        out.short(entries.size); out.short(entries.size)
        out.int(directory.size); out.int(directoryOffset); out.short(0)

        val path = joinPath(scratch, "archive-${entries.hashCode()}.zip")
        StoreFs.writeBytes(path, out.toByteArray())
        return path
    }

    /**
     * A deflated entry, from an archive a real compressor produced.
     *
     * This is the one fixture that has to be committed as bytes: the reader's deflate path is the half
     * with no reference implementation to hand on iOS, so it is checked against output that was NOT
     * written by this file. The base64 is a two-entry zip built by `java.util.zip` holding
     * `a.txt` = 400 repetitions of "compress me " and `b.txt` = "second".
     */
    @Test
    fun readsADeflatedEntryWrittenElsewhere() {
        StoreFs.mkdirs(scratch)
        val path = joinPath(scratch, "deflated.zip")
        StoreFs.writeBytes(path, decodeBase64(DEFLATED_ZIP_BASE64))

        val zip = assertNotNull(openZip(path), "the deflated archive should open")
        val entries = zip.entries()
        assertEquals(listOf("a.txt", "b.txt"), entries.map { it.name })

        val out = joinPath(scratch, "a.txt")
        assertEquals(4800, zip.extractTo(entries[0], out), "the inflated size must match the directory")
        assertEquals("compress me ".repeat(400), StoreFs.readText(out))

        val second = joinPath(scratch, "b.txt")
        assertEquals(6, zip.extractTo(entries[1], second))
        assertEquals("second", StoreFs.readText(second))
        zip.close()
    }

    private fun ArrayList<Byte>.int(value: Int) {
        add((value and 0xFF).toByte())
        add(((value shr 8) and 0xFF).toByte())
        add(((value shr 16) and 0xFF).toByte())
        add(((value shr 24) and 0xFF).toByte())
    }

    private fun ArrayList<Byte>.short(value: Int) {
        add((value and 0xFF).toByte())
        add(((value shr 8) and 0xFF).toByte())
    }

    private fun crc32(data: ByteArray): Int {
        var crc = 0xFFFFFFFFu
        for (byte in data) {
            crc = crc xor (byte.toUInt() and 0xFFu)
            repeat(8) {
                crc = if (crc and 1u != 0u) (crc shr 1) xor 0xEDB88320u else crc shr 1
            }
        }
        return (crc xor 0xFFFFFFFFu).toInt()
    }

    private fun decodeBase64(text: String): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val out = ArrayList<Byte>(text.length * 3 / 4)
        var buffer = 0
        var bits = 0
        for (c in text) {
            if (c == '=' || c == '\n') continue
            val v = alphabet.indexOf(c)
            if (v < 0) continue
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.add(((buffer shr bits) and 0xFF).toByte())
            }
        }
        return out.toByteArray()
    }

    private companion object {
        const val DEFLATED_ZIP_BASE64 =
            "UEsDBBQAAAAIAIJ+L124REV5KAAAAMASAAAFAAAAYS50eHTtxrEJADAIALBX/E0cpaX+D77RIZmS" +
            "p++rmeiKdHd3d3d3d3d3/+wLUEsDBBQAAAAIAIJ+L11pER+2CAAAAAYAAAAFAAAAYi50eHQrTk3O" +
            "z0sBAFBLAQIUAxQAAAAIAIJ+L124REV5KAAAAMASAAAFAAAAAAAAAAAAAACAAQAAAABhLnR4dFBL" +
            "AQIUAxQAAAAIAIJ+L11pER+2CAAAAAYAAAAFAAAAAAAAAAAAAACAAUsAAABiLnR4dFBLBQYAAAAA" +
            "AgACAGYAAAB2AAAAAAA="
    }
}
