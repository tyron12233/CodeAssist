package dev.ide.lang.kotlin

import dev.ide.lang.dom.Severity
import dev.ide.lang.dom.TextRange
import dev.ide.testkit.TestJars
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `stream.collect(Collectors.toList())` must type as `List<E>`, not `List<Object>`.
 *
 * Two things broke it. The argument's expected type was read off the first `collect` overload, the
 * three-argument `collect(Supplier<R>, …)`, so the nested `toList<T>()` had nothing to bind `T` and fell back
 * to `Object`. With the right overload, the solver then bound `T` to the projected argument of
 * `Collector<in E, A, R>` itself, `in Path`, which leaked into everything after the call.
 *
 * The reported chain (`Files.walk(dir).use { … collect(Collectors.toList()) }.sorted()` inside a
 * `Sequence.flatMap`) surfaced as "Type mismatch: inferred type is Object but Path? was expected".
 */
class KotlinStreamCollectInferenceTest {

    @Test
    fun reportedChainIsClean() {
        clean(
            """
            import java.nio.file.Files
            import java.nio.file.Path
            import java.util.stream.Collectors
            import kotlin.io.path.extension
            fun defineMain(p: Path): Boolean = true
            fun find(moduleDir: Path, dirs: List<String>): Path? {
                return dirs.asSequence()
                    .map { moduleDir.resolve(it) }
                    .filter { Files.isDirectory(it) }
                    .flatMap { dir ->
                        Files.walk(dir).use { walk ->
                            walk.filter {
                                Files.isRegularFile(it) && it.extension.lowercase() == "dart"
                            }
                                .collect(Collectors.toList())
                        }.sorted().asSequence()
                    }
                    .firstOrNull {
                        defineMain(it)
                    }
            }
            """,
        )
    }

    @Test
    fun collectToListKeepsTheElementType() {
        val hs = hints(
            """
            import java.nio.file.Path
            import java.util.stream.Stream
            import java.util.stream.Collectors
            fun f(s: Stream<Path>) {
                val a = s.collect(Collectors.toList())
                val b = s.collect(Collectors.toSet())
            }
            """,
        )
        assertTrue(": List<Path>" in hs, "collect(toList()) must be List<Path>; got $hs")
        assertTrue(": MutableSet<Path>" in hs || ": Set<Path>" in hs, "collect(toSet()) must keep Path; got $hs")
        assertTrue(hs.none { "Object" in it || "in Path" in it }, "no erased or projected type may leak; got $hs")
    }

    @Test
    fun aMismatchedCollectIsStillReported() {
        val errors = errors(
            """
            import java.nio.file.Path
            import java.util.stream.Stream
            import java.util.stream.Collectors
            fun f(s: Stream<Path>): String = s.collect(Collectors.toList())
            """,
        )
        assertTrue(errors.isNotEmpty(), "a List<Path> where String is declared must still be flagged")
    }

    private fun hints(code: String): List<String> = runBlocking {
        val (a, doc, text) = open(code)
        a.inlayHints!!.hints(doc.file, TextRange(0, text.length)).map { h -> h.parts.joinToString("") { it.text } }
    }

    private fun clean(code: String) {
        val errors = errors(code)
        assertTrue(errors.isEmpty(), "expected a clean file, got $errors")
    }

    private fun errors(code: String) = runBlocking {
        val (a, doc) = open(code)
        a.analyze(doc.file).diagnostics.filter {
            it.severity == Severity.ERROR && it.code != KotlinDiagnosticCodes.SYNTAX
        }
    }

    private fun open(code: String): Triple<KotlinSourceAnalyzer, SnippetDoc, String> {
        val text = "package demo\n" + code.trimIndent() + "\n"
        val src = tempProject(mapOf("Use.kt" to text))
        val a = KotlinSourceAnalyzer(fakeContext(src, listOfNotNull(stdlibJarPath(), jdkJar)))
        val doc = SnippetDoc(text, DiskFile(src.resolve("Use.kt")))
        a.incrementalParser.parseFull(doc)
        return Triple(a, doc, text)
    }

    companion object {
        private val jdkJar: java.nio.file.Path? =
            TestJars.jdkBaseJar(java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "codeassist-jdk-jar"))
    }
}
