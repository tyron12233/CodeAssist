package dev.ide.lang.kotlin

import dev.ide.lang.dom.Severity
import dev.ide.testkit.TestJars
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Two ways of naming a type that the module sweep found unresolved: through a `typealias`, and through an
 * `object` that encloses it.
 *
 * `typealias DiskFile = DiskVirtualFile` sits in one file of a package and `DiskFile(path)` is written all
 * over the others. `object ResolvedTreeCodec { class Writer }` is constructed as `ResolvedTreeCodec.Writer(d)`
 * and then used as `.run { str(…) }` -- and an object is a VALUE, so that call looks like a member call on
 * the instance rather than a constructor for the nested class.
 */
class KotlinTypealiasConstructorTest {

    @Test
    fun aTypealiasFromAnotherFileInThePackageConstructs() = clean(
        mapOf(
            "Alias.kt" to """
                package demo
                class Real(val path: String)
                typealias Alias = Real
            """,
            "Use.kt" to """
                package demo
                fun f(): String = Alias("x").path
            """,
        ),
    )

    @Test
    fun aTypealiasInTheSameFileConstructs() = clean(
        mapOf(
            "Use.kt" to """
                package demo
                class Real(val path: String)
                typealias Alias = Real
                fun f(): String = Alias("x").path
            """,
        ),
    )

    @Test
    fun aTypeNestedInAnObjectConstructsThroughIt() = clean(
        mapOf(
            "Use.kt" to """
                package demo
                object Codec { class Writer(val n: Int) { fun str(s: String) {} } }
                fun f() {
                    val w = Codec.Writer(1)
                    w.str("a")
                    println(w.n)
                }
            """,
        ),
    )

    @Test
    fun suchATypeCarriesIntoAScopeFunctionsReceiver() = clean(
        mapOf(
            "Use.kt" to """
                package demo
                object Codec { class Writer(val n: Int) { fun str(s: String) {} } }
                fun f() {
                    Codec.Writer(1).run { str("a"); println(n) }
                    Codec.Writer(1).apply { str("b") }
                    val w = Codec.Writer(1)
                    w.run { str("c") }
                }
            """,
        ),
    )

    /** Nesting in a CLASS already worked, and must keep working. */
    @Test
    fun aTypeNestedInAClassStillConstructsThroughIt() = clean(
        mapOf(
            "Use.kt" to """
                package demo
                class Outer { class Writer(val n: Int) { fun str(s: String) {} } }
                fun f() { Outer.Writer(1).run { str("a") } }
            """,
        ),
    )

    /**
     * The object's OWN members are not in the nested class's scope. `run` on a `Writer` makes `Writer` the
     * receiver, so a member of the enclosing object is still unresolved there -- as Kotlin says it is. This is
     * what keeps the fix from widening into "an object's members leak into its nested types".
     */
    @Test
    fun theEnclosingObjectsOwnMembersAreNotInScopeThere() = runBlocking {
        val errors = analyze(
            mapOf(
                "Use.kt" to """
                    package demo
                    object Codec {
                        fun onObject() {}
                        class Writer(val n: Int) { fun str(s: String) {} }
                    }
                    fun f() { Codec.Writer(1).run { onObject() } }
                """,
            ),
        )
        assertTrue(
            errors.any { it.code == KotlinDiagnosticCodes.UNRESOLVED },
            "the enclosing object's member must not resolve on the nested type, got $errors",
        )
    }

    /** A capitalized MEMBER function on an object still wins over a same-named nested type lookup. */
    @Test
    fun aCapitalizedMemberFunctionIsStillACall() = clean(
        mapOf(
            "Use.kt" to """
                package demo
                object Factory {
                    fun Make(n: Int): String = "x".repeat(n)
                }
                fun f(): String = Factory.Make(2)
            """,
        ),
    )

    private fun clean(files: Map<String, String>) = runBlocking {
        val errors = analyze(files)
        assertTrue(errors.isEmpty(), "expected a clean file, got $errors")
    }

    private suspend fun analyze(files: Map<String, String>): List<dev.ide.lang.dom.Diagnostic> {
        val trimmed = files.mapValues { it.value.trimIndent() + "\n" }
        val src = tempProject(trimmed)
        val analyzer = KotlinSourceAnalyzer(fakeContext(src, listOfNotNull(stdlibJarPath(), jdkJar)))
        for ((name, text) in trimmed) analyzer.incrementalParser.parseFull(SnippetDoc(text, DiskFile(src.resolve(name))))
        val doc = SnippetDoc(trimmed.getValue("Use.kt"), DiskFile(src.resolve("Use.kt")))
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
