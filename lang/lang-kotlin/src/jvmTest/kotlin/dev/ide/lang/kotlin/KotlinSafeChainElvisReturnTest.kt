package dev.ide.lang.kotlin

import dev.ide.lang.dom.Severity
import dev.ide.testkit.TestJars
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `val t = x?.y?.z ?: return` proves `x != null` for the rest of the function.
 *
 * If `x` were null the whole chain would be null and the function would have returned, so Kotlin smart-casts
 * `x` afterwards. `val text = leaf?.containingFile?.text ?: return "" to null` opens several functions in
 * this module, and every later use of `leaf` was reported as a nullable receiver.
 */
class KotlinSafeChainElvisReturnTest {

    @Test
    fun aSafeChainElvisReturnNarrowsTheRoot() = clean(
        """
        class Node(val parent: Node?, val text: String)
        fun f(leaf: Node?): String {
            val t = leaf?.parent?.text ?: return ""
            return t + leaf.text
        }
        """,
    )

    @Test
    fun aSingleSafeCallElvisReturnNarrowsTheRoot() = clean(
        """
        class Node(val parent: Node?, val text: String)
        fun f(leaf: Node?): String {
            val t = leaf?.text ?: return ""
            return t + leaf.text
        }
        """,
    )

    @Test
    fun theSameWithElvisThrow() = clean(
        """
        class Node(val parent: Node?, val text: String)
        fun f(leaf: Node?): String {
            val t = leaf?.parent?.text ?: error("no")
            return t + leaf.text
        }
        """,
    )

    /**
     * Only the ROOT is claimed here. The intermediate links are non-null too -- `leaf?.parent?.text` being
     * non-null means `leaf.parent` was -- and Kotlin smart-casts a stable `val` there, so reporting
     * `leaf.parent.text` is a residual false positive rather than correct behaviour. It is NOT asserted
     * either way: the path machinery matches by text, with a `.` where the chain writes `?.`, so claiming it
     * needs that normalization first.
     */

    /** Without the guard it is still flagged. */
    @Test
    fun anUnguardedUseIsStillFlagged() = runBlocking {
        val errors = analyze(
            """
            class Node(val parent: Node?, val text: String)
            fun f(leaf: Node?): String = leaf.text
            """,
        )
        assertTrue(
            errors.any { it.code == KotlinDiagnosticCodes.UNSAFE_NULLABLE },
            "an unguarded nullable receiver must still be flagged, got $errors",
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
