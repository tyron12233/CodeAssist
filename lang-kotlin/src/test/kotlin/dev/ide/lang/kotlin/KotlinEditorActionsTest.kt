package dev.ide.lang.kotlin

import dev.ide.analysis.ActionProvider
import dev.ide.analysis.AnalysisTarget
import dev.ide.analysis.EditorActionContext
import dev.ide.analysis.FixContext
import dev.ide.analysis.QuickFix
import dev.ide.index.IndexService
import dev.ide.lang.SourceAnalyzer
import dev.ide.lang.dom.ParsedFile
import dev.ide.lang.dom.TextRange
import dev.ide.lang.kotlin.analysis.KotlinBracesActionProvider
import dev.ide.lang.kotlin.analysis.KotlinExplicitTypeActionProvider
import dev.ide.lang.kotlin.analysis.KotlinExtractFunctionActionProvider
import dev.ide.lang.kotlin.analysis.KotlinFunctionBodyActionProvider
import dev.ide.lang.kotlin.analysis.KotlinIntroduceVariableActionProvider
import dev.ide.lang.kotlin.analysis.KotlinSurroundActionProvider
import dev.ide.model.Module
import dev.ide.vfs.VirtualFile
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Kotlin caret intentions, each driven the way the engine drives it: build the shared
 * [EditorActionContext] for a caret, ask one provider what it offers, then apply the chosen fix's edits.
 *
 * The caret is written as `|` in the fixture and stripped before parsing.
 */
class KotlinEditorActionsTest {

    // ---- surround with ----------------------------------------------------------------------------

    @Test
    fun surroundWrapsTheStatementAtTheCaret() {
        val result = apply(
            "package demo\nfun f() {\n    println(\"hi\")|\n}\n",
            KotlinSurroundActionProvider(),
            "Surround with 'if'",
        )
        assertEquals(
            "package demo\nfun f() {\n    if (true) {\n        println(\"hi\")\n    }\n}\n",
            result,
        )
    }

    @Test
    fun surroundOffersEachWrapper() {
        val titles = titles("package demo\nfun f() {\n    println(\"hi\")|\n}\n", KotlinSurroundActionProvider())
        assertEquals(
            listOf("Surround with 'if'", "Surround with 'try/catch'", "Surround with 'run'"),
            titles,
        )
    }

    @Test
    fun surroundWithTryCatchKeepsTheStatementIndent() {
        val result = apply(
            "package demo\nfun f() {\n    println(\"hi\")|\n}\n",
            KotlinSurroundActionProvider(),
            "Surround with 'try/catch'",
        )
        assertEquals(
            "package demo\nfun f() {\n    try {\n        println(\"hi\")\n    } catch (e: Exception) {\n" +
                "        throw e\n    }\n}\n",
            result,
        )
    }

    @Test
    fun surroundIsNotOfferedOutsideAFunctionBody() {
        assertTrue(titles("package demo\n|\nfun f() {}\n", KotlinSurroundActionProvider()).isEmpty())
    }

    // ---- introduce variable -----------------------------------------------------------------------

    @Test
    fun introduceVariableNamesTheValueAfterTheCall() {
        val result = apply(
            "package demo\nfun g() = 1\nfun f() {\n    println(g|())\n}\n",
            KotlinIntroduceVariableActionProvider(),
            "Introduce local variable 'g1'",
        )
        assertEquals(
            "package demo\nfun g() = 1\nfun f() {\n    val g1 = g()\n    println(g1)\n}\n",
            result,
        )
    }

    @Test
    fun introduceVariableIsNotOfferedOnABareName() {
        val titles = titles(
            "package demo\nfun f() {\n    val a = 1\n    println(a|)\n}\n",
            KotlinIntroduceVariableActionProvider(),
        )
        assertTrue(titles.isEmpty(), "a bare name reference is not worth extracting, got $titles")
    }

