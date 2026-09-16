package dev.ide.kotlin.syntax

import dev.ide.psi.IntellijPsiHost
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import org.jetbrains.kotlin.idea.KotlinLanguage

/**
 * How fast is the vendored parser, and is a full parse cheap enough to make incremental reparse unnecessary?
 *
 * Both questions decide what happens next, and neither had a number. The first asks whether adopting this
 * parser is worth doing on the JVM for its own sake, independent of portability. The second asks whether
 * losing `KotlinPsiMutation.reparse` is a blocker: `:lang-kotlin` reparses on every keystroke today, and the
 * vendored parser has no incremental mode, so the only thing that matters is whether a COLD full parse fits
 * inside a frame.
 *
 * The answer to the second question turned out to be NO for the largest files, which is why the assertion
 * below is a catastrophe bound rather than the frame budget. See the comment at the end of that test.
 *
 * **What is asserted, and what is only printed.** Absolute milliseconds are a property of the machine, so the
 * assertions are ratios and orders of magnitude: that the vendored parser is not slower than PSI, and that a
 * full parse of the largest real file in this repository is nowhere near a frame budget. The numbers
 * themselves are printed for a human to read, because that is what a measurement is for.
 *
 * The corpus is the biggest Kotlin files in this repository rather than synthetic input: 2,000 to 4,800 lines
 * of real code, which is the worst case an editor here actually meets.
 */
class KotlinParserBenchmarkTest {

    private data class Sample(val name: String, val text: String) {
        val lines: Int get() = text.count { it == '\n' } + 1
    }

    private fun corpus(): List<Sample> {
        val root = File(System.getProperty("kotlinSyntax.corpusRoot")!!)
        return root.walkTopDown()
            .onEnter { it.name !in setOf("build", "node_modules", "out", "testData", "vendor", ".git", ".gradle") }
            .filter { it.isFile && it.extension == "kt" && it.length() > 60_000 }
            .sortedByDescending { it.length() }
            .take(12)
            .map { Sample(it.name, it.readText().replace("\r\n", "\n")) }
            .toList()
    }

    /** Least-noise estimator: the fastest of [repeats] runs, which is the one least polluted by scheduling. */
    private fun fastestNanos(repeats: Int, block: () -> Unit): Long {
        var best = Long.MAX_VALUE
        repeat(repeats) {
            val start = System.nanoTime()
            block()
            best = minOf(best, System.nanoTime() - start)
        }
        return best
    }

    private fun parsePsi(name: String, text: String) {
        val file = IntellijPsiHost.parse(name, KotlinLanguage.INSTANCE, text)
        // PSI parses blocks lazily, so timing it without this would be timing a promise rather than a parse.
        IntellijPsiHost.forceFullParse(file)
    }

    @Test
    fun theVendoredParserIsNotSlowerThanPsi() {
        val samples = corpus()
        assertTrue(samples.size >= 8, "expected a real corpus, found ${samples.size} large files")
        val chars = samples.sumOf { it.text.length.toLong() }
        val lines = samples.sumOf { it.lines.toLong() }

        // Warm up both: HotSpot needs it, and the PSI environment stands itself up on first use.
        repeat(3) { samples.forEach { KotlinSyntax.parse(it.text); parsePsi(it.name, it.text) } }

        val oursNanos = fastestNanos(5) { samples.forEach { KotlinSyntax.parse(it.text) } }
        val psiNanos = fastestNanos(5) { samples.forEach { parsePsi(it.name, it.text) } }

        val ratio = psiNanos.toDouble() / oursNanos
        println(
            buildString {
                appendLine("parser throughput over ${samples.size} files, $lines lines, $chars chars")
                appendLine("  vendored : %,d us  (%,.0f chars/ms)".format(oursNanos / 1_000, chars * 1e6 / oursNanos))
                appendLine("  PSI      : %,d us  (%,.0f chars/ms)".format(psiNanos / 1_000, chars * 1e6 / psiNanos))
                appendLine("  vendored is %.2fx the speed of PSI".format(ratio))
            },
        )

        assertTrue(
            ratio > 0.9,
            ("the vendored parser runs at %.2fx PSI's speed, i.e. materially slower. That would be a reason " +
                "not to adopt it on the JVM for its own sake, so it is worth failing on rather than " +
                "discovering later.").format(ratio),
        )
    }

