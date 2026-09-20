package dev.ide.lang.kotlin

import dev.ide.lang.dom.Severity
import dev.ide.testkit.TestJars
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `Set` keeps its shape under `-` and `+`. The stdlib declares `Set<T>.minus`/`plus` returning `Set<T>`,
 * alongside the `Iterable<T>` overloads that return `List<T>`; picking the wrong one made
 * `(this - other) + (other - this)` a `List<String>` where a `Set<String>` was expected.
 */
class KotlinSetOperatorResultTest {

    @Test
    fun setMinusSetIsASet() = clean(
        """
        fun f(a: Set<String>, b: Set<String>): Set<String> = a - b
        """,
    )

    @Test
    fun setPlusSetIsASet() = clean(
        """
        fun f(a: Set<String>, b: Set<String>): Set<String> = a + b
        """,
    )

    @Test
    fun theSymmetricDifferenceIsASet() = clean(
        """
        fun Set<String>.symmetric(other: Set<String>): Set<String> = (this - other) + (other - this)
        """,
    )

    @Test
    fun aHashSetDestinationKeepsItsShape() = clean(
        """
        fun f(rest: List<Pair<String, Int>>, matching: List<Pair<String, Int>>): Set<String> =
            rest.mapTo(HashSet()) { it.first } - matching.mapTo(HashSet()) { it.first }
        """,
    )

    /** A List really is a List, so the same expression on a List must NOT be called a Set. */
    @Test
    fun listMinusListIsStillAList() = clean(
        """
        fun f(a: List<String>, b: List<String>): List<String> = a - b
        """,
    )

    private fun clean(code: String) = runBlocking {
        val text = "package demo\n" + code.trimIndent() + "\n"
        val src = tempProject(mapOf("Use.kt" to text))
        val analyzer = KotlinSourceAnalyzer(fakeContext(src, listOfNotNull(stdlibJarPath(), jdkJar)))
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
    }
}
