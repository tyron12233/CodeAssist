package dev.ide.lang.kotlin

import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Editor smart casts driven purely by an `is` check's flow position (`if (x is T) x.‹member›` resolves against
 * `T`), across the two editor dimensions the user cares about:
 *  - **completion**: after the guard, the narrowed type's members are offered at `x.`;
 *  - **diagnostics**: the same access is NOT a false `kt.unresolved`, and a type that fits only after the
 *    narrowing is NOT a false `kt.typeMismatch`.
 * Each positive case is paired with a negative one (the SAME access WITHOUT the guard) so the test proves the
 * narrowing is doing the work: `bark` is a `Dog` member, absent on the `Animal` static type.
 */
class KotlinSmartCastTest {

    private fun labels(code: String): List<String> =
        runBlocking { analyzer.completeAtCaret(srcDir, "Use.kt", code) }.items.map { it.symbol?.name ?: it.label }

    private fun codes(code: String): List<String?> = runBlocking {
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve("D.kt")))
        analyzer.incrementalParser.parseFull(doc)
        analyzer.analyze(doc.file).diagnostics.map { it.code }
    }

    // -------------------------------------------------------------------------------------------------------
    // A. Completion: the narrowed type's members appear at `x.` after the guard.
    // -------------------------------------------------------------------------------------------------------

    @Test fun ifIsThenBranchNarrows() {
        assertTrue("bark" in labels("package demo\nfun f(a: Animal) { if (a is Dog) { a.ba| } }"), "if (a is Dog) → a is Dog")
    }

    @Test fun ifIsSingleStatementBranchNarrows() {
        // No braces: the then is the bare `a.ba‹dummy›` expression (the user's exact reported shape).
        assertTrue("bark" in labels("package demo\nfun f(a: Animal) { if (a is Dog) a.ba| }"), "single-statement then narrows")
    }

    @Test fun elseOfNegatedIsNarrows() {
        assertTrue("bark" in labels("package demo\nfun f(a: Animal) { if (a !is Dog) {} else { a.ba| } }"), "else of `!is` narrows")
    }

    @Test fun andShortCircuitRhsNarrows() {
        assertTrue("bark" in labels("package demo\nfun f(a: Animal) { val b = a is Dog && a.ba| }"), "`x is T && x.member` narrows the RHS")
    }

    // (The `x !is T || x.member` RHS narrowing is covered under diagnostics in section B. Member-access
    // COMPLETION after `||` is a separate, pre-existing completion-classifier limitation: even an unguarded
    // `false || a.member` isn't offered member candidates, so it can't be asserted via labels here.)

    @Test fun earlyReturnGuardNarrowsRest() {
        assertTrue("bark" in labels("package demo\nfun f(a: Animal) { if (a !is Dog) return\n  a.ba| }"), "`if (a !is Dog) return` narrows the rest")
    }

    @Test fun earlyThrowGuardNarrowsRest() {
        assertTrue("bark" in labels("package demo\nfun f(a: Animal) { if (a !is Dog) throw RuntimeException()\n  a.ba| }"), "a throwing guard narrows the rest")
    }

    @Test fun whenSubjectBranchNarrows() {
        assertTrue("radius" in labels("package demo\nfun f(s: Shape) { when (s) { is Circle -> { s.rad| } } }"), "when (s) { is Circle -> } narrows s")
    }

    @Test fun whileBodyNarrows() {
        assertTrue("bark" in labels("package demo\nfun f(a: Animal) { while (a is Dog) { a.ba| } }"), "while (a is Dog) body narrows")
    }

    @Test fun nullableReceiverNarrowsToNonNull() {
        assertTrue("bark" in labels("package demo\nfun f(a: Animal?) { if (a is Dog) { a.ba| } }"), "is-check on a nullable narrows to non-null Dog")
    }

    @Test fun narrowedMemberChainResolves() {
        // `a.legs` is Int → `.toLong` resolves only because `a` smart-casts to Dog first.
        assertTrue("toLong" in labels("package demo\nfun f(a: Animal) { if (a is Dog) { a.legs.toLo| } }"), "chained member off a smart-cast resolves")
    }

    // -------------------------------------------------------------------------------------------------------
    // A'. Completion negatives: WITHOUT the narrowing the Dog member is not offered (smart cast does the work).
    // -------------------------------------------------------------------------------------------------------

    @Test fun noNarrowingNoDogMember() {
        assertTrue("bark" !in labels("package demo\nfun f(a: Animal) { a.ba| }"), "no guard → only Animal members")
    }

    @Test fun outsideThenBranchNotNarrowed() {
        assertTrue("bark" !in labels("package demo\nfun f(a: Animal) { if (a is Dog) {}\n  a.ba| }"), "after an empty then (no exit) a is still Animal")
    }

    @Test fun wrongTypeBranchDoesNotNarrowToOther() {
        assertTrue("bark" !in labels("package demo\nfun f(a: Animal) { if (a is Cat) { a.ba| } }"), "is Cat narrows to Cat, not Dog")
    }

    // -------------------------------------------------------------------------------------------------------
    // B. Diagnostics: the narrowed access is not a false `kt.unresolved`; the unguarded one still flags.
    // -------------------------------------------------------------------------------------------------------

    @Test fun narrowedMemberAccessIsNotUnresolved() {
        assertTrue("kt.unresolved" !in codes("package demo\nfun f(a: Animal) { if (a is Dog) { a.bark() } }"), "a.bark() resolves under the narrowing")
    }

    @Test fun unguardedMemberAccessIsUnresolved() {
        assertTrue("kt.unresolved" in codes("package demo\nfun f(a: Animal) { a.bark() }"), "a.bark() on Animal is genuinely unresolved")
    }

    @Test fun earlyReturnGuardSuppressesUnresolved() {
        assertTrue("kt.unresolved" !in codes("package demo\nfun f(a: Animal) { if (a !is Dog) return\n  a.bark() }"), "guarded access resolves")
    }

    @Test fun whenBranchSuppressesUnresolved() {
        assertTrue("kt.unresolved" !in codes("package demo\nfun f(s: Shape) { when (s) { is Circle -> s.radius()\n  else -> {} } }"), "when-branch access resolves")
    }

    @Test fun orShortCircuitRhsNarrowsResolves() {
        // `x !is T || x.member` narrows the `||` RHS (asserted via diagnostics; see the note in section A).
        assertTrue("kt.unresolved" !in codes("package demo\nfun f(a: Animal) { val b = a !is Dog || a.bark() }"), "`a !is Dog || a.bark()` resolves")
    }

    @Test fun unguardedOrRhsIsUnresolved() {
        assertTrue("kt.unresolved" in codes("package demo\nfun f(a: Animal) { val b = false || a.bark() }"), "an unguarded `|| a.bark()` is genuinely unresolved")
    }

    @Test fun andShortCircuitRhsResolvesInDiagnostics() {
        assertTrue("kt.unresolved" !in codes("package demo\nfun f(a: Animal) { val b = a is Dog && a.bark() }"), "`a is Dog && a.bark()` resolves")
    }

    // -------------------------------------------------------------------------------------------------------
    // C. Diagnostics: a value that fits only after the narrowing is not a false `kt.typeMismatch`.
    // -------------------------------------------------------------------------------------------------------

    @Test fun narrowedAssignmentIsNotTypeMismatch() {
        assertTrue("kt.typeMismatch" !in codes("package demo\nfun f(a: Animal) { if (a is Dog) { val d: Dog = a } }"), "a is assignable to Dog under the narrowing")
    }

    @Test fun unguardedAssignmentIsTypeMismatch() {
        assertTrue("kt.typeMismatch" in codes("package demo\nfun f(a: Animal) { val d: Dog = a }"), "Animal is not assignable to Dog without a narrowing")
    }

    companion object {
        val srcDir: Path = tempProject(
            mapOf(
                "Animals.kt" to """
                    package demo
                    open class Animal { fun breathe(): Unit = Unit }
                    class Dog : Animal() {
                        fun bark(): String = "woof"
                        var legs: Int = 4
                    }
                    class Cat : Animal() { fun meow(): String = "meow" }
                """.trimIndent(),
                "Shapes.kt" to """
                    package demo
                    sealed class Shape
                    class Circle : Shape() { fun radius(): Double = 1.0 }
                    class Square : Shape() { fun side(): Int = 1 }
                """.trimIndent(),
            ),
        )
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir, libJars = listOf(stdlibJarPath())))
    }
}
