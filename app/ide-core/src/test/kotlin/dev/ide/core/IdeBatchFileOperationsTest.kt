package dev.ide.core

import dev.ide.testkit.withTempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The file tree's batch operations: moving/copying many items in one call (with conflict choices), deleting into
 * the trash, and undoing either. Block-bodied tests on purpose: an expression body that ends in a non-Unit value
 * is silently skipped by JUnit.
 */
class IdeBatchFileOperationsTest {

    private fun withDemo(name: String, body: (IdeServices, Path) -> Unit) {
        withTempDir(name) { dir ->
            IdeServices.bootstrapJavaDemo(dir).use { ide ->
                val core = ide.modules().first { it.name == "core" }
                body(ide, ide.sourceRoots(core).first())
            }
            dir.toFile().deleteRecursively()
        }
    }

    private fun write(p: Path, text: String): Path {
        Files.createDirectories(p.parent)
        Files.writeString(p, text)
        return p
    }

    @Test
    fun movesSeveralSourcesInOneSweepAndUndoesTheMove() {
        withDemo("batch-move") { ide, root ->
            val a = write(root.resolve("com/example/core/Alpha.java"), "package com.example.core;\n\npublic class Alpha {}\n")
            val b = write(root.resolve("com/example/core/Beta.kt"), "package com.example.core\n\nclass Beta\n")
            val user = write(
                root.resolve("com/example/app/User.java"),
                "package com.example.app;\n\nimport com.example.core.Alpha;\nimport com.example.core.Beta;\n\nclass User {}\n",
            )
            val dest = root.resolve("com/example/core/sub").also { Files.createDirectories(it) }

            val r = ide.transferPaths(move = true, items = listOf(a to ConflictChoice.Skip, b to ConflictChoice.Skip), destDir = dest)
            assertEquals(2, r.done.size, "both moved: $r")
            assertTrue(r.failed.isEmpty(), "no failures: ${r.failed}")
            assertTrue("package com.example.core.sub;" in Files.readString(dest.resolve("Alpha.java")))
            assertTrue("package com.example.core.sub" in Files.readString(dest.resolve("Beta.kt")))
            val userText = Files.readString(user)
            assertTrue("import com.example.core.sub.Alpha;" in userText && "import com.example.core.sub.Beta;" in userText, userText)

            val token = assertNotNull(r.undoToken, "a move is undoable")
            val undo = ide.undoFileOperation(token)
            assertTrue(undo.ok && undo.message.isEmpty(), "undo ran clean: $undo")
            assertTrue(Files.exists(a) && Files.exists(b), "both are back")
            assertTrue("package com.example.core;" in Files.readString(a), "the package line went back")
            val back = Files.readString(user)
            assertTrue("import com.example.core.Alpha;" in back && "import com.example.core.Beta;" in back, back)
            assertFalse(ide.undoFileOperation(token).ok, "a token undoes once")
        }
    }

    @Test
    fun anItemInsideAnotherSelectedItemTravelsWithItsFolder() {
        withDemo("batch-nested") { ide, root ->
            val folder = root.resolve("com/example/core/feature")
            val inner = write(folder.resolve("Inner.java"), "package com.example.core.feature;\n\nclass Inner {}\n")
            val dest = root.resolve("com/example/other").also { Files.createDirectories(it) }
            val r = ide.transferPaths(true, listOf(folder to ConflictChoice.Skip, inner to ConflictChoice.Skip), dest)
            assertEquals(setOf(folder), r.done.keys, "only the folder is transferred: $r")
            assertTrue(Files.exists(dest.resolve("feature/Inner.java")))
            assertTrue("package com.example.other.feature;" in Files.readString(dest.resolve("feature/Inner.java")))
        }
    }

