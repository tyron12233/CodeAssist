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
        var analyzerInvalidations = 0
        var syntheticInvalidations = 0
        var resyncs = 0
        var bindingCacheDrops = 0
        override fun invalidateAnalyzers() { analyzerInvalidations++ }
        override fun invalidateSyntheticClasses() { syntheticInvalidations++ }
        override fun resyncIndex() { resyncs++ }
        override fun reindexSourceAsync(path: Path) { reindexed.add(path) }
        override fun dropJavaBindingCaches() { bindingCacheDrops++ }
        override fun dropOverlaysUnder(root: Path) {}
        override fun rekeyOverlays(from: Path, to: Path) {}
        override fun isResourcePath(path: Path) = path.toString().contains("/res/")
        override fun configurationChanged() {}
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
}
