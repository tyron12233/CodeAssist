package dev.ide.lang.kotlin

import dev.ide.lang.dom.Severity
import dev.ide.testkit.TestJars
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A `private` member of ANOTHER class is not a candidate for a bare call.
 *
 * Every test class in this module has a `private fun analyze(...)` helper with its own signature, and in the
 * sweep -- where the whole module is in the model at once -- `analyze(code)` stopped resolving to the one in
 * its own class.
 */
class KotlinPrivateHelperScopeTest {

    @Test
    fun aPrivateHelperResolvesToItsOwnClassNotASibling() = clean(
        """
        class A {
            fun run(): Int {
                val n = helper("x")
                return n.length
            }
            private fun helper(s: String): String = s
        }
        class B {
            fun run(): Int {
                val n = helper(listOf("x"))
                return n.size
            }
            private fun helper(s: List<String>): List<String> = s
        }
        """,
    )

    @Test
    fun theSameAcrossTwoFiles() = runBlocking {
        val a = "package demo\n" + """
            class A {
                fun run(): Int {
                    val n = helper("x")
                    return n.length
                }
                private fun helper(s: String): String = s
            }
        """.trimIndent() + "\n"
        val b = "package demo\n" + """
            class B {
                fun run(): Int {
                    val n = helper(listOf("x"))
                    return n.size
                }
                private fun helper(s: List<String>): List<String> = s
            }
        """.trimIndent() + "\n"
        val src = tempProject(mapOf("A.kt" to a, "B.kt" to b))
        val analyzer = KotlinSourceAnalyzer(fakeContext(src, listOfNotNull(stdlibJarPath(), jdkJar)))
        for ((n, t) in listOf("A.kt" to a, "B.kt" to b)) {
            analyzer.incrementalParser.parseFull(SnippetDoc(t, DiskFile(src.resolve(n))))
        }
        val doc = SnippetDoc(a, DiskFile(src.resolve("A.kt")))
        analyzer.incrementalParser.parseFull(doc)
        val errors = analyzer.analyze(doc.file).diagnostics.filter {
            it.severity == Severity.ERROR && it.code != KotlinDiagnosticCodes.SYNTAX
        }
        assertTrue(errors.isEmpty(), "expected a clean file, got $errors")
    }

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