    @Test
    fun conflictChoicesSkipReplaceAndKeepBoth() {
        withDemo("batch-conflict") { ide, root ->
            val src = root.resolve("com/example/core")
            val dest = root.resolve("com/example/dest")
            val skipMe = write(src.resolve("notes.txt"), "new notes")
            val replaceMe = write(src.resolve("data.txt"), "new data")
            val keepMe = write(src.resolve("Widget.java"), "package com.example.core;\n\npublic class Widget { Widget() {} }\n")
            write(dest.resolve("notes.txt"), "old notes")
            write(dest.resolve("data.txt"), "old data")
            write(dest.resolve("Widget.java"), "package com.example.dest;\n\npublic class Widget {}\n")

            val r = ide.transferPaths(
                move = false,
                items = listOf(skipMe to ConflictChoice.Skip, replaceMe to ConflictChoice.Replace, keepMe to ConflictChoice.KeepBoth),
                destDir = dest,
            )
            assertEquals(listOf(skipMe), r.skipped)
            assertEquals("old notes", Files.readString(dest.resolve("notes.txt")), "skipped leaves the existing file")
            assertEquals("new data", Files.readString(dest.resolve("data.txt")), "replace takes its place")
            val copy = dest.resolve("WidgetCopy.java")
            assertEquals(copy, r.done[keepMe], "keep both lands under a free name")
            val copyText = Files.readString(copy)
            assertTrue("public class WidgetCopy { WidgetCopy() {} }" in copyText, "the type follows the file name: $copyText")
            assertTrue("package com.example.dest;" in copyText, copyText)
            assertTrue(Files.exists(skipMe) && Files.exists(replaceMe) && Files.exists(keepMe), "a copy leaves the sources")

            val undo = ide.undoFileOperation(assertNotNull(r.undoToken))
            assertTrue(undo.ok, undo.message)
            assertEquals("old data", Files.readString(dest.resolve("data.txt")), "the replaced file comes back")
            assertFalse(Files.exists(copy), "the copy is removed")
        }
    }

    @Test
    fun duplicatingIntoTheSameFolderKeepsBoth() {
        withDemo("batch-dup") { ide, root ->
            val res = root.resolveSibling("res/layout")
            val layout = write(res.resolve("activity_main.xml"), "<LinearLayout/>")
            val r = ide.transferPaths(false, listOf(layout to ConflictChoice.KeepBoth), res)
            assertEquals(res.resolve("activity_main_copy.xml"), r.done[layout], "$r")
            val again = ide.transferPaths(false, listOf(layout to ConflictChoice.KeepBoth), res)
            assertEquals(res.resolve("activity_main_copy2.xml"), again.done[layout])
        }
    }

    @Test
    fun refusesToMoveAFolderIntoItselfAndSkipsAMoveIntoItsOwnFolder() {
        withDemo("batch-guards") { ide, root ->
            val folder = root.resolve("com/example/core/feature").also { Files.createDirectories(it.resolve("deep")) }
            val r = ide.transferPaths(true, listOf(folder to ConflictChoice.Replace), folder.resolve("deep"))
            assertTrue(folder in r.failed, "into itself fails: $r")
            val file = write(root.resolve("com/example/core/Here.java"), "package com.example.core;\nclass Here {}\n")
            val same = ide.transferPaths(true, listOf(file to ConflictChoice.Replace), file.parent)
            assertEquals(listOf(file), same.skipped)
            assertNull(same.undoToken, "nothing changed, nothing to undo")
            assertTrue(Files.exists(file))
        }
    }

    @Test
    fun deletesIntoTheTrashAndUndoBringsItBack() {
        withDemo("batch-delete") { ide, root ->
            val folder = root.resolve("com/example/core/gone")
            val inner = write(folder.resolve("Inner.java"), "package com.example.core.gone;\nclass Inner {}\n")
            val loose = write(root.resolve("com/example/core/loose.txt"), "x")
            val r = ide.deletePaths(listOf(folder, inner, loose))
            assertEquals(setOf(folder, loose), r.deleted.toSet(), "the inner file goes with its folder: $r")
            assertFalse(Files.exists(folder) || Files.exists(loose))

            val undo = ide.undoFileOperation(assertNotNull(r.undoToken))
            assertTrue(undo.ok, undo.message)
            assertEquals("x", Files.readString(loose))
            assertTrue(Files.exists(inner), "the folder comes back with its contents")
        }
    }
}
