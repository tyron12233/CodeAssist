package dev.ide.lang.kotlin

import dev.ide.lang.dom.Diagnostic
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Reproduction probe for the lambda re-entrancy starvation (Bug #2): a member access on a lambda parameter
 * (`it.name`, `row.name`) must never be falsely flagged `kt.unresolved`, even in nested / generic-higher-order
 * shapes where typing the lambda parameter requires resolving the enclosing call while that call is itself
 * mid-resolution. Asserts no `kt.unresolved` lands on the lambda-parameter member access.
 */
class KotlinLambdaReentrancyTest {

    companion object {
        private val srcDir: Path = tempProject(emptyMap())
        private val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
    }

    private fun unresolvedIn(code: String): List<Diagnostic> {
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve("Probe.kt")))
        return runBlocking {
            analyzer.incrementalParser.parseFull(doc)
            analyzer.analyze(doc.file).diagnostics
        }.filter { it.code == "kt.unresolved" }
    }

    private fun assertNoUnresolvedOn(code: String, member: String) {
        val diags = unresolvedIn(code)
        val onMember = diags.filter { code.substring(it.range.start, it.range.end) == member }
        assertTrue(onMember.isEmpty(), "false-positive unresolved on '$member' in:\n$code\n→ $diags")
    }

    @Test fun forEachItDotMember() = assertNoUnresolvedOn(
        "package demo\ndata class Item(val name: String)\nfun f(xs: List<Item>) { xs.forEach { println(it.name) } }",
        "name",
    )

    @Test fun mapItDotMember() = assertNoUnresolvedOn(
        "package demo\ndata class Item(val name: String)\nfun f(xs: List<Item>) { xs.map { it.name } }",
        "name",
    )

    @Test fun namedLambdaParamDotMember() = assertNoUnresolvedOn(
        "package demo\ndata class Item(val name: String)\nfun f(xs: List<Item>) { xs.forEach { row -> println(row.name) } }",
        "name",
    )

    @Test fun nestedLambdasItDotMember() = assertNoUnresolvedOn(
        "package demo\ndata class Item(val name: String)\nfun g(block: () -> Unit) {}\nfun f(xs: List<Item>) { g { xs.forEach { println(it.name) } } }",
        "name",
    )

    @Test fun genericHigherOrderWithSiblingArg() = assertNoUnresolvedOn(
        "package demo\ndata class Item(val name: String)\nfun <T> consume(seed: T, block: (T) -> Unit) {}\nfun f() { consume(Item(\"x\")) { println(it.name) } }",
        "name",
    )

    @Test fun letChainItDotMember() = assertNoUnresolvedOn(
        "package demo\ndata class Item(val name: String)\nfun f(i: Item) { i.let { it.name.length } }",
        "name",
    )
}
