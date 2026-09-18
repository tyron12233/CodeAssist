package dev.ide.lang.kotlin

import dev.ide.kotlin.syntax.psi.KtNamedFunction
import dev.ide.kotlin.syntax.psi.KtProperty
import dev.ide.lang.kotlin.interp.KotlinTreeResolver
import dev.ide.lang.kotlin.interp.ResolvedFunction
import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.lang.kotlin.parse.KotlinParserHost
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The preview lowering's half of the `when (this) { is Sub -> … }` smart cast — see
 * [KotlinThisSubjectSmartCastTest] for the editor's.
 *
 * The two halves resolve an extension receiver independently: the editor walks the PSI in
 * `computeImplicitReceiversAt`, the lowering pushes a `ReceiverScope` in `lowerFunction`. So a narrowing
 * taught to one is invisible to the other, and a green editor with a blank preview is exactly the state this
 * pins against. Since 3.14 a reachable function that does not lower cleanly REFUSES the whole preview, so
 * the Nimbus Weather template's `ForecastException.body()` blanked every screen that could show an error
 * with `in body: unresolved name \`statusCode\``.
 */
class ThisSubjectNarrowingLoweringTest {

    private val model = """
        package demo
        sealed class Shape {
            class Circle(val segments: Int) : Shape() { fun radius(): Double = 1.0 }
            class Square : Shape() { fun side(): Int = 1 }
        }
    """.trimIndent()

    /** Lower the named declaration (function or top-level property) of [code] against [model]. */
    private fun lower(name: String, code: String): ResolvedFunction {
        val dir = tempProject(mapOf("Shapes.kt" to model, "Use.kt" to code))
        val service = KotlinSymbolService(sourceRoots = listOf(DiskFile(dir)), classpathJars = emptyList())
        val kt = KotlinParserHost.parse("Use.kt", code)
        val parsed = KotlinParsedFile(kt, DiskFile(dir.resolve("Use.kt")), 0)
        val resolver = KotlinTreeResolver(kt, parsed, service)
        val fn = kt.declarations.filterIsInstance<KtNamedFunction>().firstOrNull { it.name == name }
        if (fn != null) return resolver.lowerFunction(fn)
        val prop = assertNotNull(
            kt.declarations.filterIsInstance<KtProperty>().firstOrNull { it.name == name },
            "no declaration named $name",
        )
        return resolver.lowerTopLevelProperty(prop)
    }

    private fun assertLowersCleanly(fn: ResolvedFunction, what: String) {
        assertTrue(
            fn.diagnostics.isEmpty(),
            "$what should lower with no gaps, got: ${fn.diagnostics.map { it.reason }}",
        )
    }

    @Test fun whenThisNarrowsABareMemberFunctionCall() {
        assertLowersCleanly(
            lower(
                "area",
                """
                package demo
                fun Shape.area(): Double = when (this) {
                    is Shape.Circle -> radius()
                    else -> 0.0
                }
                """.trimIndent(),
            ),
            "`when (this) { is Circle -> radius() }`",
        )
    }

    @Test fun whenThisNarrowsABareMemberProperty() {
        assertLowersCleanly(
            lower(
                "detail",
                """
                package demo
                fun Shape.detail(): Int = when (this) {
                    is Shape.Circle -> segments
                    else -> 0
                }
                """.trimIndent(),
            ),
            "a bare member PROPERTY under the narrowing",
        )
    }

    @Test fun whenThisNarrowsAnExplicitThisMember() {
        assertLowersCleanly(
            lower(
                "area",
                """
                package demo
                fun Shape.area(): Double = when (this) {
                    is Shape.Circle -> this.radius()
                    else -> 0.0
                }
                """.trimIndent(),
            ),
            "the explicit `this.member` spelling",
        )
    }

    /** The `forecastOrNull` shape: an extension PROPERTY getter, whose receiver the lowering pushes from a
     *  different call site than a function's. */
    @Test fun whenThisNarrowsInAnExtensionPropertyGetter() {
        assertLowersCleanly(
            lower(
                "sides",
                """
                package demo
                val Shape.sides: Int get() = when (this) {
                    is Shape.Circle -> segments
                    else -> 4
                }
                """.trimIndent(),
            ),
            "an extension property getter's `when (this)`",
        )
    }

    @Test fun ifThisIsNarrowsTheThenBranch() {
        assertLowersCleanly(
            lower(
                "area",
                """
                package demo
                fun Shape.area(): Double {
                    if (this is Shape.Circle) return radius()
                    return 0.0
                }
                """.trimIndent(),
            ),
            "`if (this is Circle)`",
        )
    }

    /** The `if` form reaching a member PROPERTY: the call form above resolves through the shared resolver,
     *  a property read through the lowering's own receiver stack, so both spellings need covering. */
    @Test fun ifThisIsNarrowsABarePropertyRead() {
        assertLowersCleanly(
            lower(
                "detail",
                """
                package demo
                fun Shape.detail(): Int {
                    if (this is Shape.Circle) return segments
                    return 0
                }
                """.trimIndent(),
            ),
            "`if (this is Circle) return segments`",
        )
    }

    /** A comma branch does not smart-cast in Kotlin, so the lowering must still report the gap rather than
     *  resolve against an arbitrary one of the two types. */
    @Test fun aCommaBranchStillReportsTheGap() {
        val fn = lower(
            "detail",
            """
            package demo
            fun Shape.detail(): Int = when (this) {
                is Shape.Circle, is Shape.Square -> segments
                else -> 0
            }
            """.trimIndent(),
        )
        assertTrue(
            fn.diagnostics.any { "segments" in it.reason },
            "`is Circle, is Square ->` must NOT narrow; got ${fn.diagnostics.map { it.reason }}",
        )
    }
}
