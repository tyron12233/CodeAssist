package dev.ide.core

import dev.ide.platform.ContentHash
import dev.ide.vfs.FileChanged
import dev.ide.vfs.FileCreated
import dev.ide.vfs.FileDeleted
import dev.ide.vfs.FileMoved
import dev.ide.vfs.VfsEvent
import dev.ide.vfs.VirtualFile
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The VFS reaction table ([WorkspaceEventHub.react]): a source CREATE and a multi-file REFACTOR need the
 * analyzer/name-env teardown (`invalidateAnalyzers`) but NOT a full library+SDK `resyncIndex()` — each source
 * file is reindexed with a targeted single-file add, and the classpath is unchanged. Only a genuine CLASSPATH
 * change (a dropped jar, an ambiguous delete/move) re-syncs. Drives the pure decision with a recording
 * [WorkspaceEventHub.Reactions] (no store / message bus).
 */
class WorkspaceReactionTest {

    private class RecordingReactions : WorkspaceEventHub.Reactions {
        val reindexed = ArrayList<Path>()
        /** In call order, so a test can assert the overlay is re-read BEFORE the file is re-indexed. */
        val calls = ArrayList<String>()
        val overlaysRefreshed = ArrayList<Path>()
        var analyzerInvalidations = 0
        var syntheticInvalidations = 0
        var resyncs = 0
        var bindingCacheDrops = 0
        var fileSystemChanges = 0
        override fun invalidateAnalyzers() { analyzerInvalidations++ }
        override fun invalidateSyntheticClasses() { syntheticInvalidations++ }
        override fun resyncIndex() { resyncs++ }
        override fun reindexSourceAsync(path: Path) { reindexed.add(path); calls.add("reindex:$path") }
        override fun dropJavaBindingCaches() { bindingCacheDrops++ }
        override fun dropOverlaysUnder(root: Path) {}
        override fun rekeyOverlays(from: Path, to: Path) {}
        override fun refreshOverlay(path: Path) { overlaysRefreshed.add(path); calls.add("overlay:$path") }
        override fun isResourcePath(path: Path) = path.toString().contains("/res/")
        override fun configurationChanged() {}
        override fun fileSystemChanged() { fileSystemChanges++ }
    }

    private class Vf(override val path: String, override val isDirectory: Boolean = false) : VirtualFile {
        override val name = path.substringAfterLast('/')
        override val exists = true
        override val length = 0L
        override fun parent(): VirtualFile? = null
        override fun children(): List<VirtualFile> = emptyList()
        override fun contentHash() = ContentHash("")
        override fun readBytes() = ByteArray(0)
        override fun readText(): CharSequence = ""
    }

    private fun changed(path: String) = FileChanged(Vf(path), ContentHash(""), ContentHash("x"))

    private fun react(events: List<VfsEvent>): Pair<RecordingReactions, Int> {
        val r = RecordingReactions()
        var membership = 0
        WorkspaceEventHub.react(events, r) { membership++ }
        return r to membership
    }

    @Test
    fun sourceCreateReindexesAndInvalidatesButDoesNotFullResync() {
        val (r, membership) = react(listOf(FileCreated(Vf("/proj/src/com/foo/New.java"))))
        assertEquals(listOf<Path>(Path.of("/proj/src/com/foo/New.java")), r.reindexed, "the new file is added targeted")
        assertEquals(1, r.analyzerInvalidations, "name environments rebuilt so others see the new file")
        assertEquals(0, r.resyncs, "no full library+SDK re-sync for a source-only create")
        assertEquals(1, membership, "the source-file set changed")
    }

    @Test
    fun multiFileRefactorInvalidatesButDoesNotFullResync() {
        val (r, _) = react(listOf(changed("/proj/src/A.kt"), changed("/proj/src/B.kt")))
        assertEquals(2, r.reindexed.size, "each edited file reindexed per-file")
        assertEquals(1, r.analyzerInvalidations, "a cross-file rename rebuilds name environments")
        assertEquals(0, r.resyncs, "no full re-sync: classpath unchanged, files already reindexed")
    }

    @Test
    fun singleSaveIsLightNoInvalidateNoResync() {
        val (r, _) = react(listOf(changed("/proj/src/A.java")))
        assertEquals(1, r.reindexed.size)
        assertEquals(0, r.analyzerInvalidations, "a single save must not evict warm analyzer caches")
        assertEquals(0, r.resyncs)
    }

