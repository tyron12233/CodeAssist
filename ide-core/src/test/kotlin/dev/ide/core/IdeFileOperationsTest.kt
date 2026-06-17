package dev.ide.core

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * File & package operations the file-tree context menu drives (issue #995): delete / rename / move / copy,
 * for both files and directories. Renaming a Java source file whose public type matches its name also renames
 * the type and every reference (reusing the symbol-rename machinery).
 *
 * Test bodies are block-bodied (not `= runBlocking { … }`): an expression body would take the return type of
 * its last statement, and a non-Unit return makes JUnit silently skip the method.
 */
class IdeFileOperationsTest {

    @Test
    fun deletesAFileAndReportsMissingTargets() {
        val dir = Files.createTempDirectory("fileops-del")
        IdeServices.bootstrapJavaDemo(dir).use { ide ->
            val core = ide.modules().first { it.name == "core" }
            val scratch = ide.sourceRoots(core).first().resolve("com/example/core/Scratch.java")
            Files.writeString(scratch, "package com.example.core; class Scratch {}")
            assertTrue(ide.deletePath(scratch), "delete should succeed")
            assertFalse(Files.exists(scratch), "file removed from disk")
            assertFalse(ide.deletePath(dir.resolve("does/not/exist.java")), "deleting a missing path reports failure")
        }
        dir.toFile().deleteRecursively()
    }

    @Test
    fun renamesAJavaClassAndItsReferencesAcrossFiles() {
        val dir = Files.createTempDirectory("fileops-rename")
        IdeServices.bootstrapJavaDemo(dir).use { ide ->
            val core = ide.modules().first { it.name == "core" }
            val greeter = ide.sourceRoots(core).first().resolve("com/example/core/Greeter.java")
            val util = ide.modules().first { it.name == "util" }
            val formatter = ide.sourceRoots(util).first().resolve("com/example/util/Formatter.java")

            val outcome = runBlocking { ide.renameFile(greeter, "Salutation.java") }
            assertTrue(outcome.success, "rename failed: ${outcome.message}")

            val renamed = greeter.resolveSibling("Salutation.java")
            assertFalse(Files.exists(greeter), "old file is gone")
            assertTrue(Files.exists(renamed), "new file exists")
            assertEquals(renamed.toString(), outcome.newPath, "newPath points at the renamed file")
            assertTrue(Files.readString(renamed).contains("class Salutation"), "the type is renamed in its own file")

            val fmt = Files.readString(formatter)
            assertTrue("Salutation" in fmt, "cross-file reference updated in Formatter:\n$fmt")
            assertFalse("Greeter" in fmt, "the old name is gone from Formatter:\n$fmt")
        }
        dir.toFile().deleteRecursively()
    }

    @Test
    fun renamesANonSourceFileInPlace() {
        val dir = Files.createTempDirectory("fileops-rename2")
        IdeServices.bootstrapJavaDemo(dir).use { ide ->
            val core = ide.modules().first { it.name == "core" }
            val notes = ide.sourceRoots(core).first().resolveSibling("notes.txt")
            Files.createDirectories(notes.parent); Files.writeString(notes, "hi")
            val outcome = runBlocking { ide.renameFile(notes, "readme.txt") }
            assertTrue(outcome.success, outcome.message)
            assertFalse(Files.exists(notes), "old name gone")
            assertTrue(Files.exists(notes.resolveSibling("readme.txt")), "renamed in place")
        }
        dir.toFile().deleteRecursively()
    }

    @Test
    fun movesAndCopiesFilesWithConflictGuards() {
        val dir = Files.createTempDirectory("fileops-move")
        IdeServices.bootstrapJavaDemo(dir).use { ide ->
            val core = ide.modules().first { it.name == "core" }
            val pkg = ide.sourceRoots(core).first().resolve("com/example/core")
            val sub = pkg.resolve("sub"); Files.createDirectories(sub)

            val src = pkg.resolve("Scratch.java")
            Files.writeString(src, "package com.example.core; class Scratch {}")
            val copied = ide.copyPath(src, sub)
            assertNotNull(copied, "copy returns the new path")
            assertTrue(Files.exists(sub.resolve("Scratch.java")), "copy created the destination")
            assertTrue(Files.exists(src), "copy keeps the original")
            assertNull(ide.copyPath(src, sub), "copy onto an existing file is refused")

            val src2 = pkg.resolve("Scratch2.java")
            Files.writeString(src2, "package com.example.core; class Scratch2 {}")
            val moved = ide.movePath(src2, sub)
            assertNotNull(moved, "move returns the new path")
            assertFalse(Files.exists(src2), "move removes the source")
            assertTrue(Files.exists(sub.resolve("Scratch2.java")), "move created the destination")
            assertNull(ide.movePath(sub, sub.resolve("deeper")), "moving a directory into itself is refused")
        }
        dir.toFile().deleteRecursively()
    }

    @Test
    fun renamesAndDeletesADirectory() {
        val dir = Files.createTempDirectory("fileops-dir")
        IdeServices.bootstrapJavaDemo(dir).use { ide ->
            val core = ide.modules().first { it.name == "core" }
            val pkg = ide.sourceRoots(core).first().resolve("com/example/core")
            val renamed = pkg.resolveSibling("renamed")

            assertTrue(runBlocking { ide.renameFile(pkg, "renamed") }.success, "directory rename succeeds")
            assertTrue(Files.exists(renamed) && !Files.exists(pkg), "directory moved in place")

            assertTrue(ide.deletePath(renamed), "recursive directory delete succeeds")
            assertFalse(Files.exists(renamed), "directory removed")
        }
        dir.toFile().deleteRecursively()
    }
}
