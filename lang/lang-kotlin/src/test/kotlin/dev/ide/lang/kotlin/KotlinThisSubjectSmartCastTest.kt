package dev.ide.lang.kotlin

import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Smart-casting the **implicit receiver**: `when (this) { is Sub -> … }` and `if (this is Sub) …` inside an
 * extension function or extension property, where the branch then reaches a member of `Sub` by BARE NAME.
 *
 * [KotlinSmartCastTest] covers the same narrowings for a named subject (`when (s) { is Circle -> s.radius() }`).
 * The `this` subject was a hole in two independent places, and both had to be closed for a bare name to work:
 *  - [whenSubjectName] only accepted a subject `val` or a simple NAME reference, so a `KtThisExpression`
 *    subject narrowed nothing at all (not even for an explicit `this.member`);
 *  - narrowings are keyed by name and consulted from `typeOfName`, but a bare `radius()` never goes through
 *    `typeOfName` — it resolves against the IMPLICIT RECEIVERS, which were computed from the declared
 *    extension receiver type and knew nothing about the narrowing.
 *
 * Reported against the Nimbus Weather store template, whose `ForecastException.body()` and
 * `ForecastUiState.forecastOrNull` are exactly these two shapes; every preview reaching them was refused with
 * `in body: unresolved name \`statusCode\``.
 */
class KotlinThisSubjectSmartCastTest {

    private fun codes(code: String): List<String?> = runBlocking {
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve("D.kt")))
        analyzer.incrementalParser.parseFull(doc)
        analyzer.analyze(doc.file).diagnostics.map { it.code }
    }

    private fun labels(code: String): List<String> =
        runBlocking { analyzer.completeAtCaret(srcDir, "Use.kt", code) }.items.map { it.symbol?.name ?: it.label }

    // -------------------------------------------------------------------------------------------------------
    // A. `when (this)` in an extension FUNCTION.
    // -------------------------------------------------------------------------------------------------------

    @Test fun whenThisBranchResolvesABareMemberFunction() {
        assertTrue(
            "kt.unresolved" !in codes(
                "package demo\nfun Shape.f(): Double = when (this) { is Circle -> radius()\n  else -> 0.0 }",
            ),
            "`when (this) { is Circle -> radius() }` must narrow the implicit receiver",
        )
    }

    @Test fun whenThisBranchResolvesABareMemberProperty() {
        assertTrue(
            "kt.unresolved" !in codes(
                "package demo\nfun Shape.f(): Int = when (this) { is Circle -> segments\n  else -> 0 }",
            ),
            "a member PROPERTY by bare name must resolve under the narrowing too",
        )
    }

    @Test fun whenThisBranchResolvesAnExplicitThisMember() {
        assertTrue(
            "kt.unresolved" !in codes(
                "package demo\nfun Shape.f(): Double = when (this) { is Circle -> this.radius()\n  else -> 0.0 }",
            ),
            "the explicit `this.member` spelling must narrow as well",
        )
    }

    /** The control: the same access with no narrowing in scope is a genuine error, so the tests above are
     *  proving the narrowing does the work rather than that nothing is ever reported. */
    @Test fun unguardedBareMemberIsStillUnresolved() {
        assertTrue(
            "kt.unresolved" in codes("package demo\nfun Shape.f(): Double = radius()"),
            "`radius()` on a bare Shape receiver is genuinely unresolved",
        )
    }

    /** A comma branch (`is A, is B`) does not smart-cast in Kotlin, so it must not here either. */
    @Test fun aCommaBranchDoesNotNarrow() {
        assertTrue(
            "kt.unresolved" in codes(
                "package demo\nfun Shape.f(): Double = when (this) { is Circle, is Square -> radius()\n  else -> 0.0 }",
            ),
            "`is Circle, is Square ->` must NOT narrow (Kotlin does not)",
        )
    }

    // -------------------------------------------------------------------------------------------------------
    // B. `when (this)` in an extension PROPERTY getter — the `forecastOrNull` shape.
    // -------------------------------------------------------------------------------------------------------

    @Test fun whenThisNarrowsInAnExtensionPropertyGetter() {
        assertTrue(
            "kt.unresolved" !in codes(
                "package demo\nval Shape.r: Double get() = when (this) { is Circle -> radius()\n  else -> 0.0 }",
            ),
            "an extension property getter's `this` must narrow the same way a function's does",
        )
    }

    @Test fun whenThisNarrowsInAnExpressionBodyExtensionProperty() {
        assertTrue(
            "kt.unresolved" !in codes(
                "package demo\nval Shape.n: Int get() = when (this) { is Circle -> segments\n  else -> 0 }",
            ),
            "a member property through an extension property getter resolves",
        )
    }

    // -------------------------------------------------------------------------------------------------------
    // C. The other `this` narrowings, for parity with the named-subject cases.
    // -------------------------------------------------------------------------------------------------------

    @Test fun ifThisIsNarrowsTheThenBranch() {
        assertTrue(
            "kt.unresolved" !in codes(
                "package demo\nfun Shape.f(): Double { if (this is Circle) { return radius() }\n  return 0.0 }",
            ),
            "`if (this is Circle)` narrows its then branch",
        )
    }

    @Test fun earlyReturnGuardOnThisNarrowsTheRest() {
        assertTrue(
            "kt.unresolved" !in codes(
                "package demo\nfun Shape.f(): Double { if (this !is Circle) return 0.0\n  return radius() }",
            ),
            "`if (this !is Circle) return` narrows the rest of the body",
        )
    }

    // -------------------------------------------------------------------------------------------------------
    // D. Completion sees the narrowed members too, so the editor and the diagnostics agree.
    // -------------------------------------------------------------------------------------------------------

    @Test fun completionOffersNarrowedMembersOnThis() {
        assertTrue(
            "radius" in labels(
                "package demo\nfun Shape.f(): Double = when (this) { is Circle -> this.rad|\n  else -> 0.0 }",
            ),
            "`this.` inside the branch must offer the narrowed type's members",
        )
    }

    companion object {
        val srcDir: Path = tempProject(
            mapOf(
                "Shapes.kt" to """
                    package demo
                    sealed class Shape
                    class Circle : Shape() {
                        val segments: Int = 32
                        fun radius(): Double = 1.0
                    }
                    class Square : Shape() { fun side(): Int = 1 }
                """.trimIndent(),
            ),
        )
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir, libJars = listOf(stdlibJarPath())))
    }
}
