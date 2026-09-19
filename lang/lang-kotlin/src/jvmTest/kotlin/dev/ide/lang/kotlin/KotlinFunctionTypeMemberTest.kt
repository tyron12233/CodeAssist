package dev.ide.lang.kotlin

import dev.ide.lang.dom.Severity
import dev.ide.testkit.TestJars
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A member whose type is a function type, and where the `?` in it belongs.
 *
 * `(String) -> Parsed?` is a function returning a NULLABLE `Parsed`. `((String) -> Parsed)?` is a nullable
 * FUNCTION. Telling them apart by "starts with `(` and ends with `?`" calls the first one nullable and then
 * strips parentheses that were never there, leaving `String) -> Parsed` and losing the member's type
 * outright. Every call through such a member then resolved to nothing, which is where the module sweep's
 * largest bucket came from: `KotlinCodeFolder`'s `parsedFor: (VirtualFile) -> KotlinParsedFile?`.
 *
 * A function-type PARAMETER was never affected (its type comes off live PSI), which is what made this look
 * like a cross-file problem rather than a type-text one.
 */
class KotlinFunctionTypeMemberTest {

    @Test
    fun aConstructorPropertyReturningANullableTypeResolvesThroughTheResult() = clean(
        """
        class Folder(private val parsedFor: (String) -> Parsed?) {
            fun f(path: String) {
                val parsed = parsedFor(path) ?: return
                println(parsed.name.isNotEmpty())
            }
        }
        """,
    )

    @Test
    fun aBodyPropertyReturningANullableTypeResolvesThroughTheResult() = clean(
        """
        class Folder {
            private val parsedFor: (String) -> Parsed? = { null }
            fun f(path: String) {
                val parsed = parsedFor(path) ?: return
                println(parsed.name.isNotEmpty())
            }
        }
        """,
    )

    @Test
    fun theResultFlowsIntoAScopeFunctionsLambdaParameter() = clean(
        """
        class Folder(private val parsedFor: (String) -> Parsed?) {
            fun f(path: String) {
                val parsed = parsedFor(path) ?: return
                parsed.parts.takeIf { it.isNotEmpty() }?.let { parts ->
                    println(parts.first())
                    println(parts.last())
                }
            }
        }
        """,
    )

    /**
     * The other reading of the same punctuation: `((String) -> Parsed)?` is a NULLABLE FUNCTION, so it is
     * invoked through `?.invoke(…)` and yields a non-null `Parsed`. Both halves have to survive the fix --
     * the outer `?` is the function's nullability, and what remains inside is the function type itself.
     *
     * (Calling such a member WITHOUT `?.` is a Kotlin error this checker does not yet report. That is a gap,
     * not a false positive, and out of the sweep's scope.)
     */
    @Test
    fun aGenuinelyNullableFunctionTypeIsInvokedThroughSafeCall() = clean(
        """
        class Folder(private val parsedFor: ((String) -> Parsed)?) {
            fun f(path: String) {
                val parsed = parsedFor?.invoke(path) ?: return
                println(parsed.name.isNotEmpty())
            }
        }
        """,
    )

    /** A non-nullable result was always fine, and stays fine. */
    @Test
    fun aConstructorPropertyReturningANonNullTypeStillResolves() = clean(
        """
        class Folder(private val parsedFor: (String) -> Parsed) {
            fun f(path: String) {
                println(parsedFor(path).name.isNotEmpty())
            }
        }
        """,
    )

    /** A function-type PARAMETER, the path that already worked, kept as the control. */
    @Test
    fun aFunctionTypeParameterReturningANullableTypeResolves() = clean(
        """
        fun f(parsedFor: (String) -> Parsed?, path: String) {
            val parsed = parsedFor(path) ?: return
            println(parsed.name.isNotEmpty())
        }
        """,
    )

    private fun clean(use: String) = runBlocking {
        val errors = analyze(use)
        assertTrue(errors.isEmpty(), "expected a clean file, got $errors")
    }

    private suspend fun analyze(use: String): List<dev.ide.lang.dom.Diagnostic> {
        val files = mapOf(
            "Decl.kt" to "package demo\nclass Parsed(val name: String, val parts: List<String>)\n",
            "Use.kt" to "package demo\n" + use.trimIndent() + "\n",
        )
        val src = tempProject(files)
        val analyzer = KotlinSourceAnalyzer(fakeContext(src, listOfNotNull(stdlibJarPath(), jdkJar)))
        for ((name, text) in files) analyzer.incrementalParser.parseFull(SnippetDoc(text, DiskFile(src.resolve(name))))
        val doc = SnippetDoc(files.getValue("Use.kt"), DiskFile(src.resolve("Use.kt")))
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
