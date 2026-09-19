package dev.ide.lang.kotlin

import dev.ide.lang.dom.Severity
import dev.ide.testkit.TestJars
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A nested type whose simple name collides with a built-in, on BOTH sides of a comparison.
 *
 * `object KotlinPackageRewrite { class Result; fun rewrite(): Result? }` is this module's own code. The
 * returned value inferred the nested `Result` while the declared return type resolved to `kotlin.Result`,
 * because the declared side was resolved without the enclosing class -- and the message rendered both as
 * simple names: "inferred type is Result but Result? was expected".
 */
class KotlinNestedTypeShadowsBuiltinTest {

    @Test
    fun aNestedTypeShadowingABuiltinMatchesItsOwnReturnType() = clean(
        """
        object Rewrite {
            class Result(val text: String)
            fun run(x: String): Result? {
                if (x.isEmpty()) return null
                return Result(x)
            }
        }
        """,
    )

    @Test
    fun theSameHoldsForAPropertyInitializer() = clean(
        """
        object Rewrite {
            class Result(val text: String)
            val cached: Result = Result("x")
        }
        """,
    )

    @Test
    fun theSameHoldsForAParameterDefault() = clean(
        """
        object Rewrite {
            class Result(val text: String)
            fun run(r: Result = Result("x")): String = r.text
        }
        """,
    )

    /** A genuine mismatch is still reported. */
    @Test
    fun arealMismatchIsStillReported() = runBlocking {
        val errors = analyze(
            """
            object Rewrite {
                class Result(val text: String)
                fun run(): Result = "not a result"
            }
            """,
        )
        assertTrue(
            errors.any { it.code == KotlinDiagnosticCodes.TYPE_MISMATCH },
            "a real mismatch must still be reported, got $errors",
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
