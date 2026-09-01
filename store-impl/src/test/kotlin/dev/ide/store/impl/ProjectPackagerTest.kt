package dev.ide.store.impl

import dev.ide.store.StoreResult
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The packager decides what leaves the user's device, so these tests are about the exclusion list far
 * more than about zipping. The keystore cases are the ones that matter: uploading a signing key to a
 * public catalog is unrecoverable, so "no keystore in the archive" is asserted against the archive's real
 * entry list, not against the packager's own report of what it skipped.
 */
class ProjectPackagerTest {

    private val temps = mutableListOf<File>()

    @AfterTest
    fun cleanUp() {
        temps.forEach { it.deleteRecursively() }
    }

    private fun project(): File {
        val root = kotlin.io.path.createTempDirectory("ca-pack-test-").toFile().also { temps += it }
        fun write(path: String, content: String = "x") {
            val f = File(root, path)
            f.parentFile.mkdirs()
            f.writeText(content)
        }
        write("settings.gradle.kts", "include(\":app\")")
        write("app/build.gradle.kts", "plugins { id(\"com.android.application\") }")
        write("app/src/main/kotlin/Main.kt", "fun main() {}")
        write("app/src/main/AndroidManifest.xml", "<manifest/>")
        write("README.md", "# Project")
        // --- things that must NOT be packaged ---
        write("local.properties", "sdk.dir=/Users/someone/Library/Android/sdk")
        write("keystore.properties", "storePassword=hunter2")
        write("release.jks", "BINARY-KEYSTORE")
        write("app/release.keystore", "BINARY-KEYSTORE")
        write("google-services.json", "{}")
        write(".env", "TOKEN=secret")
        write("build/outputs/apk/app-release.apk", "APK")
        write("app/build/tmp/junk.txt", "junk")
        write(".gradle/caches/x.bin", "cache")
        write(".git/HEAD", "ref: refs/heads/main")
        write(".idea/workspace.xml", "<x/>")
        write("app/app.iml", "<module/>")
        // The project model must travel; the index caches beside it must not.
        write(".platform/workspace.json", "{\"version\":1}")
        write(".platform/caches/index/segment.seg", "INDEX-BYTES")
        // A source folder that happens to be named `caches` is not the same thing.
        write("app/src/main/resources/caches/data.txt", "keep me")
        return root
    }

    /**
     * The model travels, its caches do not.
     *
     * Without `.platform/workspace.json` the installed copy is a folder the picker cannot list; with
     * `.platform/caches` it carries the submitter's index — megabytes of it, holding absolute paths from
     * their device — against a 5 MB budget.
     */
    @Test
    fun packagesTheProjectModelButNotItsCaches() {
        val packed = pack(project())
        assertTrue(
            ".platform/workspace.json" in packed.zipEntries,
            "the project model has to be in the archive: ${packed.zipEntries}",
        )
        assertTrue(
            packed.zipEntries.none { it.startsWith(".platform/caches") },
            "the submitter's index caches were packaged: ${packed.zipEntries.filter { it.startsWith(".platform/") }}",
        )
        assertTrue(".platform/caches/" in packed.excluded, "the exclusion should be reported: ${packed.excluded}")
        // Name-based exclusion would have taken this too.
        assertTrue(
            "app/src/main/resources/caches/data.txt" in packed.zipEntries,
            "a source directory named `caches` must be unaffected: ${packed.zipEntries}",
        )
    }

    private fun pack(root: File): PackagedResult {
        val out = File.createTempFile("ca-archive-", ".zip").also { temps += it }
        val r = ProjectPackager().pack(root.absolutePath, out)
        assertTrue(r is StoreResult.Ok, "packaging failed: $r")
        val p = (r as StoreResult.Ok).value
        val entries = ZipFile(File(p.archivePath)).use { z -> z.entries().toList().map { it.name } }
        return PackagedResult(p.files.map { it.path }, p.excluded, entries, p.sha256, p.totalBytes)
    }

    private data class PackagedResult(
        val reported: List<String>,
        val excluded: List<String>,
        val zipEntries: List<String>,
        val sha256: String,
        val totalBytes: Long,
    )

    @Test
    fun packagesSourceAndBuildScripts() {
        val r = pack(project())
        assertContains(r.zipEntries, "settings.gradle.kts")
        assertContains(r.zipEntries, "app/build.gradle.kts")
        assertContains(r.zipEntries, "app/src/main/kotlin/Main.kt")
        assertContains(r.zipEntries, "README.md")
    }

