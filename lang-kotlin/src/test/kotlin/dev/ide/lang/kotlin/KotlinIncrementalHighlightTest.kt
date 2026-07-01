package dev.ide.lang.kotlin

import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Track D follow-up #1: incremental semantic highlighting must be byte-for-byte equivalent to a full
 * whole-file walk. The highlighter reuses unchanged top-level declarations' tokens (re-anchored to shifted
 * offsets) via [IncrementalDecls]; a divergence would silently miscolor the buffer. For each edit shape we
 * feed v1 then v2 to the SAME path (exercising the reparse + per-declaration reuse) and compare to a FRESH
 * highlight of v2 (a full walk).
 */
class KotlinIncrementalHighlightTest {

    private fun rawToks(file: String, code: String): List<String> {
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve(file)))
        analyzer.incrementalParser.parseFull(doc)
        return runBlocking { analyzer.semanticHighlighter!!.highlight(doc.file) }
            .map { "${it.range.start}:${it.range.end}:${it.kind.id}:${it.modifiers.map { m -> m.toString() }.sorted().joinToString(",")}" }
            .sorted()
    }

    private fun assertIncrementalMatchesFull(tag: String, v1: String, v2: String) {
        rawToks("HlInc_$tag.kt", v1)                  // seed the cache with v1
        val incremental = rawToks("HlInc_$tag.kt", v2) // reparse v1->v2 + per-declaration reuse
        val full = rawToks("HlFull_$tag.kt", v2)        // fresh path -> full walk
        assertEquals(full, incremental, "[$tag] incremental highlighting must equal a full whole-file walk")
    }

    private val base = "package demo\n" +
        "@Composable fun Card() {}\n" +
        "fun alpha(): Int {\n  val a = 1\n  Card()\n  return a\n}\n" +
        "fun beta(): Int {\n  val b = 100\n  return b\n}\n"

    @Test fun bodyOnlyEdit() = assertIncrementalMatchesFull("body", base, base.replace("val b = 100", "val b = 999"))

    @Test fun signatureEdit() = assertIncrementalMatchesFull("sig", base, base.replace("fun alpha(): Int", "fun alpha(): Long"))

    @Test fun addDeclaration() = assertIncrementalMatchesFull("add", base, base + "fun gamma(): Int = 42\n")

    @Test fun removeDeclaration() =
        assertIncrementalMatchesFull("rm", base, base.replace("fun beta(): Int {\n  val b = 100\n  return b\n}\n", ""))

    @Test fun multipleEdits() =
        assertIncrementalMatchesFull("multi", base, base.replace("val a = 1", "val a = 2").replace("val b = 100", "val b = 7"))

    @Test fun importEdit() =
        assertIncrementalMatchesFull("imp", base, base.replace("package demo\n", "package demo\nimport kotlin.math.max\n"))

    @Test fun noOpReHighlight() = assertIncrementalMatchesFull("noop", base, base)

    companion object {
        val srcDir: Path = tempProject(mapOf("Seed.kt" to "package demo\n"))
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
    }
}
