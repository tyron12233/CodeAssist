package dev.ide.lang.jdt

import dev.ide.lang.completion.CaretAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Acceptance behavior: completing a callable inserts `()` and parks the caret usefully — after the parens
 * for a no-arg method (the call is already complete), between them for a method that takes arguments (ready
 * for the arguments to be typed). Non-callables (fields) are inserted verbatim, and an existing argument
 * list isn't duplicated. The mechanism itself is language-neutral ([CaretAction]); this verifies the Java
 * backend's policy that drives it.
 */
class CompletionInsertionTest {

    @Test
    fun noArgMethodInsertsEmptyParensWithCaretAfterThem() {
        val (analyzer, dir) = workspaceWith()
        try {
            val items = completeResult(
                analyzer, dir.resolve("app/T.java"),
                "package app; class T { void m() { String s = \"\"; s.isEmp|CARET| } }",
            ).items
            val item = items.firstOrNull { it.insertText.substringBefore('(') == "isEmpty" }
            assertNotNull(item, "expected isEmpty: ${items.map { it.insertText }}")
            assertEquals("isEmpty()", item.insertText)
            assertEquals(CaretAction.AtEnd, item.caret, "no-arg call is complete — caret goes after ()")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun methodWithParametersInsertsParensWithCaretBetweenThem() {
        val (analyzer, dir) = workspaceWith()
        try {
            val items = completeResult(
                analyzer, dir.resolve("app/T.java"),
                "package app; class T { void m() { String s = \"\"; s.substr|CARET| } }",
            ).items
            val item = items.firstOrNull { it.insertText.substringBefore('(') == "substring" }
            assertNotNull(item, "expected substring: ${items.map { it.insertText }}")
            assertEquals("substring()", item.insertText)
            // caret lands just inside '(' — i.e. "substring".length + 1
            assertEquals(CaretAction.At("substring".length + 1), item.caret)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun fieldIsInsertedVerbatimWithNoCaretAction() {
        val (analyzer, dir) = workspaceWith(
            "lib/Holder.java" to "package lib; public class Holder { public static int field = 0; }",
        )
        try {
            val items = completeResult(
                analyzer, dir.resolve("app/T.java"),
                "package app; import lib.Holder; class T { void m() { Holder.fie|CARET| } }",
            ).items
            val item = items.firstOrNull { it.insertText == "field" }
            assertNotNull(item, "expected field: ${items.map { it.insertText }}")
            assertEquals(CaretAction.AtEnd, item.caret)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun doesNotDuplicateAnExistingArgumentList() {
        val (analyzer, dir) = workspaceWith()
        try {
            // The caret already sits in front of `()`; completing the method must not produce `isEmpty()()`.
            val items = completeResult(
                analyzer, dir.resolve("app/T.java"),
                "package app; class T { void m() { String s = \"\"; s.isEmp|CARET|() } }",
            ).items
            val item = items.firstOrNull { it.insertText.substringBefore('(') == "isEmpty" }
            assertNotNull(item, "expected isEmpty: ${items.map { it.insertText }}")
            assertEquals("isEmpty", item.insertText, "an argument list already follows — insert only the name")
            assertEquals(CaretAction.AtEnd, item.caret)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}