package dev.ide.lang.jdt

import dev.ide.analysis.AnalysisTarget
import dev.ide.analysis.DiagnosticSink
import dev.ide.analysis.DiagnosticTag
import dev.ide.analysis.FileAnalyzer
import dev.ide.analysis.NodeIndex
import dev.ide.analysis.QuickFix
import dev.ide.analysis.RelatedRange
import dev.ide.bench.Bench
import dev.ide.index.IndexService
import dev.ide.lang.SourceAnalyzer
import dev.ide.lang.dom.NodeKind
import dev.ide.lang.dom.ParsedFile
import dev.ide.lang.dom.Severity
import dev.ide.lang.dom.TextRange
import dev.ide.lang.jdt.analysis.SystemOutCallAnalyzer
import dev.ide.lang.jdt.analysis.UnusedImportAnalyzer
import dev.ide.model.Module
import dev.ide.vfs.VirtualFile
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * What the two built-in Java [FileAnalyzer]s cost per pass (opt-in: `./gradlew :lang-jdt:regressionTest`).
 *
 * Both are SYNTAX tier, so the engine runs them on every diagnostics pass over the file being typed in,
 * over the same syntactic DOM and the same shared [NodeIndex] the engine builds once per pass.
 *
 * The dominant cost of such a pass is not the analyzers' logic but the DOM traversal that feeds it: on the
 * JDT DOM a walk allocates a node wrapper per node, so a 34KB file costs ~2MB a walk. Hence the two things
 * measured here: the per-pass total as the engine now runs it, and (in the second test) what the two
 * design choices behind that number are worth, since both were once the other way round.
 */
@Tag("regression")
class FileAnalyzerBenchmark {

