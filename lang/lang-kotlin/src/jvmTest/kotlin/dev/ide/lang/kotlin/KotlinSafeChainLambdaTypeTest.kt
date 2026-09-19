package dev.ide.lang.kotlin

import dev.ide.lang.dom.Severity
import dev.ide.testkit.TestJars
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The receiver of a scope function reached through a chain of SAFE calls on classpath types.
 *
 * `ktFile.importList?.imports?.takeIf { it.isNotEmpty() }?.let { … }` is this repository's own code, and
 * the module sweep reported `isNotEmpty`, `first` and `last` in it as unresolved references.
 */
class KotlinSafeChainLambdaTypeTest {

    @Test
    fun oneSafeCallThenTakeIf() = clean(
        """
        package demo
        import dev.ide.kotlin.syntax.psi.KtImportList
        fun f(list: KtImportList?) {
            list?.imports?.takeIf { it.isNotEmpty() }
        }
        """,
    )

    @Test
    fun twoSafeCallsThenTakeIf() = clean(
        """
        package demo
        import dev.ide.kotlin.syntax.psi.KtFile
        fun f(ktFile: KtFile) {
            ktFile.importList?.imports?.takeIf { it.isNotEmpty() }
        }
        """,
    )

    @Test
    fun twoSafeCallsThenTakeIfThenLet() = clean(
        """
        package demo
        import dev.ide.kotlin.syntax.psi.KtFile
        fun f(ktFile: KtFile) {
            ktFile.importList?.imports?.takeIf { it.isNotEmpty() }?.let { imports ->
                println(imports.first())
                println(imports.last())
            }
        }
        """,
    )

    /** The same shape with no safe call, to say whether the safe call is what breaks it. */
    @Test
    fun aPlainChainThenTakeIfThenLet() = clean(
        """
        package demo
        import dev.ide.kotlin.syntax.psi.KtFile
        fun f(ktFile: KtFile) {
            ktFile.importDirectives.takeIf { it.isNotEmpty() }?.let { imports ->
                println(imports.first())
            }
        }
        """,
    )

    private fun clean(code: String) = runBlocking {
        val src = tempProject(mapOf("Probe.kt" to code.trimIndent()))
        val analyzer = KotlinSourceAnalyzer(fakeContext(src, listOfNotNull(stdlibJarPath(), jdkJar, syntaxJar)))
        val doc = SnippetDoc(code.trimIndent(), DiskFile(src.resolve("Probe.kt")))
        analyzer.incrementalParser.parseFull(doc)
        val errors = analyzer.analyze(doc.file).diagnostics.filter {
            it.severity == Severity.ERROR && it.code != KotlinDiagnosticCodes.SYNTAX
        }
        assertTrue(errors.isEmpty(), "expected a clean file, got $errors")
    }

    companion object {
        private val jdkJar: java.nio.file.Path? =
            TestJars.jdkBaseJar(java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "codeassist-jdk-jar"))
        private val syntaxJar: java.nio.file.Path =
            TestJars.onClasspath("dev/ide/kotlin/syntax/psi/KtFile.class")
    }
}
