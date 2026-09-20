package dev.ide.lang.kotlin

import dev.ide.lang.dom.Severity
import dev.ide.testkit.TestJars
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Assigning to a variable INSIDE a narrowing of itself, which is how every PSI walk advances.
 *
 * `var p = e.parent; while (p is Paren) { p = p.parent }` was reported as "inferred type is Node? but Paren
 * was expected" -- a complaint about the only line that can terminate the loop. An assignment target's type
 * is what it was DECLARED as; assigning something wider is legal and just ends the smart cast.
 */
class KotlinAssignToSmartCastVarTest {

    @Test
    fun aVarMayBeAssignedItsDeclaredTypeInsideAnIsNarrowing() = clean(
        """
        open class Node(val parent: Node?)
        class Paren(parent: Node?) : Node(parent)
        fun f(e: Node) {
            var child: Node = e
            var parent = e.parent
            while (parent is Paren) {
                child = parent
                parent = parent.parent
            }
            println(child)
        }
        """,
    )

    @Test
    fun theSameAfterABareCastStatement() = clean(
        """
        open class Node(val parent: Node?)
        class Paren(parent: Node?) : Node(parent)
        fun f(n: Node?) {
            var cur = n
            cur as Paren
            cur = cur.parent
            println(cur)
        }
        """,
    )

    /** A genuinely wrong assignment is still reported. */
    @Test
    fun aRealAssignmentMismatchIsStillReported() = runBlocking {
        val errors = analyze(
            """
            fun f() {
                var n: Int = 0
                n = "s"
            }
            """,
        )
        assertTrue(
            errors.any { it.code == KotlinDiagnosticCodes.TYPE_MISMATCH },
            "a real assignment mismatch must still be reported, got $errors",
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
