package dev.ide.lang.kotlin

import dev.ide.lang.dom.Severity
import dev.ide.testkit.TestJars
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A `suspend` function called inside `runBlocking { }`.
 *
 * Every test in this module opens that way, and the module sweep reports `val errors = analyze(code)` as
 * "Not enough information to infer type variable R" -- on a call to a function with an explicit return type
 * and no type parameters at all.
 *
 * NOT REPRODUCED. Four attempts, each closer to the real thing, all come back clean: a suspend call in
 * `runBlocking`, the builder's result used, a plain call for contrast, and finally this module's exact helper
 * shape with `kotlin-test` and the `dev.ide.lang.dom` jar on the classpath. Whatever triggers it belongs to
 * the full-module sweep rather than to the shape, so the bucket is left standing and these cases are kept as
 * the record of what is already correct. Anyone picking it up should start from the sweep, not from here.
 */
class KotlinSuspendInBuilderTest {

    @Test
    fun aSuspendCallInRunBlockingInfers() = clean(
        """
        import kotlinx.coroutines.runBlocking
        class Holder {
            fun f() = runBlocking {
                val items = load("x")
                println(items.size)
            }
            private suspend fun load(s: String): List<String> = listOf(s)
        }
        """,
    )

    @Test
    fun theBuildersResultIsUsable() = clean(
        """
        import kotlinx.coroutines.runBlocking
        class Holder {
            fun f(): Int = runBlocking { load("x").size }
            private suspend fun load(s: String): List<String> = listOf(s)
        }
        """,
    )

    @Test
    fun anOrdinaryCallInRunBlockingInfers() = clean(
        """
        import kotlinx.coroutines.runBlocking
        class Holder {
            fun f() = runBlocking {
                val items = plain("x")
                println(items.size)
            }
            private fun plain(s: String): List<String> = listOf(s)
        }
        """,
    )

    /** The exact shape of this module's test helpers, which is where the sweep reports it. */
    @Test
    fun theExactTestHelperShape() = clean(
        """
        import kotlinx.coroutines.runBlocking
        import kotlin.test.assertTrue
        class Holder {
            fun a() = clean("x")
            private fun clean(code: String) = runBlocking {
                val errors = analyze(code)
                assertTrue(errors.isEmpty(), "expected a clean file, got ${'$'}errors")
            }
            private suspend fun analyze(code: String): List<dev.ide.lang.dom.Diagnostic> = emptyList()
        }
        """,
    )

    private fun clean(code: String) = runBlocking {
        val text = "package demo\n" + code.trimIndent() + "\n"
        val src = tempProject(mapOf("Use.kt" to text))
        val analyzer = KotlinSourceAnalyzer(fakeContext(src, listOfNotNull(stdlibJarPath(), jdkJar, coroutinesJar, kotlinTestJar, domJar)))
        val doc = SnippetDoc(text, DiskFile(src.resolve("Use.kt")))
        analyzer.incrementalParser.parseFull(doc)
        val errors = analyzer.analyze(doc.file).diagnostics.filter {
            it.severity == Severity.ERROR && it.code != KotlinDiagnosticCodes.SYNTAX
        }
        assertTrue(errors.isEmpty(), "expected a clean file, got $errors")
    }

    companion object {
        private val jdkJar: java.nio.file.Path? =
            TestJars.jdkBaseJar(java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "codeassist-jdk-jar"))
        private val coroutinesJar: java.nio.file.Path =
            TestJars.onClasspath("kotlinx/coroutines/BuildersKt.class")
        private val kotlinTestJar: java.nio.file.Path =
            TestJars.onClasspath("kotlin/test/AssertionsKt.class")
        private val domJar: java.nio.file.Path =
            TestJars.onClasspath("dev/ide/lang/dom/Diagnostic.class")
    }
}