    @Test
    fun introduceVariableTakesTheWholeQualifiedCallNotItsReceiver() {
        val result = apply(
            "package demo\nfun f() {\n    println(\"a\".trim|())\n}\n",
            KotlinIntroduceVariableActionProvider(),
            null,
        )
        // The generated name is deduplicated against the buffer, and `trim` occurs in the expression
        // being replaced, so the fresh name is `trim1`.
        assertEquals(
            "package demo\nfun f() {\n    val trim1 = \"a\".trim()\n    println(trim1)\n}\n",
            result,
        )
    }

    // ---- function body form -----------------------------------------------------------------------

    @Test
    fun convertsASingleReturnBlockToAnExpressionBody() {
        val result = apply(
            "package demo\nfun |f(): Int {\n    return 1 + 2\n}\n",
            KotlinFunctionBodyActionProvider(),
            "Convert to expression body",
        )
        assertEquals("package demo\nfun f(): Int = 1 + 2\n", result)
    }

    @Test
    fun convertsAnExpressionBodyToABlock() {
        val result = apply(
            "package demo\nfun |f(): Int = 1 + 2\n",
            KotlinFunctionBodyActionProvider(),
            "Convert to block body",
        )
        assertEquals("package demo\nfun f(): Int {\n    return 1 + 2\n}\n", result)
    }

    @Test
    fun aMultiStatementBlockHasNoExpressionForm() {
        val titles = titles(
            "package demo\nfun |f(): Int {\n    println(\"x\")\n    return 1\n}\n",
            KotlinFunctionBodyActionProvider(),
        )
        assertTrue(titles.isEmpty(), "expected no conversion for a multi-statement body, got $titles")
    }

    @Test
    fun theConversionIsOfferedOnTheSignatureNotDeepInTheBody() {
        val titles = titles(
            "package demo\nfun f(): Int {\n    return 1 |+ 2\n}\n",
            KotlinFunctionBodyActionProvider(),
        )
        assertTrue(titles.isEmpty(), "expected nothing from inside the body, got $titles")
    }

    // ---- braces -----------------------------------------------------------------------------------

    @Test
    fun addsBracesToASingleStatementIfBranch() {
        val result = apply(
            "package demo\nfun f(b: Boolean) {\n    if (b) println|(\"y\")\n}\n",
            KotlinBracesActionProvider(),
            "Add braces to 'if'",
        )
        assertEquals(
            "package demo\nfun f(b: Boolean) {\n    if (b) {\n        println(\"y\")\n    }\n}\n",
            result,
        )
    }

    @Test
    fun removesBracesFromASingleStatementBlock() {
        val result = apply(
            "package demo\nfun f(b: Boolean) {\n    if (b) {\n        println|(\"y\")\n    }\n}\n",
            KotlinBracesActionProvider(),
            "Remove braces from 'if'",
        )
        assertEquals("package demo\nfun f(b: Boolean) {\n    if (b) println(\"y\")\n}\n", result)
    }

    @Test
    fun theElseBranchIsNamedAsElse() {
        val titles = titles(
            "package demo\nfun f(b: Boolean) {\n    if (b) println(\"y\") else println|(\"n\")\n}\n",
            KotlinBracesActionProvider(),
        )
        assertEquals(listOf("Add braces to 'else'"), titles)
    }

    @Test
    fun aLoopBodyIsNamedAfterItsLoop() {
        val titles = titles(
            "package demo\nfun f() {\n    for (i in 1..2) println|(i)\n}\n",
            KotlinBracesActionProvider(),
        )
        assertEquals(listOf("Add braces to 'for'"), titles)
    }

    @Test
    fun bracesAreNotRemovedAroundAMultiLineStatement() {
        val titles = titles(
            "package demo\nfun f(b: Boolean) {\n    if (b) {\n        println|(\n            \"y\",\n        )\n    }\n}\n",
            KotlinBracesActionProvider(),
        )
        assertTrue(titles.isEmpty(), "a multi-line statement should keep its braces, got $titles")
    }

    // ---- explicit type ----------------------------------------------------------------------------

    @Test
    fun specifiesTheInferredTypeOnADeclaration() {
        val result = apply(
            "package demo\nfun f() {\n    val a| = 1\n}\n",
            KotlinExplicitTypeActionProvider(),
            "Specify explicit type",
        )
        assertEquals("package demo\nfun f() {\n    val a: Int = 1\n}\n", result)
    }