    /**
     * Lazy parsing: how much of a file's parse cost IS its function bodies?
     *
     * In lazy mode the parser counts braces past each body and collapses it, exactly as PSI's lazy-parseable
     * blocks do. If that is most of the cost, then an editor's per-keystroke work becomes a lazy file parse
     * plus the one body being edited, and the missing incremental mode stops being a blocker.
     */
    @Test
    fun lazyParsingSkipsMostOfTheCost() {
        val samples = corpus()
        repeat(3) { samples.forEach { KotlinSyntax.parse(it.text); KotlinSyntax.parse(it.text, lazy = true) } }

        println("lazy vs full parse, per file:")
        var worstLazy = 0L
        var totalFull = 0L
        var totalLazy = 0L
        for (sample in samples.sortedBy { it.lines }) {
            val full = fastestNanos(20) { KotlinSyntax.parse(sample.text) } / 1_000
            val lazy = fastestNanos(20) { KotlinSyntax.parse(sample.text, lazy = true) } / 1_000
            totalFull += full
            totalLazy += lazy
            worstLazy = maxOf(worstLazy, lazy)
            println("  %,6d lines  full %,6d us   lazy %,6d us   (%.0f%% saved)  %s".format(
                sample.lines, full, lazy, (full - lazy) * 100.0 / full, sample.name))
        }
        println("  totals: full %,d us, lazy %,d us — lazy is %.1fx faster".format(
            totalFull, totalLazy, totalFull.toDouble() / totalLazy))
        println("  worst lazy parse: %,d us against an 8,300 us 120Hz frame (%.0f%% of it)".format(
            worstLazy, worstLazy * 100.0 / 8_300))

        assertTrue(
            totalLazy < totalFull,
            "lazy parsing was not cheaper than full parsing, which would mean function bodies are not where " +
                "the time goes and this whole approach to incrementality is aimed at the wrong thing.",
        )
    }

    /**
     * What a keystroke costs, which is the number all of this was for.
     *
     * The caret is placed inside a real function body — found from the parse rather than guessed at an
     * arbitrary offset, since an offset outside every body measures the slow path and calls it the fast one.
     * Then a character is typed, alternating between two buffers so that each edit is a genuine change rather
     * than a no-op the fast path would refuse.
     */
    @Test
    fun aKeystrokeCostsFarLessThanAFullParse() {
        val samples = corpus()
        println("cost of one keystroke inside a function body:")
        var worstIncremental = 0L
        var totalFull = 0L
        var totalIncremental = 0L
        var skipped = 0
        var reparsed = 0

        for (sample in samples.sortedBy { it.lines }) {
            val caret = caretInsideABody(sample.text) ?: continue
            val typed = sample.text.substring(0, caret) + " " + sample.text.substring(caret)
            val twice = sample.text.substring(0, caret) + "  " + sample.text.substring(caret)

            val incremental = IncrementalKotlinParse(sample.text)
            incremental.blockAt(caret)
            repeat(4) {
                incremental.edit(typed); incremental.blockAt(caret)
                incremental.edit(twice); incremental.blockAt(caret)
            }

            val full = fastestNanos(10) { KotlinSyntax.parse(typed) } / 1_000
            var flip = false
            val keystroke = fastestNanos(20) {
                flip = !flip
                incremental.edit(if (flip) typed else twice)
                incremental.blockAt(caret)
            } / 1_000

            totalFull += full
            totalIncremental += keystroke
            worstIncremental = maxOf(worstIncremental, keystroke)
            println("  %,6d lines   full %,6d us   keystroke %,6d us   (%.0fx cheaper)  %s".format(
                sample.lines, full, keystroke, full.toDouble() / keystroke.coerceAtLeast(1), sample.name))
            skipped += incremental.fileReparsesSkipped
            reparsed += incremental.fileReparses
        }

        println("  totals: full %,d us, keystroke %,d us — %.0fx cheaper overall".format(
            totalFull, totalIncremental, totalFull.toDouble() / totalIncremental.coerceAtLeast(1)))
        println("  worst keystroke: %,d us against an 8,300 us 120Hz frame (%.1f%% of it)".format(
            worstIncremental, worstIncremental * 100.0 / 8_300))
        println("  file reparses skipped: $skipped, performed: $reparsed")

        assertTrue(
            totalIncremental < totalFull,
            "the incremental path was not cheaper than a full parse, which would mean the lazy/expand/skip " +
                "split buys nothing and the design is wrong.",
        )
    }

