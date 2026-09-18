package dev.ide.lang.kotlin

import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `KotlinSourceAnalyzer.hasSyntaxErrors` is the gate that stops the Compose preview from interpreting a file
 * that doesn't parse cleanly. The motivating crash: a stray token in a data-class constructor
 * (`data class Project(dsad val id: …)`, a leftover keystroke while editing) parses error-tolerantly into a
 * constructor whose parameters are all shifted, so the interpreter builds objects with wrong-typed fields
 * (a `Float` slot holding a `String`) — which then crashes the real Compose runtime in its measure/semantics
 * pass, outside any catchable boundary. Detecting the parse error lets the preview show "fix errors" instead.
 */
class KotlinSyntaxErrorGateTest {

    private fun parse(name: String, code: String): SnippetDoc =
        SnippetDoc(code, DiskFile(srcDir.resolve(name))).also { runBlocking { analyzer.incrementalParser.parseFull(it) } }

    @Test
    fun strayTokenInDataClassConstructorIsASyntaxError() {
        val doc = parse(
            "Bad.kt",
            "package demo\n" +
                "data class Project(dsad\n" +
                "  val id: String,\n" +
                "  val progress: Float,\n" +
                ")\n",
        )
        assertTrue(analyzer.hasSyntaxErrors(doc.file), "a stray token in a constructor must be flagged as a syntax error")
    }

    @Test
    fun wellFormedFileHasNoSyntaxErrors() {
        val doc = parse(
            "Good.kt",
            "package demo\n" +
                "data class Project(\n" +
                "  val id: String,\n" +
                "  val progress: Float,\n" +
                ")\n",
        )
        assertFalse(analyzer.hasSyntaxErrors(doc.file), "a well-formed file must not be flagged")
    }

    companion object {
        val srcDir: Path = tempProject(mapOf("Seed.kt" to "package demo\n"))
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
    }
}
