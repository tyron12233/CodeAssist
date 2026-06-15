package dev.ide.model.impl

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CrashSafeWriteTest {

    @Test
    fun writesOverwritesAndLeavesNoTempFiles() {
        val dir = Files.createTempDirectory("crashsafe")
        try {
            val target = dir.resolve("nested/workspace.json")
            CrashSafeWriter.write(target, "v1")
            assertEquals("v1", Files.readString(target))

            CrashSafeWriter.write(target, "v2") // overwrite must be atomic-replace
            assertEquals("v2", Files.readString(target))

            val leftovers = Files.list(target.parent).use { s ->
                s.filter { it.fileName.toString().contains(".tmp.") }.toList()
            }
            assertTrue(leftovers.isEmpty(), "leftover temp files: $leftovers")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun createsMissingParentDirectories() {
        val dir = Files.createTempDirectory("crashsafe2")
        try {
            val target = dir.resolve("a/b/c.toml")
            CrashSafeWriter.write(target, "hi")
            assertTrue(Files.exists(target))
            assertEquals("hi", Files.readString(target))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