    @Test
    fun deleteKeepsTheFullResync() {
        val (r, _) = react(listOf(FileDeleted(Vf("/proj/src/A.java"))))
        assertEquals(1, r.analyzerInvalidations)
        assertEquals(1, r.resyncs, "a gone path is ambiguous (source/dir/jar) — full walk is the safe catch-all")
    }

    @Test
    fun moveKeepsTheFullResync() {
        val (r, _) = react(listOf(FileMoved(Vf("/proj/src/B.java"), "/proj/src/A.java", "/proj/src/B.java")))
        assertEquals(1, r.resyncs)
    }

    @Test
    fun nonSourceCreateReSyncsTheClasspath() {
        val (r, _) = react(listOf(FileCreated(Vf("/proj/libs/dep.jar"))))
        assertTrue(r.reindexed.isEmpty(), "a jar is not a source file")
        assertEquals(1, r.resyncs, "a dropped jar can change the classpath")
    }

    // ---- writes the UI did not drive ------------------------------------------------------------------
    //
    // A writer that goes straight to disk (a plugin's ModuleResources write, a template scaffold, an icon
    // generated into res/) publishes a FileChanged carrying NO text. The overlay it wrote behind wins over
    // disk everywhere it is consulted, so it must be re-read, and the tree/tabs must be told.

    /** A change published WITHOUT the text that was written (the empty new-content hash). */
    private fun changedOnDisk(path: String) = FileChanged(Vf(path), ContentHash(""), ContentHash(""))

    @Test
    fun writeWithoutTextRefreshesTheOverlayAndReportsTheChange() {
        val (r, _) = react(listOf(changedOnDisk("/proj/app/src/main/res/values/strings.xml")))
        assertEquals(
            listOf<Path>(Path.of("/proj/app/src/main/res/values/strings.xml")), r.overlaysRefreshed,
            "a write behind an open buffer is invisible until the overlay is re-read",
        )
        assertEquals(1, r.fileSystemChanges, "the tree and any clean open tab have to catch up")
    }

    @Test
    fun theOverlayIsRefreshedBeforeTheFileIsReIndexed() {
        val (r, _) = react(listOf(changedOnDisk("/proj/app/src/main/res/values/strings.xml")))
        assertEquals(
            listOf(
                "overlay:/proj/app/src/main/res/values/strings.xml",
                "reindex:/proj/app/src/main/res/values/strings.xml",
            ),
            r.calls,
            "the re-index reads the overlay in preference to disk, so it must see the refreshed one",
        )
    }

    @Test
    fun anEditorSaveNeitherRefreshesTheOverlayNorReportsAChange() {
        val (r, _) = react(listOf(changed("/proj/app/src/main/res/values/strings.xml")))
        assertTrue(r.overlaysRefreshed.isEmpty(), "a save carries its text: the overlay is already in step")
        assertEquals(0, r.fileSystemChanges, "the UI drove this write; re-walking the tree on every save is waste")
        assertEquals(1, r.syntheticInvalidations, "the res reaction itself is unchanged")
    }

    @Test
    fun createDeleteAndMoveAllReportAFileSystemChange() {
        assertEquals(1, react(listOf(FileCreated(Vf("/proj/src/New.java")))).first.fileSystemChanges)
        assertEquals(1, react(listOf(FileDeleted(Vf("/proj/src/A.java")))).first.fileSystemChanges)
        assertEquals(
            1,
            react(listOf(FileMoved(Vf("/proj/src/B.java"), "/proj/src/A.java", "/proj/src/B.java")))
                .first.fileSystemChanges,
        )
    }

    @Test
    fun aBatchReportsOneFileSystemChange() {
        val (r, _) = react(listOf(changedOnDisk("/proj/src/A.kt"), changedOnDisk("/proj/src/B.kt")))
        assertEquals(2, r.overlaysRefreshed.size, "every file in the batch is re-read")
        assertEquals(1, r.fileSystemChanges, "coalesced per batch, like every other reaction")
    }

    @Test
    fun aCreatedDirectoryIsStillANoOp() {
        val (r, _) = react(listOf(FileCreated(Vf("/proj/src/com", isDirectory = true))))
        assertTrue(r.overlaysRefreshed.isEmpty(), "a directory has no overlay to refresh")
        assertEquals(
            0, r.fileSystemChanges,
            "directory creates keep going through the file backend's own epoch bump, as before",
        )
    }
}
