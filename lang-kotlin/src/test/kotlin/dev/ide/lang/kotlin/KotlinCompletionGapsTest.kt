package dev.ide.lang.kotlin

import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The completion-gap features added in the 2026-07 audit sweep: label completion (`return@`/`break@`/
 * `continue@`), annotation-argument enum values, short enum-entry form, exhaustive-`when` branch fill, KDoc
 * tags/`@param` names, `package` directive completion, `else`/`do-while` keyword continuations, and the
 * (previously untested) string-template completion.
 */
class KotlinCompletionGapsTest {

    private fun labels(code: String): List<String> =
        runBlocking { analyzer.completeAtCaret(srcDir, "Use.kt", code) }.items.map { it.label }

    private fun items(code: String) =
        runBlocking { analyzer.completeAtCaret(srcDir, "Use.kt", code) }.items

    // --- labels ---

    @Test
    fun returnLabelOffersEnclosingLambdaName() {
        val ls = labels("package demo\nfun f() { listOf(1).forEach { return@| } }")
        assertTrue("forEach" in ls, "return@ should offer the enclosing lambda's implicit label; got $ls")
        assertTrue("listOf" !in ls, "a label position must NOT offer scope symbols; got $ls")
    }

    @Test
    fun continueLabelOffersLoopLabel() {
        val ls = labels("package demo\nfun f() { loop@ for (i in 1..2) { continue@| } }")
        assertTrue("loop" in ls, "continue@ should offer the enclosing labeled loop; got $ls")
    }

    // --- annotation-argument enum values ---

    @Test
    fun annotationEnumArgumentOffersConstants() {
        val ls = labels("package demo\n@Cfg(mode = |)\nfun f() {}")
        assertTrue(ls.any { it == "Mode.A" || it == "A" }, "an enum-typed annotation argument should offer its constants; got $ls")
    }

    // --- short enum-entry form ---

    @Test
    fun enumSlotOffersBothShortAndQualifiedForms() {
        val ls = labels("package demo\nfun f(c: Color) = when (c) {\n  | -> 0\n  else -> 1\n}")
        assertTrue("RED" in ls, "an enum slot should offer the short entry form; got $ls")
        assertTrue("Color.RED" in ls, "and keep the qualified form; got $ls")
    }

    // --- exhaustive-when branch fill ---

    @Test
    fun sealedWhenOffersAddRemainingBranches() {
        val it = items("package demo\nfun f(s: Shape) = when (s) {\n  is Circle -> 0\n  | \n}")
            .firstOrNull { i -> i.label == "Add remaining branches" }
        assertTrue(it != null, "a sealed when should offer an add-remaining-branches item")
        assertTrue("is Square -> TODO()" in it!!.insertText, "it should fill the uncovered subtype; got '${it.insertText}'")
        assertTrue("is Circle" !in it.insertText, "and NOT re-add the covered one; got '${it.insertText}'")
    }

    @Test
    fun enumWhenOffersAddRemainingBranches() {
        val it = items("package demo\nfun f(c: Color) = when (c) {\n  | \n}")
            .firstOrNull { i -> i.label == "Add remaining branches" }
        assertTrue(it != null, "an enum when should offer an add-remaining-branches item")
        assertTrue("Color.RED -> TODO()" in it!!.insertText && "Color.GREEN -> TODO()" in it.insertText, "got '${it.insertText}'")
    }

    // --- KDoc ---

    @Test
    fun kdocOffersTagsAfterAt() {
        val ls = labels("package demo\n/**\n * @par|\n */\nfun f(count: Int) {}")
        assertTrue("@param" in ls, "a KDoc `@par` should offer @param; got $ls")
    }

    @Test
    fun kdocOffersParameterNamesAfterParamTag() {
        val ls = labels("package demo\n/**\n * @param |\n */\nfun f(count: Int, label: String) {}")
        assertTrue("count" in ls && "label" in ls, "`@param ` should offer the function's parameter names; got $ls")
    }

    @Test
    fun kdocProseOffersNothing() {
        val ls = labels("package demo\n/**\n * some | text\n */\nfun f() { val local = 1 }")
        assertTrue(ls.isEmpty(), "KDoc prose must not leak scope symbols/keywords; got $ls")
    }

    // --- package directive ---

    @Test
    fun packageDirectiveOffersPackageRoots() {
        val ls = labels("package de|")
        assertTrue("demo" in ls, "a package directive should offer package roots; got $ls")
    }

    // --- else / do-while continuations ---

    @Test
    fun elseOfferedAfterIf() {
        val ls = labels("package demo\nfun f() { if (true) {}\n  el| }")
        assertTrue("else" in ls, "`else` should be offered after an else-less if; got $ls")
    }

    @Test
    fun whileOfferedAfterDoBlock() {
        val ls = labels("package demo\nfun f() { do {}\n  wh| }")
        assertTrue("while" in ls, "`while` should be offered after a do-block; got $ls")
    }

    // --- string templates (regression) ---

    @Test
    fun stringTemplateSimpleNameOffersScope() {
        val ls = labels("package demo\nfun f(name: String) { val x = \"\$na|\" }")
        assertTrue("name" in ls, "a `\$name` template should complete scope variables; got $ls")
    }

    @Test
    fun stringTemplateBlockOffersMembers() {
        val ls = labels("package demo\nfun f(s: String) { val x = \"\${s.len|}\" }")
        assertTrue(ls.any { it.startsWith("length") }, "a `\${s.len}` template should complete members; got $ls")
    }

    companion object {
        val srcDir: Path = tempProject(
            mapOf(
                "Fixtures.kt" to (
                    "package demo\n" +
                        "enum class Color { RED, GREEN }\n" +
                        "enum class Mode { A, B }\n" +
                        "annotation class Cfg(val mode: Mode = Mode.A)\n" +
                        "sealed class Shape\n" +
                        "class Circle : Shape()\n" +
                        "class Square : Shape()\n"
                    ),
                "widgets/W.kt" to "package demo.widgets\nclass W\n",
                "Use.kt" to "package demo\n",
            ),
        )
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
    }
}
