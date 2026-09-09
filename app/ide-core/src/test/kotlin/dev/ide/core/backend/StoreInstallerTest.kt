package dev.ide.core.backend

import dev.ide.store.RemoteCatalog
import dev.ide.store.RemoteStoreItem
import dev.ide.store.StoreCatalogSource
import dev.ide.store.StoreQuery
import dev.ide.store.StoreResult
import dev.ide.ui.backend.UiInstallProgress
import dev.ide.ui.backend.UiInstallState
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The install path, against real archive bytes.
 *
 * The source is faked (there is no HTTP here) but the zip, the hashing and the extraction are real, which
 * is where the failure modes actually live: a truncated download, a hash that does not match the catalog
 * row, an archive that refuses to unpack. Each of those has to end as a *readable failure* with nothing
 * left in the workspace, not as a half-installed project.
 */
class StoreInstallerTest {

    private val temps = mutableListOf<File>()

    @AfterTest
    fun cleanUp() = temps.forEach { it.deleteRecursively() }

    private fun tempDir(name: String): File =
        kotlin.io.path.createTempDirectory(name).toFile().also { temps += it }

    /** A minimal but real project archive. */
    private fun archiveBytes(entries: Map<String, String> = DEFAULT_ENTRIES): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (path, body) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(body.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun sha256(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    /**
     * A source that serves [bytes] from memory and verifies the hash exactly as the real one does.
     *
     * The verification is duplicated here on purpose: it is the behaviour under test, and pointing the
     * test at `SupabaseStoreSource` would mean standing up an HTTP server to test a hash comparison.
     */
    private class FakeSource(
        private val bytes: ByteArray?,
        private val failWith: StoreResult<Unit>? = null,
    ) : StoreCatalogSource {
        val recorded = mutableListOf<String>()
        var downloads = 0

        override fun configured() = true
        override fun catalog(appBuild: Int) = StoreResult.Unavailable<RemoteCatalog>("n/a")
        override fun search(query: StoreQuery, appBuild: Int) = StoreResult.Unavailable<List<RemoteStoreItem>>("n/a")
        override fun feedDocument(seedSlug: String?) = StoreResult.Unavailable<String>("n/a")

        override fun downloadPayload(
            storagePath: String,
            expectedSha256: String?,
            expectedBytes: Long,
            into: File,
            onProgress: (Float) -> Unit,
        ): StoreResult<Unit> {
            downloads++
            failWith?.let { return it }
            val data = bytes ?: return StoreResult.Failed("No such object")
            onProgress(0.5f)
            into.writeBytes(data)
            onProgress(1f)
            val actual = java.security.MessageDigest.getInstance("SHA-256").digest(data)
                .joinToString("") { "%02x".format(it) }
            if (expectedSha256 != null && !expectedSha256.equals(actual, ignoreCase = true)) {
                into.delete()
                return StoreResult.Failed("The download did not match its checksum")
            }
            return StoreResult.Ok(Unit)
        }

        override fun recordInstall(slug: String, installId: String) {
            recorded += slug
        }
    }

    private fun payload(bytes: ByteArray, sha: String? = null, id: String = "acme-starter") =
        StoreInstaller.Payload(
            itemId = id,
            storagePath = "$id/1.0.0.zip",
            sha256 = sha ?: sha256(bytes),
            sizeBytes = bytes.size.toLong(),
            title = "Acme Starter",
        )

    @Test
    fun unpacksTheProjectAndReportsEveryPhaseInOrder() {
        val bytes = archiveBytes()
        val source = FakeSource(bytes)
        val root = tempDir("ca-install-ok-")
        val seen = mutableListOf<UiInstallProgress>()

        val result = StoreInstaller(source).install(payload(bytes), root, adopt = { null }) { seen += it }

        assertTrue(result.success, "install should succeed: ${result.message}")
        val installed = File(result.rootPath!!)
        assertTrue(installed.isDirectory, "the project directory should exist")
        assertEquals(root.canonicalFile, installed.canonicalFile.parentFile, "it belongs under the projects root")
        assertEquals("include(\":app\")", File(installed, "settings.gradle.kts").readText())
        assertTrue(File(installed, "app/src/main/kotlin/Main.kt").isFile, "nested entries should survive")

        assertEquals(
            listOf(UiInstallState.DOWNLOADING, UiInstallState.IMPORTING, UiInstallState.INSTALLED),
            seen.map { it.state }.distinct(),
            "the phases must arrive in order, with no state skipped",
        )
        assertTrue(seen.all { it.itemId == "acme-starter" }, "every report is keyed to its own item")
        // Progress has to actually move, or the percentage on the button is decoration.
        assertTrue(seen.any { it.state == UiInstallState.DOWNLOADING && it.fraction > 0f })
    }

    /** The whole point of shipping a hash: a substituted archive must not reach the workspace. */
    @Test
    fun refusesAnArchiveWhoseHashDoesNotMatchTheCatalogRow() {
        val bytes = archiveBytes()
        val root = tempDir("ca-install-hash-")
        val seen = mutableListOf<UiInstallProgress>()

        val result = StoreInstaller(FakeSource(bytes))
            .install(payload(bytes, sha = "0".repeat(64)), root, adopt = { null }) { seen += it }

        assertFalse(result.success)
        assertTrue(result.message.contains("checksum"), "the reason should say so: ${result.message}")
        assertEquals(UiInstallState.FAILED, seen.last().state)
        assertEquals(result.message, seen.last().message, "the failure reason has to reach the UI")
        assertEquals(emptyList(), root.listFiles()!!.toList(), "nothing may be left behind: ${root.list()!!.toList()}")
    }

    @Test
    fun aFailedDownloadLeavesNothingInTheWorkspace() {
        val root = tempDir("ca-install-down-")
        val seen = mutableListOf<UiInstallProgress>()
        val result = StoreInstaller(FakeSource(null)).install(payload(archiveBytes()), root, adopt = { null }) { seen += it }

        assertFalse(result.success)
        assertEquals(UiInstallState.FAILED, seen.last().state)
        assertEquals(emptyList(), root.listFiles()!!.toList())
    }

    /** Offline is not a defect, and must not read like one. */
    @Test
    fun offlineFailsWithTheSourcesOwnReasonNotAGenericError() {
        val root = tempDir("ca-install-off-")
        val source = FakeSource(null, failWith = StoreResult.Unavailable("No connection"))
        val seen = mutableListOf<UiInstallProgress>()
        val result = StoreInstaller(source).install(payload(archiveBytes()), root, adopt = { null }) { seen += it }

        assertFalse(result.success)
        assertEquals("No connection", result.message)
        assertEquals("No connection", seen.last().message)
    }

    /** A hostile archive is the extractor's job, but the installer has to end up reporting it. */
    @Test
    fun rejectsAnArchiveThatEscapesItsDirectory() {
        val bytes = archiveBytes(mapOf("../escaped.txt" to "pwned", "ok.txt" to "fine"))
        val root = tempDir("ca-install-slip-")
        val canary = File(root.parentFile, "escaped.txt")
        val seen = mutableListOf<UiInstallProgress>()

        val result = StoreInstaller(FakeSource(bytes)).install(payload(bytes), root, adopt = { null }) { seen += it }

        assertFalse(result.success, "a traversal entry must fail the install")
        assertFalse(canary.exists(), "the entry escaped the projects root: ${canary.absolutePath}")
        assertEquals(UiInstallState.FAILED, seen.last().state)
        assertEquals(emptyList(), root.listFiles()!!.toList())
    }

    /** Installing the same project twice must not merge into or overwrite the first copy. */
    @Test
    fun aSecondInstallGetsItsOwnDirectory() {
        val bytes = archiveBytes()
        val root = tempDir("ca-install-twice-")
        val installer = StoreInstaller(FakeSource(bytes))

        val first = installer.install(payload(bytes), root, adopt = { null }) {}
        val second = installer.install(payload(bytes), root, adopt = { null }) {}

        assertTrue(first.success && second.success)
        assertTrue(first.rootPath != second.rootPath, "the second install overwrote the first: ${first.rootPath}")
        assertEquals(2, root.listFiles()!!.count { it.isDirectory })
    }

    /** The temp archive is an implementation detail and must not accumulate in the temp directory. */
    @Test
    fun theDownloadedArchiveIsDeletedEitherWay() {
        val bytes = archiveBytes()
        val root = tempDir("ca-install-temp-")
        fun zipsInTemp() = File(System.getProperty("java.io.tmpdir"))
            .listFiles { f: File -> f.name.startsWith("ca-store-") && f.name.endsWith(".zip") }
            ?.size ?: 0

        val before = zipsInTemp()
        StoreInstaller(FakeSource(bytes)).install(payload(bytes), root, adopt = { null }) {}
        StoreInstaller(FakeSource(bytes)).install(payload(bytes, sha = "1".repeat(64)), root, adopt = { null }) {}
        assertEquals(before, zipsInTemp(), "a temp archive was left behind")
    }

    /**
     * An archive that unpacks but is not a project must not leave a folder in the workspace.
     *
     * `ProjectManager.list()` only shows directories with a model, so a folder left here would be an entry
     * the user can neither open nor delete from the app.
     */
    @Test
    fun anArchiveThatIsNotAProjectIsRemovedAgain() {
        val bytes = archiveBytes(mapOf("notes.txt" to "just some files"))
        val root = tempDir("ca-install-notaproject-")
        val seen = mutableListOf<UiInstallProgress>()

        val result = StoreInstaller(FakeSource(bytes))
            .install(payload(bytes), root, adopt = { "That download isn't a project CodeAssist can open" }) { seen += it }

        assertFalse(result.success)
        assertTrue(result.message.contains("isn't a project"), result.message)
        assertEquals(UiInstallState.FAILED, seen.last().state)
        assertEquals(emptyList(), root.listFiles()!!.toList(), "the unpacked folder should be gone")
    }

    /** The adopt step runs on the unpacked directory, not on the archive or the projects root. */
    @Test
    fun adoptSeesTheUnpackedProjectDirectory() {
        val bytes = archiveBytes()
        val root = tempDir("ca-install-adopt-")
        var adopted: File? = null

        val result = StoreInstaller(FakeSource(bytes)).install(
            payload(bytes),
            root,
            adopt = { dir -> adopted = dir; null },
        ) {}

        assertTrue(result.success)
        val dir = adopted
        assertTrue(dir != null && dir.isDirectory, "adopt should receive the unpacked directory")
        assertEquals(root.canonicalFile, dir!!.canonicalFile.parentFile)
        assertTrue(File(dir, "settings.gradle.kts").isFile, "the files should be in place before adopting")
        assertEquals(dir.absolutePath, result.rootPath)
    }

    private companion object {
        val DEFAULT_ENTRIES = mapOf(
            "settings.gradle.kts" to "include(\":app\")",
            "app/src/main/kotlin/Main.kt" to "fun main() = println(\"hi\")",
            "README.md" to "# Acme Starter",
        )
    }
}
