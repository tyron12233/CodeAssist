package dev.ide.lang.kotlin

import dev.ide.lang.dom.Severity
import dev.ide.testkit.TestJars
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `x as T` written as a STATEMENT narrows `x` to `T` for the rest of the block, because the cast throws when
 * it does not hold. `KotlinSemanticChecks`'s own dispatch table opens each checker that way, and 20+ of this
 * module's `kt.typeMismatch` hits were that one file reporting `KtElement but KtProperty was expected`.
 */
class KotlinCastStatementNarrowingTest {

    @Test
    fun aBareCastStatementNarrowsForTheRestOfTheBlock() = clean(
        """
        open class Base
        class Derived : Base() { fun only(): Int = 1 }
        fun take(d: Derived): Int = d.only()
        fun f(b: Base) {
            b as Derived
            println(b.only())
            println(take(b))
        }
        """,
    )

    @Test
    fun itNarrowsInsideALambda() = clean(
        """
        open class Base
        class Derived : Base() { fun only(): Int = 1 }
        fun run(b: Base, body: (Base) -> Unit) = body(b)
        fun f(b: Base) {
            run(b) { x ->
                x as Derived
                println(x.only())
            }
        }
        """,
    )

    /** `as?` yields null rather than throwing, so it guarantees nothing and must NOT narrow. */
    @Test
    fun aSafeCastStatementDoesNotNarrow() = runBlocking {
        val errors = analyze(
            """
            open class Base
            class Derived : Base() { fun only(): Int = 1 }
            fun f(b: Base) {
                b as? Derived
                println(b.only())
            }
            """,
        )
        assertTrue(errors.isNotEmpty(), "`as?` must not narrow, but the file came back clean")
    }

    /** The narrowing only applies AFTER the cast, not before it. */
    @Test
    fun itDoesNotNarrowBeforeTheCast() = runBlocking {
        val errors = analyze(
            """
            open class Base
            class Derived : Base() { fun only(): Int = 1 }
            fun f(b: Base) {
                println(b.only())
                b as Derived
            }
            """,
        )
        assertTrue(errors.isNotEmpty(), "a use before the cast must still be an error, got $errors")
    }

    private fun clean(code: String) = runBlocking {
        val errors = analyze(code)
        assertTrue(errors.isEmpty(), "expected a clean file, got $errors")
    }

    private suspend fun analyze(code: String): List<dev.ide.lang.dom.Diagnostic> {
        val text = "package demo\n" + code.trimIndent() + "\n"
        val src = tempProject(mapOf("Use.kt" to text))
        val analyzer = KotlinSourceAnalyzer(fakeContext(src, listOfNotNull(stdlibJarPath(), jdkJar)))
        val doc = SnippetDoc(text, DiskFile(src.resolve("Use.kt")))
        analyzer.incrementalParser.parseFull(doc)
        return analyzer.analyze(doc.file).diagnostics.filter {
            it.severity == Severity.ERROR && it.code != KotlinDiagnosticCodes.SYNTAX
        }
    }

    companion object {
        private val jdkJar: java.nio.file.Path? =
            TestJars.jdkBaseJar(java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "codeassist-jdk-jar"))
    }
}