    /** The one that must never regress: no signing material in the actual archive. */
    @Test
    fun noSigningMaterialReachesTheArchive() {
        val r = pack(project())
        val forbidden = listOf("release.jks", "app/release.keystore", "keystore.properties", "local.properties", ".env", "google-services.json")
        forbidden.forEach { path ->
            assertFalse(r.zipEntries.any { it == path }, "SECRET LEAKED INTO ARCHIVE: $path")
        }
        // And nothing with a keystore-ish extension, however it is named.
        assertFalse(r.zipEntries.any { it.endsWith(".jks") || it.endsWith(".keystore") || it.endsWith(".p12") || it.endsWith(".pem") })
    }

    @Test
    fun buildOutputAndLocalStateAreExcluded() {
        val r = pack(project())
        assertFalse(r.zipEntries.any { it.startsWith("build/") }, "build output packaged: ${r.zipEntries}")
        assertFalse(r.zipEntries.any { it.contains("/build/") })
        assertFalse(r.zipEntries.any { it.startsWith(".gradle/") })
        assertFalse(r.zipEntries.any { it.startsWith(".git/") })
        assertFalse(r.zipEntries.any { it.startsWith(".idea/") })
        assertFalse(r.zipEntries.any { it.endsWith(".iml") })
        assertFalse(r.zipEntries.any { it.endsWith(".apk") })
    }

    /** Exclusions are reported so the submit screen can prove the keystore was left out. */
    @Test
    fun exclusionsAreReportedNotSilentlyDropped() {
        val r = pack(project())
        assertContains(r.excluded, "local.properties")
        assertContains(r.excluded, "keystore.properties")
        assertContains(r.excluded, "release.jks")
        assertContains(r.excluded, "build/")
        assertContains(r.excluded, ".git/")
    }

    /** A stable hash is what lets an unchanged re-submission be recognised as unchanged. */
    @Test
    fun sameProjectPacksToTheSameHash() {
        val root = project()
        assertEquals(pack(root).sha256, pack(root).sha256)
    }

    @Test
    fun changedSourceChangesTheHash() {
        val root = project()
        val before = pack(root).sha256
        File(root, "app/src/main/kotlin/Main.kt").writeText("fun main() { println(1) }")
        assertTrue(before != pack(root).sha256, "editing a source file must change the archive hash")
    }

    @Test
    fun hashIsLowercaseHexSha256() {
        val sha = pack(project()).sha256
        assertEquals(64, sha.length, "expected 64 hex chars, got '$sha'")
        assertTrue(sha.all { it in "0123456789abcdef" }, "hash must be lowercase hex: $sha")
    }

    @Test
    fun oversizedProjectIsRejectedWithTheLimitInTheMessage() {
        val root = kotlin.io.path.createTempDirectory("ca-pack-big-").toFile().also { temps += it }
        // Incompressible random bytes, so the zip cannot shrink it under the cap.
        val rnd = java.util.Random(1)
        File(root, "big.bin").writeBytes(ByteArray(64 * 1024).also { rnd.nextBytes(it) })
        val r = ProjectPackager(maxBytes = 16 * 1024).pack(root.absolutePath)
        assertTrue(r is StoreResult.Failed, "expected rejection, got $r")
        assertTrue((r as StoreResult.Failed).message.contains("too large"), r.message)
    }

    @Test
    fun tooManyFilesIsRejected() {
        val root = kotlin.io.path.createTempDirectory("ca-pack-many-").toFile().also { temps += it }
        repeat(12) { File(root, "f$it.kt").writeText("//") }
        val r = ProjectPackager(maxFiles = 5).pack(root.absolutePath)
        assertTrue(r is StoreResult.Failed)
        assertTrue((r as StoreResult.Failed).message.contains("Too many files"), r.message)
    }

    @Test
    fun projectOfNothingButExcludedFilesIsRejected() {
        val root = kotlin.io.path.createTempDirectory("ca-pack-empty-").toFile().also { temps += it }
        File(root, "local.properties").writeText("sdk.dir=/x")
        File(root, "build").mkdirs()
        val r = ProjectPackager().pack(root.absolutePath)
        assertTrue(r is StoreResult.Failed, "expected rejection, got $r")
        assertTrue((r as StoreResult.Failed).message.contains("Nothing to submit"), r.message)
    }

    @Test
    fun missingDirectoryIsAFailureNotAnException() {
        val r = ProjectPackager().pack("/definitely/not/here")
        assertTrue(r is StoreResult.Failed)
    }

    @Test
    fun reportedFileListMatchesTheArchiveContents() {
        val r = pack(project())
        assertEquals(r.reported.sorted(), r.zipEntries.sorted(), "the submit screen's file list must be the truth")
    }

    @Test
    fun archiveSizeMatchesWhatIsReported() {
        val out = File.createTempFile("ca-archive-", ".zip").also { temps += it }
        val p = (ProjectPackager().pack(project().absolutePath, out) as StoreResult.Ok).value
        assertEquals(File(p.archivePath).length(), p.totalBytes)
    }
}
