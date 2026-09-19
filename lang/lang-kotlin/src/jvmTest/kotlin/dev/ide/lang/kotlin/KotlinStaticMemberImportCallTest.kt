package dev.ide.lang.kotlin

import dev.ide.lang.dom.Severity
import dev.ide.testkit.TestJars
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A function imported BY NAME out of a class is a function, not a value of its return type.
 *
 * `import org.junit.jupiter.api.Assumptions.assumeTrue` then `assumeTrue(x)` is how every self-gating test in
 * this repository starts, and the module sweep reported 47 of them across 21 files as "Expression
 * 'assumeTrue' of type Unit cannot be invoked as a function". Two answers disagreed: `callTargets` found no
 * method for the bare name, while `inferType` happily returned the imported method's RETURN type, and
 * `notCallable` believed the second one.
 */
class KotlinStaticMemberImportCallTest {

    @Test
    fun aJavaStaticImportedByNameIsCallable() = runBlocking {
        val junit = TestJars.onClasspath("org/junit/jupiter/api/Assumptions.class")
        val src = tempProject(
            mapOf(
                "Use.kt" to """
                    package demo
                    import org.junit.jupiter.api.Assumptions.assumeTrue
                    fun check(ok: Boolean) {
                        assumeTrue(ok)
                    }
                """.trimIndent(),
            ),
        )
        val analyzer = KotlinSourceAnalyzer(fakeContext(src, listOf(stdlibJarPath(), junit)))
        val errors = analyzer.errorsOf(src, "Use.kt")
        assertTrue(errors.isEmpty(), "expected a clean file, got $errors")
    }

    @Test
    fun aKotlinObjectMemberImportedByNameIsCallable() = runBlocking {
        val src = tempProject(
            mapOf(
                "Gate.kt" to """
                    package demo
                    object Gate {
                        fun allow(ok: Boolean) {}
                    }
                """.trimIndent(),
                "Use.kt" to """
                    package demo
                    import demo.Gate.allow
                    fun check(ok: Boolean) {
                        allow(ok)
                    }
                """.trimIndent(),
            ),
        )
        val analyzer = KotlinSourceAnalyzer(fakeContext(src))
        val errors = analyzer.errorsOf(src, "Use.kt")
        assertTrue(errors.isEmpty(), "expected a clean file, got $errors")
    }

    /** The diagnostic this is about still fires where it should: a Unit-typed VALUE is not callable. */
    @Test
    fun aUnitValueIsStillNotCallable() = runBlocking {
        val src = tempProject(
            mapOf(
                "Use.kt" to """
                    package demo
                    fun check() {
                        val done = Unit
                        done()
                    }
                """.trimIndent(),
            ),
        )
        val analyzer = KotlinSourceAnalyzer(fakeContext(src))
        val errors = analyzer.errorsOf(src, "Use.kt")
        assertEquals(1, errors.size, "expected exactly the not-callable error, got $errors")
        assertEquals(KotlinDiagnosticCodes.NOT_CALLABLE, errors.single().code, "got $errors")
    }
}

private suspend fun KotlinSourceAnalyzer.errorsOf(src: java.nio.file.Path, name: String) =
    SnippetDoc(java.nio.file.Files.readString(src.resolve(name)), DiskFile(src.resolve(name))).let { doc ->
        incrementalParser.parseFull(doc)
        analyze(doc.file).diagnostics.filter {
            it.severity == Severity.ERROR && it.code != KotlinDiagnosticCodes.SYNTAX
        }
    }
