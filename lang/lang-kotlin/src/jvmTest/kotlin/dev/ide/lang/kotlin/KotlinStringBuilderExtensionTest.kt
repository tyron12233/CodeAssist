package dev.ide.lang.kotlin

import dev.ide.lang.dom.Severity
import dev.ide.testkit.TestJars
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Extensions and members on `StringBuilder`, whose Kotlin name is a typealias for `java.lang.StringBuilder`.
 *
 * `kotlin.text.StringBuilder` is an ALIAS for `java.lang.StringBuilder`, and the stdlib keys its extensions
 * under either name: `clear`/`setRange` under the JVM type, `isNotEmpty` under the alias. A value declared
 * `sb: StringBuilder` resolves to the alias, whose supertype chain is `CharSequence`/`Any` and never mentions
 * the JVM type -- so half these extensions were unreachable and half were not.
 *
 * That split is what made every single-cause hypothesis look disproven: `isNotEmpty` is inline-only AND on an
 * aliased receiver and resolved fine, `appendRange` resolved, a source extension named `setSomething`
 * resolved. Each fact was true; the cause was which NAME the stdlib happened to key each one under.
 */
class KotlinStringBuilderExtensionTest {

    @Test
    fun clearResolves() = clean(
        """
        fun f(sb: StringBuilder) {
            sb.clear()
        }
        """,
    )

    @Test
    fun setRangeResolves() = clean(
        """
        fun f(sb: StringBuilder) {
            sb.setRange(0, 1, "x")
        }
        """,
    )

    @Test
    fun appendRangeResolves() = clean(
        """
        fun f(sb: StringBuilder, s: String) {
            sb.appendRange(s, 0, 1)
        }
        """,
    )

    @Test
    fun inlineOnlyStdlibExtensionsResolve() = clean(
        """
        fun f(cs: CharSequence, s: String?, xs: List<Int>, sb: StringBuilder) {
            println(cs.isNotEmpty())
            println(s.isNullOrEmpty())
            println(xs.isNotEmpty())
            println(sb.isNotEmpty())
        }
        """,
    )

    @Test
    fun javaMembersOfTheAliasedTypeResolve() = clean(
        """
        fun f(sb: StringBuilder) {
            sb.replace(0, 1, "x")
            sb.deleteCharAt(0)
        }
        """,
    )

    @Test
    fun sourceExtensionsNamedSetSomethingResolve() = clean(
        """
        class Thing
        fun Thing.setWidth(x: Int) {}
        fun StringBuilder.setDepth(x: Int) {}
        fun f(t: Thing, sb: StringBuilder) {
            t.setWidth(1)
            sb.setDepth(1)
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
