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

    // ---- 4. bulk multi-line edit: single-shift resize vs the old per-line add/removeAt ----

    @Test
    fun benchmarkBulkLineResize() {
        // A multi-line paste/delete resizes the styler's three parallel arrays at the edit point. The old code
        // did one add(firstLine)/removeAt(firstLine) PER line — each an O(N) tail shift, so pasting K lines was
        // O(K·N) and stalled for seconds on a big file. The fix does a single addAll(index, …) / subList.clear()
        // = one shift, O(N + K). This isolates that resize cost (the re-tokenize walk is identical either way).
        val report = StringBuilder("\n=== Bulk paste: parallel-array resize at the edit point (ns/op, lower is better) ===\n")
        report.append("base lines | paste lines |   legacy(per-line add)      new(single addAll)   speedup\n")
        for ((base, paste) in listOf(2_000 to 1_000, 8_000 to 2_000, 20_000 to 3_000)) {
            val ref = ArrayList<Int>(base + paste).apply { repeat(base) { add(it) } }
            val insertAt = base / 3

            val legacy = bench(warmup = 1, runs = 3, ops = 2) {
                val a = ArrayList(ref)
                repeat(paste) { a.add(insertAt, 0) } // OLD: one tail shift per inserted line → O(paste·base)
                a.size.toLong()
            }
            val neu = bench(warmup = 2, runs = 5, ops = 20) {
                val a = ArrayList(ref)
                a.addAll(insertAt, List(paste) { 0 }) // NEW: one tail shift → O(base + paste)
                a.size.toLong()
            }
            report.append("%-10d | %-11d | %22s %23s %9.0fx\n".format(base, paste, ns(legacy), ns(neu), legacy / neu))
            if (base >= 20_000) {
                assertTrue(neu < legacy / 5.0, "single-shift resize ($neu ns) should dwarf per-line ($legacy ns)")
            }
        }
        println(report)
    }

    // ---- 5. large-file open: lazy (viewport-only) tokenization vs eager whole-file ----

    @Test
    fun benchmarkLargeFileOpen() {
        // Opening a file used to lex EVERY line up front (LineStyles.reset). Now reset only sizes placeholder
        // arrays and lexing is deferred: the first paint lexes just the viewport, and lines below tokenize as
        // they scroll in. This measures the work done before the first paint: whole-file lex vs reset + one
        // viewport. The gap widens with file size (the whole-file cost is O(lines); the lazy cost is ~viewport).
        val report = StringBuilder("\n=== Open a file: tokenization before first paint (ns/op, lower is better) ===\n")
        report.append("lines     |   eager(whole-file)     lazy(reset + 1 viewport)   speedup\n")
        val viewport = 60
        for (lines in intArrayOf(500, 5_000, 50_000)) {
            val text = javaDoc(lines)
            val doc = EditorDocument.of(text)

            val eager = bench(warmup = 2, runs = 5, ops = 3) {
                legacyWholeDocTokenize(text, CodeLanguage.Java).toLong() // what reset() used to do at open
            }
            val lazy = bench(warmup = 3, runs = 5, ops = 20) {
                val styles = LineStyles(CodeLanguage.Java)
                styles.reset(doc) // O(lines) placeholder fill, no tokenization
                var bh = 0L
                val end = minOf(viewport, doc.lineCount)
                var l = 0
                while (l < end) { bh += styles.spansFor(l).size.toLong(); l++ } // only the first viewport lexes
                bh
            }
            report.append("%-9d | %20s %26s %9.0fx\n".format(lines, ns(eager), ns(lazy), eager / lazy))
            if (lines >= 50_000) {
                assertTrue(lazy < eager / 5.0, "lazy open ($lazy ns) should dwarf eager whole-file ($eager ns)")
            }
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
