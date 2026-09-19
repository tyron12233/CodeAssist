package dev.ide.lang.kotlin

import dev.ide.lang.dom.Severity
import dev.ide.testkit.TestJars
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A class nested in a companion object, named by its simple name from inside that companion.
 *
 * `KotlinFormatter` declares `private class Atom` in its companion and uses it as `ArrayList<Atom>` and as
 * a parameter type. The module sweep resolved `Atom(…)` (the CONSTRUCTOR call) and not `Atom` (the TYPE).
 */
class KotlinCompanionNestedTypeTest {

    @Test
    fun aTypeNestedInACompanionResolvesByItsSimpleNameInside() = clean(
        """
        class Outer {
            companion object {
                private class Atom(val start: Int)
                private fun make(): Atom = Atom(1)
                private fun holder(): ArrayList<Atom> = ArrayList()
                private fun read(a: Atom): Int = a.start
            }
        }
        """,
    )

    @Test
    fun aTypeNestedInAPlainObjectResolvesByItsSimpleNameInside() = clean(
        """
        object Outer {
            private class Atom(val start: Int)
            private fun holder(): ArrayList<Atom> = ArrayList()
            private fun read(a: Atom): Int = a.start
        }
        """,
    )

    @Test
    fun aTypeNestedInAClassResolvesByItsSimpleNameInside() = clean(
        """
        class Outer {
            private class Atom(val start: Int)
            private fun holder(): ArrayList<Atom> = ArrayList()
            private fun read(a: Atom): Int = a.start
        }
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
