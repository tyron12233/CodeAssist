package dev.ide.lang.kotlin

import dev.ide.lang.completion.CompletionItem
import dev.ide.lang.completion.CompletionItemKind
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Context-aware keyword, modifier, live-template and postfix completion ([KotlinKeywords]/[KotlinPostfix]):
 * keywords are offered only where the grammar admits them (statement vs member vs top-level vs argument),
 * already-typed modifiers aren't re-offered, the `if`/`for`/`main`/`fun` live templates expand to skeletons,
 * and `expr.val`/`cond.if`/`list.for` postfix rewrites surface at a member-access position.
 */
class KotlinKeywordCompletionTest {

    private fun items(code: String): List<CompletionItem> =
        runBlocking { analyzer.completeAtCaret(srcDir, "K.kt", code) }.items

    private fun labels(code: String): List<String> = items(code).map { it.label }

    private fun snippet(code: String, label: String): CompletionItem =
        items(code).first { it.kind == CompletionItemKind.SNIPPET && it.label == label }

    // --- A: keywords gated by position ---

    @Test
    fun statementPositionOffersControlAndDeclarationKeywords() {
        val ls = labels("package demo\nfun g() { va| }")
        assertTrue("val" in ls, "statement position should offer `val`; got $ls")
        assertTrue("var" in ls, "statement position should offer `var`; got $ls")
    }

    @Test
    fun returnOfferedInFunctionBody() {
        assertTrue("return" in labels("package demo\nfun g() { ret| }"), "expected `return` in a function body")
    }

    @Test
    fun loopKeywordsOnlyInsideLoop() {
        assertTrue("break" in labels("package demo\nfun g() { for (x in 0..1) { bre| } }"), "expected `break` in a loop")
        assertFalse("break" in labels("package demo\nfun g() { bre| }"), "no `break` outside a loop")
    }

    @Test
    fun memberPositionOffersFunAndModifiers() {
        val ls = labels("package demo\nclass C { ov| }")
        assertTrue("override" in ls, "member position should offer `override`; got $ls")
    }

    @Test
    fun memberModifierOffered() {
        assertTrue("abstract" in labels("package demo\nclass C { abst| }"), "expected `abstract` in a class body")
    }

    @Test
    fun topLevelOffersClassAndFun() {
        val ls = labels("package demo\nclas|")
        assertTrue("class" in ls, "top level should offer `class`; got $ls")
    }

    @Test
    fun argumentPositionExcludesStatementKeywords() {
        // Inside a call's argument list only expression keywords belong — `val`/`for` must not appear.
        val ls = labels("package demo\nfun box(w: Int) {}\nfun g() { box(|) }")
        assertFalse("val" in ls, "no `val` in an argument slot; got $ls")
        assertFalse("for" in ls, "no `for` in an argument slot; got $ls")
        assertTrue("null" in ls, "expected `null` in an expression position; got $ls")
    }

    @Test
    fun noKeywordsInTypePosition() {
        // A type-reference position must offer types, never statement keywords.
        assertFalse("val" in labels("package demo\nfun g(): In| {}"), "no `val` in a type position")
    }

    // --- B: modifier de-duplication ---

    @Test
    fun alreadyTypedModifierNotReoffered() {
        val ls = labels("package demo\nclass C { private | }")
        assertFalse("private" in ls, "`private` already present should not be re-offered; got $ls")
        assertTrue("abstract" in ls, "other modifiers should still be offered; got $ls")
    }

    // --- C: live templates ---

    @Test
    fun funLiveTemplateExpands() {
        val it = snippet("package demo\nfun g() { fun| }", "fun")
        assertTrue(it.insertText.startsWith("fun name(") && it.insertText.contains("{\n"), "got ${it.insertText}")
    }

    @Test
    fun mainLiveTemplateAtTopLevel() {
        val it = snippet("package demo\nmai|", "main")
        assertEquals("fun main() {\n    \n}", it.insertText)
    }

    @Test
    fun ifLiveTemplateExpands() {
        val it = snippet("package demo\nfun g() { if| }", "if")
        assertTrue(it.insertText.startsWith("if (cond) {"), "got ${it.insertText}")
    }

    // Postfix templates moved off the Kotlin completion service onto POSTFIX_TEMPLATE_EP (driven by the
    // engine's generic PostfixContributor) — see KotlinPostfixTemplateTest for the template logic and
    // ide-core PostfixContributorTest for the end-to-end driver.

    companion object {
        val srcDir: Path = tempProject(emptyMap())
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
    }
}
