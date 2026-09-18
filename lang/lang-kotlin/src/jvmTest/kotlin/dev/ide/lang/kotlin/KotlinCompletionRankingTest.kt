package dev.ide.lang.kotlin

import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * IntelliJ-like ordering + origin for the completion popup:
 *  - a top-level callable shows the package it comes from (the popup's right-aligned origin / `container`);
 *  - on `receiver.`, the receiver's OWN members rank above extensions, project-source extensions above
 *    library extensions, and the ubiquitous universal scope functions (`let`/`run`/`also`/`apply`/…) and the
 *    `Object` methods (`equals`/`hashCode`/`toString`) sort to the bottom — they no longer win on name length.
 */
class KotlinCompletionRankingTest {

    private fun items(file: String, code: String) =
        runBlocking { analyzer.completeAtCaret(srcDir, file, code) }.items

    @Test
    fun topLevelFunctionShowsItsPackageAsContainer() {
        // A library top-level callable (println) carries its package; a project-source one carries the
        // declaring file's package. The popup renders this as the right-aligned origin.
        val println = items("Use.kt", "fun f() { printl| }").firstOrNull { it.symbol?.name == "println" }
        assertNotNull(println, "println should be offered")
        assertTrue(println.container == "kotlin.io", "top-level callable shows its package; container=${println.container}")

        val mine = items("Use.kt", "package demo\nfun f() { greetT| }").firstOrNull { it.symbol?.name == "greetTop" }
        assertNotNull(mine, "source top-level greetTop should be offered")
        assertTrue(mine.container == "demo", "source top-level shows its package; container=${mine.container}")
    }

    @Test
    fun typeCompletionShowsItsPackageAndNoRedundantDetail() {
        // A type candidate (class/interface/…) must surface its package as the origin so two same-named,
        // unimported types are distinguishable — and must NOT repeat its own name as the grayed detail line.
        val greeter = items("Other.kt", "package other\nfun f() { Greet| }").firstOrNull { it.label == "Greeter" }
        assertNotNull(greeter, "Greeter type should be offered; got ${items("Other.kt", "package other\nfun f() { Greet| }").map { it.label }}")
        assertTrue(greeter.container == "demo", "type shows its package as origin; container=${greeter.container}")
        assertTrue(greeter.detail == null, "a type must not repeat its own name as detail; detail=${greeter.detail}")

        // A built-in type (kotlin.String) likewise shows its package.
        val string = items("U.kt", "fun f() { val s: Stri| }").firstOrNull { it.label == "String" }
        assertNotNull(string, "String type should be offered")
        assertTrue(string.container == "kotlin", "built-in type shows its package; container=${string.container}")
    }

    @Test
    fun ownMembersRankAboveExtensionsOnMemberAccess() {
        // `b.` on a source class: its own members must precede every extension (project + stdlib).
        val ls = items("U.kt", "package demo\nfun f(b: Box) { b.| }").mapNotNull { it.symbol?.name }
        val firstExtension = ls.indexOfFirst { it in EXTENSIONS }
        for (own in listOf("member1", "member2", "prop1")) {
            val at = ls.indexOf(own)
            assertTrue(at in 0 until firstExtension, "own member '$own' (#$at) must precede the first extension (#$firstExtension); got ${ls.take(12)}")
        }
    }

    @Test
    fun projectExtensionsRankAboveUniversalScopeFunctions() {
        // A project-source extension on the receiver type beats the Any-receiver scope functions (let/run/…).
        val ls = items("U.kt", "package demo\nfun f(b: Box) { b.| }").mapNotNull { it.symbol?.name }
        val ext = ls.indexOf("extOne")
        val scope = ls.indexOfFirst { it == "let" || it == "run" || it == "also" || it == "apply" }
        assertTrue(ext >= 0 && scope >= 0, "both a source extension and a scope function should be offered; got ${ls.take(15)}")
        assertTrue(ext < scope, "project extension 'extOne' (#$ext) must precede scope functions (#$scope); got ${ls.take(15)}")
    }

    @Test
    fun objectMethodsSortToTheBottom() {
        // hashCode/equals/toString are on every receiver — they must rank below the type's real members.
        val ls = items("U.kt", "package demo\nfun f(b: Box) { b.| }").mapNotNull { it.symbol?.name }
        val hashCode = ls.indexOf("hashCode")
        val member1 = ls.indexOf("member1")
        assertTrue(hashCode >= 0 && member1 >= 0, "both hashCode and member1 should be offered; got ${ls.take(20)}")
        assertTrue(member1 < hashCode, "real member 'member1' (#$member1) must precede 'hashCode' (#$hashCode)")
    }

    companion object {
        private val EXTENSIONS = setOf("extOne", "extTwo", "let", "run", "also", "apply", "to", "takeIf", "takeUnless", "hashCode", "equals", "toString")
        val srcDir: Path = tempProject(
            mapOf(
                "Box.kt" to "package demo\nclass Box {\n  fun member1() {}\n  fun member2() {}\n  val prop1 = 1\n}\nfun demo.Box.extOne() {}\nfun demo.Box.extTwo() {}\n",
                "Top.kt" to "package demo\nfun greetTop() {}\n",
                "Greeter.kt" to "package demo\nclass Greeter\n",
            ),
        )
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
    }
}
