package dev.ide.store.bridge

import dev.ide.store.RemoteCatalog
import dev.ide.store.RemoteItemKind
import dev.ide.store.RemoteStoreItem
import dev.ide.store.StoreCatalogSource
import dev.ide.store.StoreQuery
import dev.ide.store.StoreResult
import dev.ide.ui.backend.UiStoreItem
import dev.ide.ui.backend.UiStoreItemKind
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Installed project -> publisher ships a new version -> what happens to the copy on this device.
 *
 * Real zips, real extraction, real files on disk; only the store's HTTP is faked. The rule under test is the
 * one the user cares about: an untouched copy updates in place, and a copy with the user's work in it is
 * never written over.
 */
class StoreUpdatesTest {

    private val temps = mutableListOf<File>()

    @AfterTest
    fun cleanUp() = temps.forEach { it.deleteRecursively() }

    private fun tempDir(name: String): File = kotlin.io.path.createTempDirectory(name).toFile().also { temps += it }

    private fun zip(entries: Map<String, String>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            entries.forEach { (path, body) -> z.putNextEntry(ZipEntry(path)); z.write(body.toByteArray()); z.closeEntry() }
        }
        return out.toByteArray()
    }

    private fun sha256(bytes: ByteArray) =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Serves archives by storage path, and answers a slug search with [current]. */
    private class FakeStore : StoreCatalogSource {
        val objects = mutableMapOf<String, ByteArray>()
        var current: RemoteStoreItem? = null
        var failDownloads = false

        override fun configured() = true
        override fun catalog(appBuild: Int) = StoreResult.Unavailable<RemoteCatalog>("n/a")
        override fun feedDocument(seedSlug: String?) = StoreResult.Unavailable<String>("n/a")
        override fun search(query: StoreQuery, appBuild: Int): StoreResult<List<RemoteStoreItem>> =
            StoreResult.Ok(listOfNotNull(current?.takeIf { it.id == query.text }))
        override fun downloadPayload(
            storagePath: String,
            expectedSha256: String?,
            expectedBytes: Long,
            intoPath: String,
            onProgress: (Float) -> Unit,
        ): StoreResult<Unit> {
            if (failDownloads) return StoreResult.Unavailable("offline")
            val bytes = objects[storagePath] ?: return StoreResult.Failed("No such object")
            File(intoPath).writeBytes(bytes)
            return StoreResult.Ok(Unit)
        }
        override fun recordInstall(slug: String, installId: String) = Unit
    }

    private val store = FakeStore()
    private val baselineDir = tempDir("baselines")
    private val updates = StoreUpdates(
        source = store,
        baselines = StoreBaselines { baselineDir.absolutePath },
        updater = StoreUpdater(store),
        appBuild = { 0 },
        toUi = { UiStoreItem(id = it.id, kind = UiStoreItemKind.Community, title = it.title, summary = "", category = "") },
    )

    private fun publish(version: String, code: Int, files: Map<String, String>): RemoteStoreItem {
        val bytes = zip(files)
        val path = "notes/$version.zip"
        store.objects[path] = bytes
        val row = RemoteStoreItem(
            id = "notes", kind = RemoteItemKind.COMMUNITY, title = "Notes", summary = "", category = "kotlin",
            version = version, versionCode = code, storagePath = path, sizeBytes = bytes.size.toLong(),
            sha256 = sha256(bytes),
        )
        store.current = row
        return row
    }

    private val v1 = mapOf(
        "app/src/main/kotlin/Main.kt" to "fun main() = println(\"v1\")",
        "app/src/main/kotlin/Old.kt" to "// removed in v2",
        "README.md" to "Notes v1",
        ".platform/workspace.toml" to "name = \"Notes\"",
    )
    private val v2 = mapOf(
        "app/src/main/kotlin/Main.kt" to "fun main() = println(\"v2\")",
        "app/src/main/kotlin/New.kt" to "// added in v2",
        "README.md" to "Notes v2",
        ".platform/workspace.toml" to "name = \"Notes\"\nversion = 2",
    )

    /** Install v1 the way the app does, and record its baseline. Returns the project folder. */
    private fun installV1(): File {
        val row = publish("1.0.0", 1, v1)
        val workspace = tempDir("workspace")
        val result = StoreInstaller(store).install(
            StoreInstaller.Payload.of(row)!!, workspace.absolutePath, adopt = { null }, onProgress = {},
            keepArchiveAt = updates.archivePath("notes"),
        )
        assertTrue(result.success, result.message)
        val root = result.rootPath!!
        updates.recordInstall(StoreInstaller.Payload.of(row)!!, root)
        return File(root)
    }

    @Test
    fun anUntouchedCopyUpdatesInPlaceAndKeepsWhatTheStoreNeverInstalled() {
        val root = installV1()
        // Things the store did not put there: a build, IDE state, a file of the user's own.
        File(root, "app/build/outputs/app.apk").apply { parentFile.mkdirs(); writeText("apk") }
        File(root, ".platform/open-tabs.txt").writeText("Main.kt")
        File(root, "app/src/main/kotlin/Mine.kt").writeText("// the user's own file")

        publish("1.1.0", 2, v2)
        val update = assertNotNull(updates.check("notes", root.absolutePath))
        assertEquals("1.0.0", update.installedVersion)
        assertEquals("1.1.0", update.availableVersion)
        assertFalse(updates.localChanges("notes", root.absolutePath).blocksInPlace)

        val result = updates.applyInPlace("notes", root.absolutePath) {}
        assertTrue(result.success, result.message)

        assertEquals("fun main() = println(\"v2\")", File(root, "app/src/main/kotlin/Main.kt").readText())
        assertEquals("// added in v2", File(root, "app/src/main/kotlin/New.kt").readText())
        assertFalse(File(root, "app/src/main/kotlin/Old.kt").exists(), "a file the new version dropped stays")
        assertEquals("Notes v2", File(root, "README.md").readText())
        assertEquals("name = \"Notes\"\nversion = 2", File(root, ".platform/workspace.toml").readText())
        // Left alone.
        assertEquals("apk", File(root, "app/build/outputs/app.apk").readText())
        assertEquals("Main.kt", File(root, ".platform/open-tabs.txt").readText())
        assertEquals("// the user's own file", File(root, "app/src/main/kotlin/Mine.kt").readText())
        // And now it is current: no update offered, and the new baseline describes v2.
        assertNull(updates.check("notes", root.absolutePath))
        assertFalse(updates.localChanges("notes", root.absolutePath).blocksInPlace)
    }

    @Test
    fun anEditedCopyIsNeverWrittenOver() {
        val root = installV1()
        File(root, "app/src/main/kotlin/Main.kt").writeText("fun main() = println(\"my change\")")
        File(root, "README.md").delete()

        publish("1.1.0", 2, v2)
        val changes = updates.localChanges("notes", root.absolutePath)
        assertEquals(listOf("app/src/main/kotlin/Main.kt"), changes.modified)
        assertEquals(listOf("README.md"), changes.deleted)
        assertTrue(changes.blocksInPlace)

        val result = updates.applyInPlace("notes", root.absolutePath) {}
        assertFalse(result.success)
        assertEquals("fun main() = println(\"my change\")", File(root, "app/src/main/kotlin/Main.kt").readText())
        assertTrue(File(root, "app/src/main/kotlin/Old.kt").exists())
    }

    @Test
    fun theIdeWritingItsOwnStateIsNotAnEdit() {
        val root = installV1()
        File(root, ".platform/workspace.toml").writeText("name = \"Notes\"\n# re-saved by the IDE")
        File(root, ".platform/libraries.json").writeText("{}")
        assertFalse(updates.localChanges("notes", root.absolutePath).blocksInPlace)
    }

    @Test
    fun aUserFileWhereTheNewVersionPutsOneIsNotOverwritten() {
        val root = installV1()
        File(root, "app/src/main/kotlin/New.kt").writeText("// the user got there first")
        publish("1.1.0", 2, v2)

        val result = updates.applyInPlace("notes", root.absolutePath) {}
        assertFalse(result.success)
        assertTrue(result.message.contains("New.kt"), result.message)
        assertEquals("// the user got there first", File(root, "app/src/main/kotlin/New.kt").readText())
        assertEquals("fun main() = println(\"v1\")", File(root, "app/src/main/kotlin/Main.kt").readText())
    }

    @Test
    fun aFailedDownloadLeavesTheProjectAsItWas() {
        val root = installV1()
        publish("1.1.0", 2, v2)
        store.failDownloads = true
        assertFalse(updates.applyInPlace("notes", root.absolutePath) {}.success)
        assertEquals("fun main() = println(\"v1\")", File(root, "app/src/main/kotlin/Main.kt").readText())
        assertTrue(File(root, "app/src/main/kotlin/Old.kt").exists())
    }

    @Test
    fun theSameVersionIsNotAnUpdate() {
        val root = installV1()
        assertNull(updates.check("notes", root.absolutePath))
    }

    @Test
    fun anInstallFromBeforeBaselinesIsNeverUpdatedInPlace() {
        val row = publish("1.0.0", 1, v1)
        val workspace = tempDir("legacy")
        val root = StoreInstaller(store).install(StoreInstaller.Payload.of(row)!!, workspace.absolutePath, adopt = { null }, onProgress = {}).rootPath!!
        publish("1.1.0", 2, v2)
        // No baseline, so its version is unknown: no update is claimed, and in place is refused.
        assertNull(updates.check("notes", root))
        assertFalse(updates.localChanges("notes", root).known)
        assertFalse(updates.applyInPlace("notes", root) {}.success)
    }

    // ---- merging an edited copy -------------------------------------------------------------------------

    private val mergeV1 = mapOf(
        "app/src/main/kotlin/Main.kt" to "fun main() {\n    greet(\"world\")\n}\n\nfun greet(name: String) {\n    println(\"Hello, \$name\")\n}\n",
        "app/src/main/kotlin/Theme.kt" to "val primary = 0xFF6200EE\nval secondary = 0xFF03DAC5\n",
        "app/src/main/kotlin/Old.kt" to "// removed in v2\n",
        "README.md" to "Notes v1\n",
        ".platform/workspace.toml" to "name = \"Notes\"\n",
    )
    private val mergeV2 = mapOf(
        // The publisher added a call in main; the user will change the greeting: different lines.
        "app/src/main/kotlin/Main.kt" to "fun main() {\n    greet(\"world\")\n    greet(\"store\")\n}\n\nfun greet(name: String) {\n    println(\"Hello, \$name\")\n}\n",
        // Both will change the primary colour: the same line.
        "app/src/main/kotlin/Theme.kt" to "val primary = 0xFF3F51B5\nval secondary = 0xFF03DAC5\n",
        "app/src/main/kotlin/New.kt" to "// added in v2\n",
        "README.md" to "Notes v2\n",
        ".platform/workspace.toml" to "name = \"Notes\"\nversion = 2\n",
    )

    private fun installMergeV1(keepArchive: Boolean = true): File {
        val row = publish("1.0.0", 1, mergeV1)
        val workspace = tempDir("merge")
        val root = StoreInstaller(store).install(
            StoreInstaller.Payload.of(row)!!, workspace.absolutePath, adopt = { null },
            keepArchiveAt = if (keepArchive) updates.archivePath("notes") else null, onProgress = {},
        ).rootPath!!
        updates.recordInstall(StoreInstaller.Payload.of(row)!!, root)
        return File(root)
    }

    @Test
    fun aMergeKeepsTheUsersEditsAndTheNewVersions() {
        val root = installMergeV1()
        File(root, "app/src/main/kotlin/Main.kt").writeText(mergeV1.getValue("app/src/main/kotlin/Main.kt").replace("Hello, ", "Hi, "))
        File(root, "app/src/main/kotlin/Theme.kt").writeText("val primary = 0xFFE91E63\nval secondary = 0xFF03DAC5\n")
        File(root, "app/src/main/kotlin/Old.kt").writeText("// I still use this\n")
        File(root, "app/src/main/kotlin/Mine.kt").writeText("// the user's own file\n")
        publish("1.1.0", 2, mergeV2)

        val changes = updates.localChanges("notes", root.absolutePath)
        assertTrue(changes.canMerge)
        val result = updates.merge("notes", root.absolutePath) {}
        assertTrue(result.success, result.message)

        // Different lines: both kept, no markers.
        assertEquals(
            "fun main() {\n    greet(\"world\")\n    greet(\"store\")\n}\n\nfun greet(name: String) {\n    println(\"Hi, \$name\")\n}\n",
            File(root, "app/src/main/kotlin/Main.kt").readText(),
        )
        // The same line: both kept, between markers, and listed.
        assertEquals(
            "<<<<<<< Your version\nval primary = 0xFFE91E63\n=======\nval primary = 0xFF3F51B5\n>>>>>>> New version (1.1.0)\nval secondary = 0xFF03DAC5\n",
            File(root, "app/src/main/kotlin/Theme.kt").readText(),
        )
        // Removed by the publisher, changed by the user: the user's file stays, and is listed.
        assertEquals("// I still use this\n", File(root, "app/src/main/kotlin/Old.kt").readText())
        assertEquals(listOf("app/src/main/kotlin/Old.kt", "app/src/main/kotlin/Theme.kt"), result.conflicts.sorted())
        // Only the publisher changed these.
        assertEquals("Notes v2\n", File(root, "README.md").readText())
        assertEquals("// added in v2\n", File(root, "app/src/main/kotlin/New.kt").readText())
        assertEquals("name = \"Notes\"\nversion = 2\n", File(root, ".platform/workspace.toml").readText())
        // Only the user's.
        assertEquals("// the user's own file\n", File(root, "app/src/main/kotlin/Mine.kt").readText())

        // Now current, and the user's edits are measured against the new version, not the old one.
        assertNull(updates.check("notes", root.absolutePath))
        val after = updates.localChanges("notes", root.absolutePath)
        assertTrue("app/src/main/kotlin/Main.kt" in after.modified)
        assertFalse("README.md" in after.modified)
    }

    @Test
    fun anInstallWithoutItsOriginalFilesCannotBeMerged() {
        val root = installMergeV1(keepArchive = false)
        File(root, "README.md").writeText("mine\n")
        publish("1.1.0", 2, mergeV2)
        assertFalse(updates.localChanges("notes", root.absolutePath).canMerge)
        val result = updates.merge("notes", root.absolutePath) {}
        assertFalse(result.success)
        assertEquals("mine\n", File(root, "README.md").readText())
    }
}
