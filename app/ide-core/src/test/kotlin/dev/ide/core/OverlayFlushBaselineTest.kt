package dev.ide.core

import dev.ide.testkit.withTempDir
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The pre-build flush writes only buffers with unsaved edits. It used to write every buffer that differed from
 * disk, so a file copied over an open, untouched tab from outside the IDE was put back to the tab's old text by
 * the next Run, and a file deleted from outside was re-created.
 */
class OverlayFlushBaselineTest {

    @Test
    fun aCleanBufferNeverOverwritesAFileChangedFromOutside() {
        withTempDir("flush-clean") { dir ->
        IdeServices.bootstrapJavaDemo(dir).use { ide ->
            val src = ide.workspaceRoot.resolve("Notes.txt")
            Files.writeString(src, "old")
            ide.documentLoaded(src, "old") // the editor opened it

            Files.writeString(src, "copied in") // a file manager copies over it
            ide.flushOpenDocuments()

            assertEquals("copied in", Files.readString(src))
        }
        }
    }

    @Test
    fun aCleanBufferDoesNotRecreateAFileDeletedFromOutside() {
        withTempDir("flush-deleted") { dir ->
        IdeServices.bootstrapJavaDemo(dir).use { ide ->
            val src = ide.workspaceRoot.resolve("Notes.txt")
            Files.writeString(src, "old")
            ide.documentLoaded(src, "old")

            Files.delete(src)
            ide.flushOpenDocuments()

            assertFalse(Files.exists(src))
        }
        }
    }

    @Test
    fun anEditedBufferStillFlushesAndThenCountsAsSaved() {
        withTempDir("flush-edited") { dir ->
        IdeServices.bootstrapJavaDemo(dir).use { ide ->
            val src = ide.workspaceRoot.resolve("Notes.txt")
            Files.writeString(src, "old")
            ide.documentLoaded(src, "old")
            ide.updateDocument(src, "typed") // an unsaved edit

            ide.flushOpenDocuments()
            assertEquals("typed", Files.readString(src), "the build compiles what the user sees")

            Files.writeString(src, "copied in")
            ide.flushOpenDocuments()
            assertEquals("copied in", Files.readString(src), "once flushed, the buffer is clean again")
        }
        }
    }

    @Test
    fun aReloadedBufferIsCleanAndASavedOneIsToo() {
        withTempDir("flush-reload") { dir ->
        IdeServices.bootstrapJavaDemo(dir).use { ide ->
            val src = ide.workspaceRoot.resolve("Notes.txt")
            Files.writeString(src, "old")
            ide.documentLoaded(src, "old")

            Files.writeString(src, "v2")
            ide.documentLoaded(src, "v2") // the tab picked up the outside write
            Files.writeString(src, "v3") // and another lands before the tab catches up
            ide.flushOpenDocuments()
            assertEquals("v3", Files.readString(src))

            ide.save(src, "saved")
            Files.writeString(src, "v4")
            ide.flushOpenDocuments()
            assertEquals("v4", Files.readString(src))
        }
        }
    }
}
