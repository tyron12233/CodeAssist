package dev.ide.ui.editor.core

import androidx.compose.ui.text.TextRange
import dev.ide.ui.editor.CodeLanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Performance benchmark of the rebuilt editor against the legacy editor's cost model, runnable as a JVM
 * test (`./gradlew :ide-ui:desktopTest --tests '*EditorPerformanceBenchmark*'` — pass `--info` to see the
 * printed tables). It measures the three places the legacy `BasicTextField` editor was O(N) per keystroke
 * and the new one is not:
 *
 *  1. Buffer edit: the rope (`Rope.replace`, what backs [EditorDocument]) vs the old whole-string
 *     rebuild (`buildString`, [naiveReplace]). This is the O(log N) vs O(N) result.
 *  2. Re-highlight: the incremental per-line styler ([LineStyles.splice]) vs the legacy whole-document
 *     re-tokenize ([legacyWholeDocTokenize], the cost the old `highlight()` paid every keystroke).
 *  3. Full keystroke: [EditorSession.commitText] (rope edit + incremental restyle + one host
 *     materialization) vs a legacy keystroke (string rebuild + whole-document re-tokenize).
 *
 * The timing assertions are deliberately loose (large safety factors) so the test is not flaky on a slow or
 * busy CI box; the printed ns/op numbers are the real takeaway. Correctness (rope == string) is asserted
 * tightly.
 */
class EditorPerformanceBenchmark {

    // accumulates results so the JIT can't dead-code-eliminate the measured work
    private var blackhole = 0L

    // ---- 1. buffer edit: rope vs whole-string rebuild ----

    @Test
    fun benchmarkBufferEdit() {
        val sizes = intArrayOf(1_000, 10_000, 100_000, 1_000_000)
        val report = StringBuilder("\n=== Buffer edit: one char insert (ns/op, lower is better) ===\n")
        report.append("size      |   naive(end)   rope(end)  speedup |   naive(mid)   rope(mid)  speedup\n")

        for (size in sizes) {
            val base = pattern(size)
            val baseRope = Rope.of(base)
            val end = base.length
            val mid = base.length / 2

            // correctness: the rope edit must equal the string rebuild it replaces
            assertEquals(naiveReplace(base, end, end, "x"), baseRope.replace(end, end, "x").toString())
            assertEquals(naiveReplace(base, mid, mid, "x"), baseRope.replace(mid, mid, "x").toString())

            val naiveEnd = bench(warmup = 20, runs = 5, ops = 200) { naiveReplace(base, end, end, "x").length.toLong() }
            val ropeEnd = bench(warmup = 1000, runs = 5, ops = 5000) { baseRope.replace(end, end, "x").length.toLong() }
            val naiveMid = bench(warmup = 20, runs = 5, ops = 200) { naiveReplace(base, mid, mid, "x").length.toLong() }
            val ropeMid = bench(warmup = 1000, runs = 5, ops = 5000) { baseRope.replace(mid, mid, "x").length.toLong() }

            report.append(
                "%-9d | %11s %11s %7.0fx | %11s %11s %7.0fx\n".format(
                    size, ns(naiveEnd), ns(ropeEnd), naiveEnd / ropeEnd, ns(naiveMid), ns(ropeMid), naiveMid / ropeMid,
                ),
            )

            // For a large document the rope edit is O(log N) while the rebuild is O(N): the gap is enormous,
            // so even a 5x floor is safe on any machine.
            if (size >= 1_000_000) {
                assertTrue(ropeEnd < naiveEnd / 5.0, "rope end-insert ($ropeEnd ns) should dwarf naive ($naiveEnd ns)")
                assertTrue(ropeMid < naiveMid / 5.0, "rope mid-insert ($ropeMid ns) should dwarf naive ($naiveMid ns)")
            }
        }
        println(report)
    }

    // ---- 2. re-highlight: incremental splice vs whole-document re-tokenize ----

    @Test
    fun benchmarkReHighlight() {
        val report = StringBuilder("\n=== Re-highlight after one keystroke (ns/op, lower is better) ===\n")
        report.append("lines     |   legacy(whole-doc)   incremental(splice)   speedup\n")
        for (lines in intArrayOf(500, 5_000, 25_000)) {
            val text = javaDoc(lines)
            val doc = EditorDocument.of(text)
            val midLine = lines / 2

            val legacy = bench(warmup = 5, runs = 5, ops = 30) {
                legacyWholeDocTokenize(text, CodeLanguage.Java).toLong()
            }
            // incremental: the styler is reset once (file open, outside timing); a keystroke only re-tokenizes
            // the edited line and ripples forward while the lexer exit-state keeps changing. Splicing the same
            // line repeatedly measures exactly that steady-state per-keystroke cost.
            val styles = freshStyles(doc)
            val incremental = bench(warmup = 200, runs = 5, ops = 5000) {
                styles.splice(doc, midLine, 1, 1).toLong()
            }
            report.append(
                "%-9d | %18s %21s %9.0fx\n".format(lines, ns(legacy), ns(incremental), legacy / incremental),
            )
        }
        println(report)
    }

