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

    /** A built-in element type declared against an unrelated one, open or not, is a clash: only `String`
     *  itself fills `List<String>`. Before, both arguments had to be final types, so an interface like `Path`
     *  slipped through. */
    @Test
    fun aMismatchedElementTypeIsReported() {
        val cases = listOf(
            "fun f(s: Stream<Path>): List<String> = s.collect(Collectors.toList())",
            "fun f(s: Stream<Path>) { val xs: List<String> = s.collect(Collectors.toList()) }",
            "fun f(xs: List<Path>): List<Int> = xs",
            "fun f(xs: MutableList<Runnable>): MutableList<String> = xs",
        )
        for (c in cases) {
            val errors = errors(STREAM_IMPORTS + c)
            assertEquals(
                listOf(KotlinDiagnosticCodes.TYPE_MISMATCH), errors.map { it.code },
                "expected one type mismatch for `$c`, got $errors",
            )
        }
    }

    /** Valid assignments the stricter element check must keep accepting. */
    @Test
    fun compatibleElementTypesStayClean() {
        val cases = listOf(
            "fun f(): List<CharSequence> = listOf(\"a\")",
            "fun f(): List<java.io.Serializable> = listOf(\"a\")",
            "fun f(): List<Comparable<String>> = listOf(\"a\")",
            "fun f(): List<Number> = listOf(1)",
            "fun f(): List<Any> = listOf(\"a\")",
            "fun f(): List<String> = emptyList()",
            "fun f(): List<*> = listOf(Path.of(\"a\"))",
            "fun f(c: Comparator<Any>): Comparator<String> = c",
            "fun f(s: Stream<String>): List<String> = s.collect(Collectors.toList())",
            "fun f(s: Stream<Path>): MutableList<Path> = s.collect(Collectors.toList())",
            "fun f(s: Stream<Path>): List<Any> = s.collect(Collectors.toList())",
            "fun f(s: Stream<String>): List<CharSequence> = s.collect(Collectors.toList())",
            "fun f(s: Stream<Int>): List<Int> = s.collect(Collectors.toList())",
            "fun f(): List<String> = java.util.Arrays.asList(\"a\")",
            "fun f(): Map<String, Int> = java.util.HashMap<String, Int>()",
            "fun f(xs: List<Nothing>): List<String> = xs",
            "fun f(m: Map<String, Path>): Map<String, Any> = m",
        )
        for (c in cases) {
            val errors = errors(STREAM_IMPORTS + c)
            assertTrue(errors.isEmpty(), "`$c` must stay clean, got $errors")
        }
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
        private const val STREAM_IMPORTS =
            "import java.nio.file.Path\nimport java.util.stream.Stream\nimport java.util.stream.Collectors\n"
        private val jdkJar: java.nio.file.Path? =
            TestJars.jdkBaseJar(java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "codeassist-jdk-jar"))
    }
}
