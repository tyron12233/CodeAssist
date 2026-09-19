package dev.ide.lang.kotlin

import dev.ide.lang.dom.Severity
import dev.ide.testkit.TestJars
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A member's declared type is resolved against the file that DECLARES it, not the file that reads it.
 *
 * `class Holder(val ktFile: KtFile)` in one file and `h.ktFile.importList` in another is ordinary Kotlin:
 * the reader needs no import of `KtFile` to use a member typed by it. Resolving that type against the
 * reader's imports instead loses the member's type, and every call on it then reads as an unresolved
 * reference -- which is what `KotlinCodeFolder.kt` (no `KtFile` import) showed in the module sweep.
 */
class KotlinMemberTypeFileContextTest {

    @Test
    fun aMemberTypedByATypeTheReaderDoesNotImportStillResolves() = clean(
        mapOf(
            "Decl.kt" to """
                package demo
                import dev.ide.kotlin.syntax.psi.KtFile
                class Holder(val ktFile: KtFile)
            """,
            "Use.kt" to """
                package demo
                fun f(h: Holder) {
                    h.ktFile.importList?.imports?.takeIf { it.isNotEmpty() }?.let { imports ->
                        println(imports.first())
                    }
                }
            """,
        ),
        read = "Use.kt",
    )

    /** The same, through a LOCAL inferred from the member — the shape the sweep actually found. */
    @Test
    fun aLocalInferredFromSuchAMemberKeepsItsType() = clean(
        mapOf(
            "Decl.kt" to """
                package demo
                import dev.ide.kotlin.syntax.psi.KtFile
                class Holder(val ktFile: KtFile)
            """,
            "Use.kt" to """
                package demo
                fun f(h: Holder?) {
                    val holder = h ?: return
                    val ktFile = holder.ktFile
                    ktFile.importList?.imports?.takeIf { it.isNotEmpty() }?.let { imports ->
                        println(imports.first())
                    }
                }
            """,
        ),
        read = "Use.kt",
    )

    private fun clean(files: Map<String, String>, read: String) = runBlocking {
        val trimmed = files.mapValues { it.value.trimIndent() }
        val src = tempProject(trimmed)
        val analyzer = KotlinSourceAnalyzer(fakeContext(src, listOfNotNull(stdlibJarPath(), jdkJar, syntaxJar)))
        for ((name, text) in trimmed) {
            analyzer.incrementalParser.parseFull(SnippetDoc(text, DiskFile(src.resolve(name))))
        }
        val doc = SnippetDoc(trimmed.getValue(read), DiskFile(src.resolve(read)))
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
