package dev.ide.core

import dev.ide.lang.completion.CompletionItemKind
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Hippie / word completion (real IdeServices): a word present only in the buffer (here in a comment, so no
 * semantic backend could surface it) is offered as a low-priority [CompletionItemKind.WORD] item that
 * extends the prefix under the caret, and accepting it replaces that prefix.
 */
class HippieCompletionTest {

    @Test
    fun bufferWordCompletesFromAnywhereInTheFile() {
        val dir = Files.createTempDirectory("ide-hippie")
        IdeServices.bootstrapJavaDemo(dir).use { ide ->
            val app = ide.modules().first { it.name == "app" }
            val probe = ide.sourceRoots(app).first().resolve("com/example/app/Probe.java")
            // `ztrocketfuel` exists only in a comment — the JDT backend can't propose it, so any proposal of
            // it must come from the buffer-word fallback.
            val code = """
                package com.example.app;
                // ztrocketfuel marker
                public class Probe {
                    void m() {
                        ztr|CARET|
                    }
                }
            """.trimIndent()
            val offset = code.indexOf("|CARET|")
            val result = ide.complete(probe, code.replace("|CARET|", ""), offset)

            val word = result.items.firstOrNull { it.insertText == "ztrocketfuel" }
            assertTrue(word != null, "buffer word should be offered: ${result.items.map { it.insertText }}")
            assertEquals(CompletionItemKind.WORD, word.kind, "buffer words carry the WORD kind")

            // The accept range covers the partial word `ztr`, so accepting replaces it (not just inserts).
            val replaced = code.replace("|CARET|", "").substring(result.replacementRange.start, result.replacementRange.end)
            assertEquals("ztr", replaced, "accept range should span the typed prefix")
        }
        dir.toFile().deleteRecursively()
    }
}
