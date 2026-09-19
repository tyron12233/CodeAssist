package dev.ide.lang.kotlin

import dev.ide.lang.dom.Severity
import dev.ide.testkit.TestJars
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A Java type parameter's implicit bound is `java.lang.Object`, which every type satisfies.
 *
 * The module sweep reported 19 "Type argument is not within its bounds: should be a subtype of 'Object'"
 * across 14 files. Nothing can violate that bound, so every one of them is a false positive.
 */
class KotlinJavaObjectBoundTest {

    @Test
    fun anArrayListOfAnythingIsWithinItsBound() = clean(
        """
        package demo
        fun f(): ArrayList<String> = ArrayList()
        """,
    )

    @Test
    fun aHashMapOfAnythingIsWithinItsBound() = clean(
        """
        package demo
        fun f(): HashMap<String, Int> = HashMap()
        """,
    )

    @Test
    fun aJavaGenericSpelledByItsJavaNameIsWithinItsBound() = clean(
        """
        package demo
        fun f(): java.util.ArrayList<Int> = java.util.ArrayList()
        """,
    )

    @Test
    fun aNullableTypeArgumentIsWithinAnErasedJavaBound() = clean(
        """
        package demo
        fun f(xs: List<String?>): List<Int?> = emptyList()
        """,
    )

    /** An UNBOUNDED Kotlin type parameter is `T : Any?`, so a nullable argument satisfies it. */
    @Test
    fun aNullableTypeArgumentIsWithinAnUnboundedKotlinParameter() = clean(
        """
        package demo
        fun f(): Pair<String?, Int?> = Pair(null, null)
        """,
    )

    /** A Kotlin `T : Any` DOES reject a nullable argument, and must keep doing so. */
    @Test
    fun aKotlinNonNullBoundStillRejectsANullableArgument() = runBlocking {
        val errors = analyze(
            """
            package demo
            class Box<T : Any>
            fun f(): Box<String?>? = null
            """,
        )
        assertTrue(
            errors.any { it.code == KotlinDiagnosticCodes.UPPER_BOUND_VIOLATED },
            "expected the upper-bound error, got $errors",
        )
    }

    /** The check still fires where a bound is real. */
    @Test
    fun aRealBoundIsStillEnforced() = runBlocking {
        val errors = analyze(
            """
            package demo
            open class Box<T : Number>
            class Bad : Box<String>()
            """,
        )
        assertTrue(
            errors.any { it.code == KotlinDiagnosticCodes.UPPER_BOUND_VIOLATED },
            "expected the upper-bound error, got $errors",
        )
    }

    private fun clean(code: String) = runBlocking {
        val errors = analyze(code)
        assertTrue(errors.isEmpty(), "expected a clean file, got $errors")
    }

    private suspend fun analyze(code: String): List<dev.ide.lang.dom.Diagnostic> {
        val src = tempProject(mapOf("Probe.kt" to code.trimIndent()))
        val analyzer = KotlinSourceAnalyzer(fakeContext(src, listOfNotNull(stdlibJarPath(), jdkJar)))
        val doc = SnippetDoc(code.trimIndent(), DiskFile(src.resolve("Probe.kt")))
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