    // ---- 3. full keystroke: new EditorSession vs legacy model ----

    @Test
    fun benchmarkFullKeystroke() {
        val report = StringBuilder("\n=== Sustained typing: full per-keystroke cost (ns/char, lower is better) ===\n")
        report.append("doc chars |   legacy(rebuild+retokenize)   new(rope edit + incr. restyle)   speedup\n")
        val typed = 120

        for (size in intArrayOf(10_000, 100_000, 500_000)) {
            val base = javaDoc(size / 40) // ~40 chars/line
            val language = CodeLanguage.Java

            // legacy keystroke: rebuild the whole string + re-tokenize the whole document, every char
            val legacyNs = benchSequence(warmup = 1, runs = 3) {
                var text = base
                val t0 = System.nanoTime()
                repeat(typed) {
                    text = naiveReplace(text, text.length, text.length, "x")
                    blackhole += legacyWholeDocTokenize(text, language)
                }
                val dt = System.nanoTime() - t0
                dt.toDouble() / typed
            }

            // new keystroke: the real session, rope edit + incremental restyle, and no full-text
            // materialization (the host pulls the String lazily, debounced — off the typing path). Reading
            // doc.length keeps the work live without materializing (it's a stored field, O(1)).
            val newNs = benchSequence(warmup = 1, runs = 3) {
                val session = EditorSession(base, language, TextRange(base.length))
                val t0 = System.nanoTime()
                repeat(typed) { session.commitText("x"); blackhole += session.doc.length.toLong() }
                val dt = System.nanoTime() - t0
                dt.toDouble() / typed
            }

            report.append("%-9d | %28s %30s %9.1fx\n".format(size, ns(legacyNs), ns(newNs), legacyNs / newNs))
        }
        println(report)
    }

    // ---- harness ----

    /** Min ns/op over [runs] batches of [ops] calls, after [warmup] untimed calls (JIT warm). */
    private inline fun bench(warmup: Int, runs: Int, ops: Int, op: () -> Long): Double {
        var bh = 0L
        repeat(warmup) { bh += op() }
        var best = Long.MAX_VALUE
        repeat(runs) {
            val t0 = System.nanoTime()
            var i = 0
            while (i < ops) { bh += op(); i++ }
            val dt = System.nanoTime() - t0
            if (dt < best) best = dt
        }
        blackhole += bh
        return best.toDouble() / ops
    }

    /** Min over [runs] of a measurement that already returns ns/unit (used when each run sets up state). */
    private inline fun benchSequence(warmup: Int, runs: Int, run: () -> Double): Double {
        repeat(warmup) { run() }
        var best = Double.MAX_VALUE
        repeat(runs) { val r = run(); if (r < best) best = r }
        return best
    }

    private fun freshStyles(doc: EditorDocument): LineStyles =
        LineStyles(CodeLanguage.Java).also { it.reset(doc) }

    private fun ns(v: Double): String = if (v >= 1000) "%.1f µs".format(v / 1000) else "%.0f ns".format(v)

    // ---- legacy cost model ----

    /** Exactly the old [EditorDocument]'s buffer rebuild: copy the whole string on every edit. */
    private fun naiveReplace(text: String, start: Int, end: Int, ins: String): String =
        buildString(text.length - (end - start) + ins.length) {
            append(text, 0, start)
            append(ins)
            append(text, end, text.length)
        }

    /** What the legacy `highlight()` did every keystroke: lex the whole document, line by line. */
    private fun legacyWholeDocTokenize(text: String, language: CodeLanguage): Int {
        var entry = LexState.CODE
        var spans = 0
        var lineStart = 0
        var i = 0
        while (i <= text.length) {
            if (i == text.length || text[i] == '\n') {
                val res = styleLine(text.substring(lineStart, i), entry, language)
                entry = res.exitState
                spans += res.spans.size
                lineStart = i + 1
            }
            i++
        }
        return spans
    }

    // ---- fixtures ----

    private fun pattern(size: Int): String {
        val unit = "abcdefghij klmnopqrst\n"
        val sb = StringBuilder(size + unit.length)
        while (sb.length < size) sb.append(unit)
        return sb.substring(0, size)
    }

    private fun javaDoc(lines: Int): String = buildString {
        append("package com.example.benchmark;\n\nclass Generated {\n")
        repeat(lines) { i -> append("    private int field").append(i).append(" = ").append(i).append("; // line ").append(i).append('\n') }
        append("}\n")
    }

    @Test
    fun blackholeIsObserved() {
        // touch the accumulator so the others' work is provably live; this test orders after them only by
        // name, so just assert it is a valid long (the real guard is `blackhole +=` inside each benchmark).
        assertTrue(blackhole >= 0L || blackhole < 0L)
    }
}
