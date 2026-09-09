package dev.ide.core.backend

import dev.ide.core.IdeServices
import dev.ide.core.ProjectManager
import dev.ide.store.RemoteCatalog
import dev.ide.store.RemoteStoreItem
import dev.ide.store.StoreCatalogSource
import dev.ide.store.StoreQuery
import dev.ide.store.StoreResult
import dev.ide.store.impl.ProjectPackager
import dev.ide.ui.backend.UiInstallProgress
import dev.ide.ui.backend.UiInstallState
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Submit → install, on a real project.
 *
 * The two halves of the store were built and tested separately, and separately they both passed while
 * being unable to hand anything to each other: a packaged project that installs into a folder the picker
 * cannot list is not an install. So this goes the whole way round — seed a genuine project, package it the
 * way a submission does, serve those exact bytes back, install them, and require the result to appear in
 * [ProjectManager.list].
 */
class StoreRoundTripTest {

    private val homeEnv = dev.ide.testkit.TestEnv("ide-store-roundtrip")
    private val temps = mutableListOf<File>()

    @AfterTest
    fun cleanUp() {
        temps.forEach { it.deleteRecursively() }
        homeEnv.close()
    }

    /** Serves one archive from disk, hashing it exactly as the real source does. */
    private class ArchiveSource(private val archive: File) : StoreCatalogSource {
        override fun configured() = true
        override fun catalog(appBuild: Int) = StoreResult.Unavailable<RemoteCatalog>("n/a")
        override fun search(query: StoreQuery, appBuild: Int) = StoreResult.Unavailable<List<RemoteStoreItem>>("n/a")
        override fun feedDocument(seedSlug: String?) = StoreResult.Unavailable<String>("n/a")
        override fun recordInstall(slug: String, installId: String) = Unit

        override fun downloadPayload(
            storagePath: String,
            expectedSha256: String?,
            expectedBytes: Long,
            into: File,
            onProgress: (Float) -> Unit,
        ): StoreResult<Unit> {
            archive.copyTo(into, overwrite = true)
            onProgress(1f)
            val actual = into.inputStream().use { stream ->
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(8192)
                while (true) {
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            }
            if (expectedSha256 != null && !expectedSha256.equals(actual, ignoreCase = true)) {
                into.delete()
                return StoreResult.Failed("The download did not match its checksum")
            }
            return StoreResult.Ok(Unit)
        }
    }

    @Test
    fun aPackagedProjectInstallsAndAppearsInTheProjectList() {
        // 1. A real project, seeded the way first launch seeds the demo.
        val source = kotlin.io.path.createTempDirectory("ca-rt-src-").toFile().also { temps += it }
        val projectDir = File(source, "android-sample")
        IdeServices.seedDemo(projectDir.toPath())
        assertTrue(File(projectDir, ".platform/workspace.json").isFile, "seedDemo should write a model")

        // 2. Packaged exactly as a submission would package it.
        val packed = ProjectPackager().pack(projectDir.absolutePath)
        assertTrue(packed is StoreResult.Ok, "packing a seeded project failed: $packed")
        val archive = File((packed as StoreResult.Ok).value.archivePath).also { temps += it }
        assertTrue(
            packed.value.files.any { it.path == ".platform/workspace.json" },
            "the model has to travel with the archive, or the installed copy is not a project: " +
                packed.value.files.take(8).map { it.path },
        )

        // 3. Installed into a fresh workspace.
        val manager = ProjectManager.desktop(homeEnv.dir.resolve("projects"))
        val projectsRoot = homeEnv.dir.resolve("projects").toFile()
        projectsRoot.mkdirs()
        val seen = mutableListOf<UiInstallProgress>()
        val result = StoreInstaller(ArchiveSource(archive)).install(
            payload = StoreInstaller.Payload(
                itemId = "android-sample",
                storagePath = "android-sample/1.0.0.zip",
                sha256 = packed.value.sha256,
                sizeBytes = packed.value.totalBytes,
                title = "Android Sample",
            ),
            projectsRoot = projectsRoot,
            adopt = { dir ->
                if (manager.adoptProjectInPlace(dir.toPath())) null else "not a project"
            },
        ) { seen += it }

        assertTrue(result.success, "install failed: ${result.message}")
        assertEquals(UiInstallState.INSTALLED, seen.last().state)

        // 4. And the picker can actually see it. This is the assertion the two halves were failing.
        val listed = manager.list()
        assertTrue(
            listed.any { it.rootPath == result.rootPath },
            "the installed project is not in the project list: ${listed.map { it.rootPath }} vs ${result.rootPath}",
        )
    }

    /** A tampered archive must never reach the workspace, even one that is otherwise a valid project. */
    @Test
    fun aTamperedArchiveOfARealProjectIsStillRefused() {
        val source = kotlin.io.path.createTempDirectory("ca-rt-tamper-").toFile().also { temps += it }
        val projectDir = File(source, "android-sample")
        IdeServices.seedDemo(projectDir.toPath())
        val packed = ProjectPackager().pack(projectDir.absolutePath) as StoreResult.Ok
        val archive = File(packed.value.archivePath).also { temps += it }

        val manager = ProjectManager.desktop(homeEnv.dir.resolve("projects"))
        val projectsRoot = homeEnv.dir.resolve("projects").toFile()
        projectsRoot.mkdirs()

        val result = StoreInstaller(ArchiveSource(archive)).install(
            payload = StoreInstaller.Payload(
                itemId = "android-sample",
                storagePath = "android-sample/1.0.0.zip",
                // The catalog row says something else: the bytes are not what was approved.
                sha256 = "f".repeat(64),
                sizeBytes = packed.value.totalBytes,
                title = "Android Sample",
            ),
            projectsRoot = projectsRoot,
            adopt = { error("adopt must not be reached for an archive that failed verification") },
        ) {}

        assertFalse(result.success)
        assertEquals(emptyList(), manager.list(), "nothing may be installed: ${manager.list()}")
        assertEquals(emptyList(), projectsRoot.listFiles()!!.toList())
    }
}
