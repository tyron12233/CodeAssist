package dev.codeassist.dart

import dev.ide.platform.ContentHash
import dev.ide.platform.impl.MessageBusImpl
import dev.ide.vfs.FileChanged
import dev.ide.vfs.VfsTopics
import dev.ide.vfs.VirtualFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

class PackageWatcherTest {

    @TempDir
    lateinit var pkg: Path

    private fun write(rel: String, text: String = "// $rel"): Path =
        pkg.resolve(rel).also { Files.createDirectories(it.parent); Files.write(it, text.toByteArray()) }

    /** Move a file's time on, as a save does, without waiting for the clock. */
    private fun touch(p: Path) = Files.setLastModifiedTime(p, FileTime.fromMillis(Files.getLastModifiedTime(p).toMillis() + 5_000))

    private fun saved(bus: MessageBusImpl, p: Path) =
        bus.syncPublisher(VfsTopics.CHANGES).onEvents(listOf(FileChanged(FakeFile(p.toString()), ContentHash(""), ContentHash(""))))

    @Test
    fun aSaveIsReportedAtOnceAndOnlyOnce() {
        val main = write("lib/main.dart")
        val bus = MessageBusImpl()
        PackageWatcher(pkg, bus, walkEveryMs = 60_000).use { w ->
            assertEquals(emptyList<Path>(), w.changes())
            touch(main)
            saved(bus, main)
            assertTrue(w.wake.tryReceive().isSuccess, "a save wakes the waiter")
            assertEquals(listOf(main), w.changes())
            // The same save again changes nothing on disk, so it is not a change.
            saved(bus, main)
            assertEquals(emptyList<Path>(), w.changes())
        }
    }

    @Test
    fun filesNothingWatchesAreIgnoredByEventsAndTheWalk() {
        val generated = write(".dart_tool/codeassist/preview/main.dart")
        val built = write("build/app.dart")
        val notes = write("lib/notes.txt")
        val lib = write("lib/a.dart")
        val bus = MessageBusImpl()
        PackageWatcher(pkg, bus, walkEveryMs = 0).use { w ->
            w.changes()
            for (p in listOf(generated, built, notes)) { touch(p); saved(bus, p) }
            assertEquals(emptyList<Path>(), w.changes())
            touch(lib)
            assertEquals(listOf(lib), w.changes(), "the walk finds an edit no event announced")
        }
    }

    @Test
    fun theWalkFindsNewDeletedAndPubspecChanges() {
        val a = write("lib/a.dart")
        val pubspec = write("pubspec.yaml", "name: app\n")
        PackageWatcher(pkg, bus = null, walkEveryMs = 0).use { w ->
            Files.delete(a)
            val b = write("lib/src/b.dart")
            touch(pubspec)
            assertEquals(setOf(a, b, pubspec), w.changes().toSet())
        }
    }

    private class FakeFile(override val path: String) : VirtualFile {
        override val name: String get() = path.substringAfterLast('/')
        override val isDirectory = false
        override val exists = true
        override val length = 0L
        override fun parent(): VirtualFile? = null
        override fun children(): List<VirtualFile> = emptyList()
        override fun contentHash() = ContentHash("")
        override fun readBytes() = ByteArray(0)
        override fun readText(): CharSequence = ""
    }
}
