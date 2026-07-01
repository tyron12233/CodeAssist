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

    // --- D: parameter-position keywords (constructors & functions) ---

    @Test
    fun primaryConstructorOffersValVarAndModifiers() {
        val ls = labels("package demo\nclass C(va|)")
        assertTrue("val" in ls, "primary ctor should offer `val`; got $ls")
        assertTrue("var" in ls, "primary ctor should offer `var`; got $ls")
    }

    @Test
    fun primaryConstructorOffersVisibilityAndOverride() {
        assertTrue("private" in labels("package demo\nclass C(priv|)"), "primary ctor should offer `private`")
        assertTrue("override" in labels("package demo\nclass C(over|)"), "primary ctor should offer `override`")
        assertTrue("vararg" in labels("package demo\nclass C(vara|)"), "primary ctor should offer `vararg`")
    }

    @Test
    fun siblingParameterModifierDoesNotSuppressValOnTheNextParameter() {
        // The comma boundary keeps the first parameter's `val` from de-duplicating the second's.
        val ls = labels("package demo\nclass C(val x: Int, va|)")
        assertTrue("val" in ls, "`val` should still be offered on a sibling parameter; got $ls")
    }

    @Test
    fun secondaryConstructorOffersVarargButNotValVar() {
        val ls = labels("package demo\nclass C {\n  constructor(v|)\n}")
        assertTrue("vararg" in ls, "secondary ctor should offer `vararg`; got $ls")
        assertFalse("val" in ls, "a secondary-ctor parameter cannot be a property; got $ls")
        assertFalse("var" in ls, "a secondary-ctor parameter cannot be a property; got $ls")
    }

    @Test
    fun functionParameterOffersVarargButNotValVar() {
        val ls = labels("package demo\nfun f(v|) {}")
        assertTrue("vararg" in ls, "function parameter should offer `vararg`; got $ls")
        assertFalse("val" in ls, "a function parameter cannot be a property; got $ls")
    }

    @Test
    fun noinlineAndCrossinlineOnlyInInlineFunction() {
        assertTrue("noinline" in labels("package demo\ninline fun f(n|) {}"), "inline fun should offer `noinline`")
        assertTrue("crossinline" in labels("package demo\ninline fun f(cross|) {}"), "inline fun should offer `crossinline`")
        assertFalse("noinline" in labels("package demo\nfun f(n|) {}"), "a non-inline fun must not offer `noinline`")
    }

    @Test
    fun parameterTypePositionOffersNoModifierKeywords() {
        assertFalse("val" in labels("package demo\nclass C(x: In|)"), "no `val` in a parameter's type position")
    }

    @Test
    fun parameterDefaultValueIsAnExpressionPosition() {
        val ls = labels("package demo\nfun f(x: Int = |) {}")
        assertTrue("null" in ls, "a default-value slot is an expression position; got $ls")
        assertFalse("val" in ls, "no `val` in a default-value slot; got $ls")
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

    // --- E: `by` delegation, variance, and the new modifiers/declaration keywords ---

    @Test
    fun byOfferedAfterPropertyName() {
        assertTrue("by" in labels("package demo\nfun g() { val x | }"), "expected `by` after a property name (delegation)")
        assertTrue("by" in labels("package demo\nfun g() { val x: Int | }"), "expected `by` after a typed property (delegation)")
    }

    @Test
    fun byNotOfferedWhileTypingPropertyName() {
        // `val b|` — `b` IS the name being typed, not a continuation; `by` must not be suggested as the name.
        assertFalse("by" in labels("package demo\nfun g() { val b| }"), "no `by` while typing the property name")
    }

    @Test
    fun byNotOfferedOnceInitializerStarted() {
        assertFalse("by" in labels("package demo\nfun g() { val x = | }"), "no `by` once `=` started the initializer")
    }

    @Test
    fun byOfferedForClassDelegation() {
        assertTrue(
            "by" in labels("package demo\ninterface I\nclass A : I | {}"),
            "expected `by` after a supertype (class delegation `class A : I by d`)",
        )
    }

    @Test
    fun functionModifiersOfferedInClassBody() {
        assertTrue("operator" in labels("package demo\nclass C { op| }"), "expected `operator` in a class body")
        assertTrue("infix" in labels("package demo\nclass C { inf| }"), "expected `infix` in a class body")
        assertTrue("tailrec" in labels("package demo\nclass C { tail| }"), "expected `tailrec` in a class body")
    }

    @Test
    fun valueClassOfferedAtTopLevelAndMember() {
        assertTrue("value class" in labels("package demo\nval| "), "expected `value class` at top level")
    }

    @Test
    fun varianceOfferedInTypeParameterList() {
        assertTrue("out" in labels("package demo\nclass Box<o|>"), "expected `out` variance in a type-parameter list")
        assertTrue("in" in labels("package demo\nclass Box<i|>"), "expected `in` variance in a type-parameter list")
    }

    @Test
    fun reifiedOfferedOnlyForInlineFunctionTypeParams() {
        assertTrue("reified" in labels("package demo\ninline fun <r|> f() {}"), "expected `reified` for an inline fun's type param")
        assertFalse("reified" in labels("package demo\nfun <r|> f() {}"), "no `reified` for a non-inline fun's type param")
    }

    // --- F: local declarations in a statement position ---

    @Test
    fun statementOffersLocalDeclarationKeywords() {
        val ls = labels("package demo\nfun g() { cl| }")
        assertTrue("class" in ls, "a block admits a local `class`; got $ls")
    }

    @Test
    fun statementOffersLocalObjectAndInterface() {
        assertTrue("object" in labels("package demo\nfun g() { obj| }"), "a block admits a local `object`")
        assertTrue("interface" in labels("package demo\nfun g() { inter| }"), "a block admits a local `interface`")
        assertTrue("typealias" in labels("package demo\nfun g() { typea| }"), "a block admits a local `typealias`")
    }

    @Test
    fun statementDoesNotOfferVisibilityModifiers() {
        // A local declaration cannot carry a visibility modifier, so `private` must not be offered in a block.
        assertFalse("private" in labels("package demo\nfun g() { priv| }"), "no `private` on a local declaration")
    }

    // --- G: when-condition keywords ---

    @Test
    fun whenConditionOffersTypeAndRangeChecks() {
        val ls = labels("package demo\nfun g(x: Any) { when (x) { i| } }")
        assertTrue("is" in ls, "a with-subject `when` condition admits `is`; got $ls")
        assertTrue("in" in ls, "a with-subject `when` condition admits `in`; got $ls")
    }

    @Test
    fun whenConditionOffersElse() {
        assertTrue("else" in labels("package demo\nfun g(x: Any) { when (x) { 1 -> Unit\n el| } }"), "a `when` condition admits `else`")
    }

    @Test
    fun whenConditionExcludesStatementKeywords() {
        // A `when`-entry condition is an expression position — `val`/`for` must not appear even though the
        // `when` sits inside a block (which would otherwise read as a statement position).
        val ls = labels("package demo\nfun g(x: Any) { when (x) { va| } }")
        assertFalse("val" in ls, "no `val` in a when-condition; got $ls")
        assertFalse("for" in ls, "no `for` in a when-condition; got $ls")
    }

    @Test
    fun subjectlessWhenDoesNotOfferIs() {
        // Without a subject, a `when` branch is a boolean expression; `is`/`in` need a subject to test against.
        assertFalse("is" in labels("package demo\nfun g() { when { i| } }"), "no `is` in a subject-less when")
    }

    // --- H: property accessors ---

    @Test
    fun getSetOfferedAfterProperty() {
        val ls = labels("package demo\nclass C {\n  val x: Int g|\n}")
        assertTrue("get" in ls, "expected `get` after a property declaration; got $ls")
    }

    @Test
    fun getNotOfferedAtStatementStart() {
        assertFalse("get" in labels("package demo\nfun g() { ge| }"), "no `get` at a plain statement start")
    }

    // --- I: try continuations ---

    @Test
    fun catchFinallyOfferedAfterTryBlock() {
        val ls = labels("package demo\nfun g() { try { } cat| }")
        assertTrue("catch" in ls, "expected `catch` after a try block; got $ls")
        assertTrue("finally" in labels("package demo\nfun g() { try { } fin| }"), "expected `finally` after a try block")
    }

    // --- J: where clause + context receivers + compound declarations ---

    @Test
    fun whereOfferedAfterGenericSignature() {
        assertTrue("where" in labels("package demo\nfun <T> f(): T wh|"), "expected `where` after a generic function signature")
    }

    @Test
    fun contextOfferedAtMemberAndTopLevel() {
        assertTrue("context" in labels("package demo\nclass C { cont| }"), "expected `context` in a class body")
        assertTrue("context" in labels("package demo\ncont|"), "expected `context` at top level")
    }

    @Test
    fun compoundDeclarationHeadsOffered() {
        assertTrue("data class" in labels("package demo\nda|"), "expected `data class` at top level")
        assertTrue("sealed interface" in labels("package demo\nseal|"), "expected `sealed interface` at top level")
        assertTrue("fun interface" in labels("package demo\nfun|"), "expected `fun interface` at top level")
    }

    @Test
    fun varargNotOfferedAsMemberModifier() {
        // `vararg` applies to a parameter, never a class member — it must not appear in a class body.
        assertFalse("vararg" in labels("package demo\nclass C { vara| }"), "no `vararg` as a class-member modifier")
    }

    companion object {
        val srcDir: Path = tempProject(emptyMap())
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
    }
}
