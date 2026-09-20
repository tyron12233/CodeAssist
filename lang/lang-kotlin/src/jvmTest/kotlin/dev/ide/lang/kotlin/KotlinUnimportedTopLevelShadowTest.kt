package dev.ide.lang.kotlin

import dev.ide.lang.dom.Severity
import dev.ide.testkit.TestJars
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * An UNIMPORTED top-level function must not take a call that belongs to the enclosing class.
 *
 * Kotlin cannot see one at all, but callee resolution consulted the top-level index BEFORE the scope walk
 * that knows about members, so an out-of-scope hit did not merely compete -- it won outright. On a classpath
 * carrying the Kotlin compiler this bound a class's own `private fun typeOf()` to `kotlin.reflect.typeOf`
 * (whose result has no `qualifiedName`) and its own `private fun analyze(…)` to the Analysis API's
 * `fun <R> analyze(…): R`, which is where the sweep's "not enough information to infer type variable R" came
 * from -- on a call to a function with no type parameters at all.
 */
class KotlinUnimportedTopLevelShadowTest {

    @Test
    fun aMemberWinsOverASameNamedUnimportedTopLevel() = clean(
        """
        package demo
        class Holder {
            private fun helper(): String = "x"
            fun use(): Int = helper().length
        }
        """,
        other = """
            package far.away
            fun <R> helper(block: () -> R): R = block()
        """,
    )

    @Test
    fun aLocalStillWinsToo() = clean(
        """
        package demo
        fun outer(): Int {
            fun helper(): String = "x"
            return helper().length
        }
        """,
        other = """
            package far.away
            fun helper(n: Int): Int = n
        """,
    )

    /** A top-level in the file's OWN package needs no import and must still resolve. */
    @Test
    fun aSamePackageTopLevelStillResolves() = clean(
        """
        package demo
        fun use(): Int = sibling().length
        """,
        other = """
            package demo
            fun sibling(): String = "x"
        """,
    )

    /** An EXPLICITLY imported top-level still resolves. */
    @Test
    fun anImportedTopLevelStillResolves() = clean(
        """
        package demo
        import far.away.sibling
        fun use(): Int = sibling().length
        """,
        other = """
            package far.away
            fun sibling(): String = "x"
        """,
    )

    private fun clean(use: String, other: String) = runBlocking {
        val files = mapOf(
            "Use.kt" to use.trimIndent() + "\n",
            "Other.kt" to other.trimIndent() + "\n",
        )
        val src = tempProject(files)
        val analyzer = KotlinSourceAnalyzer(fakeContext(src, listOfNotNull(stdlibJarPath(), jdkJar)))
        for ((n, t) in files) analyzer.incrementalParser.parseFull(SnippetDoc(t, DiskFile(src.resolve(n))))
        val doc = SnippetDoc(files.getValue("Use.kt"), DiskFile(src.resolve("Use.kt")))
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