    @Test
    fun javaFileAnalyzersPerPassCost() {
        val (analyzer, dir) = workspaceWith("app/Empty.java" to "package app; class Empty {}")
        try {
            val rows = ArrayList<String>()
            var typicalPass = 0.0

            for (shape in SHAPES) {
                val src = fixture(shape)
                val file = StubFile(dir.resolve("app/Bench.java").toString(), src)
                val parsed = analyzer.parseSyntactic(file, src)
                val target = Target(file, parsed, analyzer)
                val calls = NodeIndex.over(parsed).nodes(NodeKind.METHOD_CALL).size

                val sysOut = SystemOutCallAnalyzer()
                val unused = UnusedImportAnalyzer()
                // The shared traversal, on its own: the floor under everything below.
                val walkNs = Bench.nsPerOp { NodeIndex.over(parsed).kinds.size.toLong() }
                val walkAlloc = Bench.allocPerOp { NodeIndex.over(parsed).kinds.size.toLong() }
                // Each analyzer given that index: its own work, with no traversal of its own.
                val walked = walked(parsed)
                val sysOutNs = Bench.nsPerOp { given(target, walked) { t, s, n -> sysOut.analyze(t, s, n) } }
                val unusedNs = Bench.nsPerOp { given(target, walked) { t, s, n -> unused.analyze(t, s, n) } }
                // The whole SYNTAX pass as the engine runs it: one index, gate, both analyzers.
                val passNs = Bench.nsPerOp { pass(target, sysOut, unused) }
                val passAlloc = Bench.allocPerOp { pass(target, sysOut, unused) }

                rows += ("%-28s %6d B src %4d imports %5d calls | walk %8s %8s" +
                    " | systemOut %8s | unusedImport %8s | whole pass %8s %8s").format(
                    shape.name, src.length, shape.imports, calls,
                    Bench.ns(walkNs), Bench.bytes(walkAlloc.toDouble()),
                    Bench.ns(sysOutNs), Bench.ns(unusedNs),
                    Bench.ns(passNs), Bench.bytes(passAlloc.toDouble()),
                )
                if (shape === SHAPES[1]) typicalPass = passNs
            }

            println("\n=== Java FileAnalyzer cost per SYNTAX pass ===")
            rows.forEach(::println)
            println("==============================================\n")

            assertTrue(typicalPass > 0.0, "the benchmark measured nothing")
            // Per-keystroke work on the focal file: a pass costing more than a frame is a regression worth
            // failing on, however slow the machine.
            assertTrue(typicalPass < 16_000_000, "the SYNTAX pass took ${Bench.ns(typicalPass)} on a typical file")
            assertTrue(Bench.sink != Long.MIN_VALUE)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * What the two design choices in that pass are worth, measured against the shapes they replaced:
     *
     *  - **one shared traversal**: the engine's node-kind gate hands each analyzer the nodes it asked
     *    for. Before, the gate collected a kind *set* and each analyzer then walked the DOM again to find
     *    its own nodes: three traversals for the pass above, of which two were redundant.
     *  - **one tokenized body**: `java.unusedImport` tokenizes the non-import text once and looks each
     *    import name up. Before, it compiled a `\bname\b` pattern per import and scanned the whole body
     *    with it, which is O(imports x file size).
     */
    @Test
    fun sharedTraversalAndTokenSetWins() {
        val (analyzer, dir) = workspaceWith("app/Empty.java" to "package app; class Empty {}")
        try {
            val shape = SHAPES[1] // the typical file
            val src = fixture(shape)
            val file = StubFile(dir.resolve("app/Bench.java").toString(), src)
            val parsed = analyzer.parseSyntactic(file, src)

            val threeWalks = Bench.nsPerOp { threeWalks(parsed) }
            val threeWalksAlloc = Bench.allocPerOp { threeWalks(parsed) }
            val oneWalk = Bench.nsPerOp { NodeIndex.over(parsed).kinds.size.toLong() }
            val oneWalkAlloc = Bench.allocPerOp { NodeIndex.over(parsed).kinds.size.toLong() }

            // The unused-import question, both ways, over the same body text.
            val body = src.lineSequence().filterNot { it.trimStart().startsWith("import ") }.joinToString("\n")
            val names = (0 until shape.imports).map { "Type$it" }
            val regexPerImport = Bench.nsPerOp {
                var n = 0L
                for (name in names) if (Regex("\\b${Regex.escape(name)}\\b").containsMatchIn(body)) n++
                n
            }
            val unused = UnusedImportAnalyzer()
            val target = Target(file, parsed, analyzer)
            val walked = walked(parsed)
            val tokenized = Bench.nsPerOp { given(target, walked) { t, s, n -> unused.analyze(t, s, n) } }

            println("\n=== Why the pass is shaped this way (typical file: ${src.length} B, ${shape.imports} imports) ===")
            println("traversals     three separate walks : %9s %9s".format(Bench.ns(threeWalks), Bench.bytes(threeWalksAlloc.toDouble())))
            println("               one shared index     : %9s %9s".format(Bench.ns(oneWalk), Bench.bytes(oneWalkAlloc.toDouble())))
            println("unused-import  regex per import     : %9s".format(Bench.ns(regexPerImport)))
            println("               tokenized once       : %9s".format(Bench.ns(tokenized)))
            println("=========================================================================\n")

            assertTrue(oneWalk < threeWalks, "one shared walk should beat three: $oneWalk vs $threeWalks")
            assertTrue(tokenized < regexPerImport, "the tokenized scan should beat a regex per import")
            assertTrue(Bench.sink != Long.MIN_VALUE)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private class Shape(val name: String, val imports: Int, val methods: Int, val callsPerMethod: Int)

    private companion object {
        val SHAPES = listOf(
            Shape("small (5 imp, 20 calls)", imports = 5, methods = 4, callsPerMethod = 5),
            Shape("typical (30 imp, 400 calls)", imports = 30, methods = 40, callsPerMethod = 10),
            Shape("import-heavy (60 imp)", imports = 60, methods = 40, callsPerMethod = 10),
            Shape("call-heavy (1600 calls)", imports = 30, methods = 80, callsPerMethod = 20),
        )

        /** A plausible Java source file: real imports (half of them used), methods full of calls, and the
         *  nested-call shape (`a(b(c(x)))`) whose text a per-node `text()` copy re-materializes at depth. */
        fun fixture(shape: Shape): String = buildString {
            appendLine("package app;")
            for (i in 0 until shape.imports) appendLine("import java.util.pkg$i.Type$i;")
            appendLine("public class Bench {")
            for (i in 0 until shape.imports / 2) appendLine("    Type$i field$i;")
            for (m in 0 until shape.methods) {
                appendLine("    void method$m(String arg) {")
                for (c in 0 until shape.callsPerMethod) {
                    appendLine("        sink(wrap(trim(arg.substring($c), \"x\"), $c), method${m}Name());")
                }
                appendLine("        System.out.println(\"log from method$m\");")
                appendLine("    }")
                appendLine("    String method${m}Name() { return \"method$m\"; }")
            }
            appendLine("    void sink(Object a, Object b) {}")
            appendLine("    String wrap(String s, int i) { return s; }")
            appendLine("    String trim(String s, String t) { return s; }")
            appendLine("}")
        }

        /** The old shape: a gate walk for the kind set, then a walk inside each of the two analyzers. */
        fun threeWalks(parsed: ParsedFile): Long {
            var n = 0L
            n += parsed.nodesIn(parsed.range).mapTo(HashSet()) { it.kind }.size
            n += parsed.nodesIn(parsed.range).count { it.kind == NodeKind.METHOD_CALL }
            n += parsed.nodesIn(parsed.range).count { it.kind == NodeKind.IMPORT_DECL }
            return n
        }
    }

    /** One analyzer over an ALREADY-WALKED index, so what is timed is the analyzer's own work. */
    private fun given(target: AnalysisTarget, nodes: NodeIndex, analyze: (AnalysisTarget, DiagnosticSink, NodeIndex) -> Unit): Long {
        val sink = CountingSink()
        analyze(target, sink, nodes)
        return sink.n.toLong()
    }

    /** An index whose traversal has already happened: the state the second analyzer of a pass sees. */
    private fun walked(parsed: ParsedFile): NodeIndex = NodeIndex.over(parsed).also { it.kinds }

    /** The SYNTAX pass as the engine runs it: one index, the gate, then both analyzers. */
    private fun pass(target: AnalysisTarget, vararg analyzers: FileAnalyzer): Long {
        val nodes = NodeIndex.over(target.parsed)
        val sink = CountingSink()
        for (a in analyzers) {
            val interested = a.interestedIn
            if (interested != null && interested.none { it in nodes }) continue
            a.analyze(target, sink, nodes)
        }
        return sink.n.toLong()
    }

    private class CountingSink : DiagnosticSink {
        var n = 0
        override fun report(
            range: TextRange, severity: Severity, message: String, code: String?,
            fixes: List<QuickFix>, tags: Set<DiagnosticTag>, related: List<RelatedRange>,
        ) { n++ }
    }

    private class Target(
        override val file: VirtualFile,
        override val parsed: ParsedFile,
        private val analyzer: SourceAnalyzer? = null,
    ) : AnalysisTarget {
        override val documentVersion = 1L
        override val resolver: SourceAnalyzer get() = analyzer ?: error("the analyzers do not resolve")
        override val index: IndexService get() = error("the analyzers do not query the index")
        override val module: Module get() = error("the analyzers do not read the module")
        override fun checkCanceled() {}
    }
}