    @Test
    fun removesAnExplicitType() {
        val result = apply(
            "package demo\nfun f() {\n    val a|: Int = 1\n}\n",
            KotlinExplicitTypeActionProvider(),
            "Remove explicit type",
        )
        assertEquals("package demo\nfun f() {\n    val a = 1\n}\n", result)
    }

    @Test
    fun theTypeIntentionIsOfferedOnlyOnTheName() {
        val titles = titles("package demo\nfun f() {\n    val a = |1\n}\n", KotlinExplicitTypeActionProvider())
        assertTrue(titles.isEmpty(), "expected nothing with the caret on the initializer, got $titles")
    }

    @Test
    fun aDeclarationWithNoInitializerCannotGainAType() {
        val titles = titles(
            "package demo\nclass C {\n    lateinit var a|\n}\n",
            KotlinExplicitTypeActionProvider(),
        )
        assertTrue(titles.isEmpty(), "nothing to infer from, got $titles")
    }

    // ---- extract function -------------------------------------------------------------------------

    @Test
    fun extractsSelectedStatementsIntoAPrivateFunction() {
        val result = applyRange(
            "package demo\nfun f() {\n[    println(\"a\")\n    println(\"b\")]\n}\n",
            KotlinExtractFunctionActionProvider(),
            "Extract function 'extracted'",
        )
        assertEquals(
            "package demo\nfun f() {\n    extracted()\n}\n\nprivate fun extracted() {\n" +
                "    println(\"a\")\n    println(\"b\")\n}\n",
            result,
        )
    }

    @Test
    fun aLocalTheSelectionReadsBecomesAParameter() {
        val result = applyRange(
            "package demo\nfun f() {\n    val n: Int = 1\n[    println(n)]\n}\n",
            KotlinExtractFunctionActionProvider(),
            "Extract function 'extracted'",
        )
        assertTrue("extracted(n)" in result, "expected the local passed as an argument:\n$result")
        assertTrue("private fun extracted(n: Int) {" in result, "expected a typed parameter:\n$result")
    }

    @Test
    fun aLocalDeclaredInsideAndUsedAfterIsNotExtractable() {
        // Handing the value back needs a return plus a declaration at the call site, so this is not offered.
        val titles = titlesRange(
            "package demo\nfun f() {\n[    val n = 1]\n    println(n)\n}\n",
            KotlinExtractFunctionActionProvider(),
        )
        assertTrue(titles.isEmpty(), "expected no extraction, got $titles")
    }

    @Test
    fun aLocalDeclaredAndUsedOnlyInsideNeedsNoParameter() {
        val result = applyRange(
            "package demo\nfun f() {\n[    val n = 1\n    println(n)]\n}\n",
            KotlinExtractFunctionActionProvider(),
            "Extract function 'extracted'",
        )
        assertTrue("private fun extracted() {" in result, "expected no parameters:\n$result")
        assertTrue("val n = 1" in result, result)
    }

    @Test
    fun aPartialSelectionWidensToWholeStatements() {
        val result = applyRange(
            "package demo\nfun f() {\n    prin[tln(\"a\")\n    printl]n(\"b\")\n}\n",
            KotlinExtractFunctionActionProvider(),
            "Extract function 'extracted'",
        )
        assertTrue("println(\"a\")\n    println(\"b\")" in result, "both statements expected:\n$result")
        assertTrue("    extracted()\n}" in result, "one call replaces both:\n$result")
    }

    @Test
    fun theNameIsDeduplicatedAgainstTheFile() {
        val result = applyRange(
            "package demo\nfun extracted() {}\nfun f() {\n[    println(\"a\")]\n}\n",
            KotlinExtractFunctionActionProvider(),
            "Extract function 'extracted1'",
        )
        assertTrue("private fun extracted1()" in result, result)
    }

    @Test
    fun extractIsNotOfferedWithoutASelection() {
        assertTrue(
            titles("package demo\nfun f() {\n    println|(\"a\")\n}\n", KotlinExtractFunctionActionProvider())
                .isEmpty(),
        )
    }

