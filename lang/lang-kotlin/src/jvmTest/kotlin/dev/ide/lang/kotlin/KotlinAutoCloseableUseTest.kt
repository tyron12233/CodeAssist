package dev.ide.lang.kotlin

import dev.ide.lang.dom.Severity
import dev.ide.testkit.TestJars
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `use` on a receiver that is `AutoCloseable` but not `Closeable` -- a `java.util.stream.Stream`.
 *
 * The stdlib has two: the `Closeable` one in `kotlin.io`, and the `AutoCloseable` one compiled into
 * `kotlin.jdk7.AutoCloseableKt`, so its symbol carries package `kotlin.jdk7`. That package was not in the
 * default-import set, so the extension was found and then rejected as "not in scope" -- and
 * `Files.walk(dir).use { }` read as an unresolved reference while the same call on a `BufferedReader`, which
 * IS `Closeable`, resolved fine.
 */
class KotlinAutoCloseableUseTest {

    @Test
    fun useResolvesOnAStream() = clean(
        """
        import java.nio.file.Files
        import java.nio.file.Path
        fun f(dir: Path): Long = Files.walk(dir).use { it.count() }
        """,
    )

    @Test
    fun useResolvesOnAnExplicitStreamParameter() = clean(
        """
        import java.nio.file.Path
        import java.util.stream.Stream
        fun f(s: Stream<Path>): Long = s.use { it.count() }
        """,
    )

    /** The `Closeable` overload, which always worked, must keep working. */
    @Test
    fun useStillResolvesOnACloseable() = clean(
        """
        import java.nio.file.Files
        import java.nio.file.Path
        fun f(p: Path): String? = Files.newBufferedReader(p).use { it.readLine() }
        """,
    )

    private fun clean(code: String) = runBlocking {
        val text = "package demo\n" + code.trimIndent() + "\n"
        val src = tempProject(mapOf("Use.kt" to text))
        val a = KotlinSourceAnalyzer(fakeContext(src, listOfNotNull(stdlibJarPath(), jdkJar)))
        val doc = SnippetDoc(text, DiskFile(src.resolve("Use.kt")))
        a.incrementalParser.parseFull(doc)
        val errors = a.analyze(doc.file).diagnostics.filter {
            it.severity == Severity.ERROR && it.code != KotlinDiagnosticCodes.SYNTAX
        }
        assertTrue(errors.isEmpty(), "expected a clean file, got $errors")
    }

    companion object {
        private val jdkJar: java.nio.file.Path? =
            TestJars.jdkBaseJar(java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "codeassist-jdk-jar"))
    }
}
