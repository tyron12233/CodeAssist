package dev.ide.lang.kotlin

import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Type completion at a `when (subject) { is <caret> }` branch on a SEALED subject: the subject's subclasses
 * are surfaced (even ones not otherwise near the top of the type list) and, being assignable to the subject
 * type, rank ahead of every unrelated classifier. A non-sealed / subjectless `when` keeps the plain type list.
 */
class KotlinSealedWhenCompletionTest {

    private fun names(code: String): List<String> =
        runBlocking { analyzer.completeAtCaret(srcDir, "Use.kt", code) }.items.map { it.symbol?.name ?: it.label }

    @Test
    fun sealedSubjectOffersItsSubclasses() {
        val ns = names("package demo\nfun f(s: Shape) = when (s) { is | else -> 0 }\n")
        assertTrue("Circle" in ns, "a sealed subclass must be offered at `is <caret>`; got $ns")
        assertTrue("Square" in ns, "every sealed subclass; got $ns")
        assertTrue("Rect" in ns, "including a data-class subclass; got $ns")
    }

    @Test
    fun sealedSubclassesRankAheadOfUnrelatedTypes() {
        // A subtype is assignable to the subject, so it floats above any type that is NOT (`Unrelated`) — the
        // sealed parent `Shape` is itself assignable and legitimately shares the top tier, so it isn't the bar.
        val ns = names("package demo\nfun f(s: Shape) = when (s) { is | else -> 0 }\n")
        val unrelated = ns.indexOf("Unrelated")
        assertTrue(unrelated >= 0, "the unrelated control type should be offered; got $ns")
        for (sub in listOf("Circle", "Square", "Rect")) {
            val i = ns.indexOf(sub)
            assertTrue(i in 0 until unrelated, "$sub should rank ahead of the unrelated type; got $ns")
        }
    }

    @Test
    fun prefixNarrowsToTheMatchingSubclass() {
        val ns = names("package demo\nfun f(s: Shape) = when (s) { is Ci| else -> 0 }\n")
        assertTrue("Circle" in ns, "a prefixed `is Ci` must still offer the matching subclass; got $ns")
        assertTrue("Square" !in ns, "a non-matching subclass is filtered by the prefix; got $ns")
    }

    companion object {
        val srcDir: Path = tempProject(
            mapOf(
                "Shape.kt" to (
                    "package demo\n" +
                        "sealed class Shape\n" +
                        "class Circle : Shape()\n" +
                        "class Square : Shape()\n" +
                        "data class Rect(val w: Int) : Shape()\n" +
                        "class Unrelated\n"
                    ),
                "Use.kt" to "package demo\n",
            ),
        )
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
    }
}