    // ---- harness ----------------------------------------------------------------------------------

    /** The intention titles [provider] offers at the `|` caret in [code]. */
    private fun titles(code: String, provider: ActionProvider): List<String> = runBlocking {
        val (clean, offset) = split(code)
        provider.actions(contextFor(clean, offset)).map { it.title }
    }

    /**
     * Apply the intention named [title] (or the only one, when null) at the `|` caret and return the
     * resulting text. Edits land descending by offset, which is the host's contract.
     */
    private fun apply(code: String, provider: ActionProvider, title: String?): String = runBlocking {
        val (clean, offset) = split(code)
        val ctx = contextFor(clean, offset)
        val fixes = provider.actions(ctx)
        val fix: QuickFix = when {
            title != null -> fixes.firstOrNull { it.title == title }
                ?: error("no intention titled \"$title\"; offered ${fixes.map { f -> f.title }}")
            else -> fixes.singleOrNull() ?: error("expected one intention, got ${fixes.map { f -> f.title }}")
        }
        val edits = fix.computeEdits(Ctx(ctx.target)).edits.values.flatten()
        val sb = StringBuilder(clean)
        for (e in edits.sortedByDescending { it.offset }) {
            sb.replace(e.offset, e.offset + e.oldLength, e.newText.toString())
        }
        sb.toString()
    }

    /** The titles [provider] offers for the `[`..`]` selection in [code]. */
    private fun titlesRange(code: String, provider: ActionProvider): List<String> = runBlocking {
        val (clean, range) = splitRange(code)
        provider.actions(contextFor(clean, range.start, range.end)).map { it.title }
    }

    /** Apply the intention named [title] for the `[`..`]` selection and return the resulting text. */
    private fun applyRange(code: String, provider: ActionProvider, title: String): String = runBlocking {
        val (clean, range) = splitRange(code)
        val ctx = contextFor(clean, range.start, range.end)
        val fixes = provider.actions(ctx)
        val fix = fixes.firstOrNull { it.title == title }
            ?: error("no intention titled \"$title\"; offered ${fixes.map { f -> f.title }}")
        val edits = fix.computeEdits(Ctx(ctx.target)).edits.values.flatten()
        val sb = StringBuilder(clean)
        for (e in edits.sortedByDescending { it.offset }) {
            sb.replace(e.offset, e.offset + e.oldLength, e.newText.toString())
        }
        sb.toString()
    }

    private fun splitRange(code: String): Pair<String, TextRange> {
        val open = code.indexOf('[')
        require(open >= 0) { "the fixture must mark the selection with [ and ]" }
        val withoutOpen = code.removeRange(open, open + 1)
        val close = withoutOpen.indexOf(']')
        require(close >= 0) { "the fixture must close the selection with ]" }
        return withoutOpen.removeRange(close, close + 1) to TextRange(open, close)
    }

    private fun split(code: String): Pair<String, Int> {
        val at = code.indexOf('|')
        require(at >= 0) { "the fixture must mark the caret with |" }
        return code.removeRange(at, at + 1) to at
    }

    private suspend fun contextFor(code: String, offset: Int, end: Int = offset): EditorActionContext {
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve("Use.kt")))
        val parsed = analyzer.incrementalParser.parseFull(doc)
        analyzer.analyze(doc.file)
        return EditorActionContext.of(
            Target(parsed, doc.file, analyzer),
            TextRange(offset, end),
            KotlinLanguageBackend.LANGUAGE_ID,
        )
    }

    private class Target(
        override val parsed: ParsedFile,
        override val file: VirtualFile,
        override val resolver: SourceAnalyzer,
    ) : AnalysisTarget {
        override val documentVersion = 1L
        override val index: IndexService get() = error("the intentions under test do not query the index")
        override val module: Module get() = error("the intentions under test do not read the module")
        override fun checkCanceled() {}
    }

    private class Ctx(override val target: AnalysisTarget) : FixContext {
        override fun checkCanceled() {}
    }

    companion object {
        val srcDir: Path = tempProject(mapOf("Local.kt" to "package demo\n"))
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
    }
}
