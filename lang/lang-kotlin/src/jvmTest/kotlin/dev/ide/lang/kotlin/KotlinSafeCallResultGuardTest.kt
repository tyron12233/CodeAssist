package dev.ide.lang.kotlin

import dev.ide.lang.dom.Severity
import dev.ide.testkit.TestJars
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `val e = x?.y; if (e != null) { … }` proves `x` non-null inside the guard.
 *
 * A null `x` makes `e` null, so the guard cannot pass with a null `x`. Kotlin smart-casts on that and this
 * module writes the shape freely -- `val enclosing = leaf?.getParentOfType<KtProperty>(); if (enclosing !=
 * null) { leaf.textRange … }` -- and every such use of the receiver was reported as nullable.
 */
class KotlinSafeCallResultGuardTest {

    @Test
    fun aGuardedSafeCallResultNarrowsTheReceiver() = clean(
        """
        class Node(val parent: Node?, val text: String)
        fun f(leaf: Node?): Int {
            val enclosing = leaf?.parent
            if (enclosing != null) return leaf.text.length
            return 0
        }
        """,
    )

    @Test
    fun theSameThroughALongerChain() = clean(
        """
        class Node(val parent: Node?, val text: String)
        fun f(leaf: Node?): Int {
            val top = leaf?.parent?.parent
            if (top != null) return leaf.text.length
            return 0
        }
        """,
    )

    /** A `var` could have been reassigned between the initializer and the check, so it must NOT narrow. */
    @Test
    fun aVarHoldingTheResultDoesNotNarrow() = runBlocking {
        val errors = analyze(
            """
            class Node(val parent: Node?, val text: String)
            fun f(leaf: Node?, other: Node?): Int {
                var enclosing = leaf?.parent
                enclosing = other
                if (enclosing != null) return leaf.text.length
                return 0
            }
            """,
        )
        assertTrue(
            errors.any { it.code == KotlinDiagnosticCodes.UNSAFE_NULLABLE },
            "a reassignable var must not narrow its safe-call receiver, got $errors",
        )
    }

    /** A local initialized from something unrelated proves nothing about the receiver. */
    @Test
    fun anUnrelatedLocalDoesNotNarrow() = runBlocking {
        val errors = analyze(
            """
            class Node(val parent: Node?, val text: String)
            fun f(leaf: Node?, other: Node?): Int {
                val enclosing = other?.parent
                if (enclosing != null) return leaf.text.length
                return 0
            }
            """,
        )
        assertTrue(
            errors.any { it.code == KotlinDiagnosticCodes.UNSAFE_NULLABLE },
            "an unrelated local must not narrow, got $errors",
        )
    }

    private fun clean(code: String) = runBlocking {
        val errors = analyze(code)
        assertTrue(errors.isEmpty(), "expected a clean file, got $errors")
    }

    private suspend fun analyze(code: String): List<dev.ide.lang.dom.Diagnostic> {
        val text = "package demo\n" + code.trimIndent() + "\n"
        val src = tempProject(mapOf("Use.kt" to text))
        val a = KotlinSourceAnalyzer(fakeContext(src, listOfNotNull(stdlibJarPath(), jdkJar)))
        val doc = SnippetDoc(text, DiskFile(src.resolve("Use.kt")))
        a.incrementalParser.parseFull(doc)
        return a.analyze(doc.file).diagnostics.filter {
            it.severity == Severity.ERROR && it.code != KotlinDiagnosticCodes.SYNTAX
        }
    }

    companion object {
        private val jdkJar: java.nio.file.Path? =
            TestJars.jdkBaseJar(java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "codeassist-jdk-jar"))
    }
}
