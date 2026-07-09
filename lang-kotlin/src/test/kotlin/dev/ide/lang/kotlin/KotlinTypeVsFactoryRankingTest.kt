package dev.ide.lang.kotlin

import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A capitalized type prefix in a bare name reference must surface the CLASS, not bury it under the stdlib
 * `String(...)` factory functions (top-level `fun String(...)`). Regression for the completion offering
 * `String(stringBuilder: StringBuilder)` ahead of the `String`/`StringBuilder` classes.
 */
class KotlinTypeVsFactoryRankingTest {

    private fun items(file: String, code: String) =
        runBlocking { analyzer.completeAtCaret(srcDir, file, code) }.items

    @Test
    fun classRanksAboveSameNameTopLevelFactoryFunctions() {
        val ls = items("U.kt", "fun f() { val x = Strin| }")
        val strClass = ls.indexOfFirst { it.label == "String" && it.kind == dev.ide.lang.completion.CompletionItemKind.CLASS }
        val firstFactory = ls.indexOfFirst { it.label.startsWith("String(") }
        assertTrue(strClass >= 0, "the String class should be offered; got ${ls.take(12).map { it.label }}")
        assertTrue(firstFactory < 0 || strClass < firstFactory,
            "the String class (#$strClass) must rank above the String(...) factory functions (#$firstFactory); got ${ls.take(12).map { it.label }}")
    }

    @Test
    fun lowercaseFunctionPrefixStillWinsOnGrade() {
        // The demotion is a proximity tiebreak AFTER grade — a lowercase function prefix must still beat a
        // case-insensitively-matching type (`printl` → println, not some `Print*` class).
        val ls = items("U.kt", "fun f() { printl| }").mapNotNull { it.symbol?.name }
        assertNotNull(ls.firstOrNull { it == "println" }, "println should be offered")
        assertTrue(ls.indexOf("println") == 0 || ls.take(3).contains("println"),
            "println should rank at the very top for a lowercase prefix; got ${ls.take(6)}")
    }

    companion object {
        val srcDir: Path = tempProject(mapOf("Dummy.kt" to "package demo\n"))
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
    }
}
