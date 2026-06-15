package dev.ide.core

import dev.ide.index.IndexId
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

/** End-to-end (real IdeServices + real index): an empty-prefix import lists a package's children. */
class ImportCompletionE2ETest {

    @Test
    fun emptyPrefixImportShowsPackagesEndToEnd() {
        val dir = Files.createTempDirectory("ide-import")
        IdeServices.bootstrapJavaDemo(dir).use { ide ->
            // wait until the background package index has indexed java's children (deep package present)
            val deadline = System.currentTimeMillis() + 90_000
            while (System.currentTimeMillis() < deadline &&
                ide.indexService.prefix<String>(IndexId("java.packages"), "java.", 5).none()) Thread.sleep(100)

            val app = ide.modules().first { it.name == "app" }
            val probe = ide.sourceRoots(app).first().resolve("com/example/app/Probe.java")
            fun complete(variant: String): List<String> {
                val code = "package com.example.app;\n$variant"
                val offset = code.indexOf("|CARET|")
                return ide.complete(probe, code.replace("|CARET|", ""), offset).items.map { it.insertText }
            }

            val empty = complete("import java.|CARET|")
            assertTrue(empty.isNotEmpty(), "empty-prefix import must list java's sub-packages")
            assertTrue(empty.all { '.' !in it }, "should be simple package segments, not FQNs: $empty")
            assertTrue(complete("import java.u|CARET|").contains("util"), "prefix narrowing must still work")
        }
        dir.toFile().deleteRecursively()
    }
}