    /** An offset genuinely inside a collapsed body, found from the parse rather than guessed. */
    private fun caretInsideABody(text: String): Int? {
        val tree = KotlinSyntax.parse(text, lazy = true)
        var best: Int? = null
        fun walk(node: org.jetbrains.kotlin.kmp.tree.LightNode) {
            val children = tree.getChildren(node)
            val collapsed = tree.getType(node) == org.jetbrains.kotlin.kmp.parser.KtNodeTypes.BLOCK &&
                children.isNotEmpty() && children.all { tree.isToken(it) }
            val size = tree.getEndOffset(node) - tree.getStartOffset(node)
            // Prefer a big body: it is the interesting case, and a one-liner would flatter the measurement.
            if (collapsed && size > 200 && best == null) best = tree.getStartOffset(node) + 2
            if (!collapsed) children.forEach(::walk)
        }
        walk(tree.getRoot())
        return best
    }

    @Test
    fun aFullParseIsCheapEnoughThatIncrementalReparseIsNotNeeded() {
        val samples = corpus()
        repeat(3) { samples.forEach { KotlinSyntax.parse(it.text) } }

        println("full-parse latency by file size (the question incremental reparse exists to answer):")
        var worstMicros = 0L
        for (sample in samples.sortedBy { it.lines }) {
            val nanos = fastestNanos(20) { KotlinSyntax.parse(sample.text) }
            val micros = nanos / 1_000
            worstMicros = maxOf(worstMicros, micros)
            println("  %,6d lines  %,7d us  %s".format(sample.lines, micros, sample.name))
        }

        // A keystroke has a frame to land in: 16.6 ms at 60 Hz, 8.3 ms at 120 Hz.
        //
        // The answer here is NO for the largest files. On this machine the worst real file uses most of a
        // 120Hz frame, and that figure is a FLOOR twice over: it is the fastest of twenty runs on a warmed
        // JIT, and on ART it will be several times slower again. So incremental reparse is not made
        // redundant by raw speed, and the module should not pretend otherwise.
        //
        // The assertion is therefore a catastrophe bound, not the frame. Asserting the frame would be
        // asserting this machine: it passes here at ~80% and would flake on CI hardware, which is how a
        // benchmark turns into a test everybody reruns until it goes green.
        val frameMicros = 8_300
        println("  worst: %,d us against an %,d us 120Hz frame (%.0f%% of it)".format(
            worstMicros, frameMicros, worstMicros * 100.0 / frameMicros))
        println("  NOTE: fastest-of-20 on a warm JIT, so a floor; ART will be materially slower.")
        assertTrue(
            worstMicros < 50_000,
            "the largest real file takes ${worstMicros}us to parse. That is far beyond anything an editor " +
                "can do per keystroke, so something has regressed badly rather than merely got slower.",
        )
    }
}
