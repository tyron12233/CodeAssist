package dev.ide.lang.kotlin

import dev.ide.lang.dom.Severity
import dev.ide.testkit.TestJars
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A sealed hierarchy whose members are nested INSIDE it, naming their supertype by its simple name.
 *
 * `sealed interface Plan { object Full : Plan }` is the ordinary spelling, and `return Plan.Full` from a
 * function declared to return `Plan` reported "inferred type is Full but Plan was expected".
 */
class KotlinSealedNestedSubtypeTest {

    @Test
    fun aNestedObjectSatisfiesItsEnclosingSealedInterface() = clean(
        """
        sealed interface Plan {
            object Full : Plan
            class Partial(val n: Int) : Plan
        }
        fun f(x: Boolean): Plan = if (x) Plan.Full else Plan.Partial(1)
        fun g(x: Boolean): Plan {
            if (x) return Plan.Full
            return Plan.Partial(1)
        }
        """,
    )

    @Test
    fun theSameInsideAnEnclosingObject() = clean(
        """
        object Holder {
            sealed interface Plan {
                object Full : Plan
                class Partial(val n: Int) : Plan
            }
            fun f(x: Boolean): Plan {
                if (x) return Plan.Full
                return Plan.Partial(1)
            }
        }
        """,
    )

    @Test
    fun aSealedClassSpelledWithParentheses() = clean(
        """
        sealed class Plan {
            object Full : Plan()
            class Partial(val n: Int) : Plan()
        }
        fun f(x: Boolean): Plan = if (x) Plan.Full else Plan.Partial(1)
        """,
    )

    /** An unrelated type is still a mismatch. */
    @Test
    fun anUnrelatedTypeIsStillAMismatch() = runBlocking {
        val errors = analyze(
            """
            sealed interface Plan {
                object Full : Plan
            }
            class Other
            fun f(): Plan = Other()
            """,
        )
        assertTrue(
            errors.any { it.code == KotlinDiagnosticCodes.TYPE_MISMATCH },
            "an unrelated type must still be a mismatch, got $errors",
        )
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
