package dev.ide.analysis.impl

import dev.ide.analysis.AnalysisTarget
import dev.ide.analysis.AnalyzerId
import dev.ide.analysis.AnalyzerTier
import dev.ide.analysis.Diagnostic
import dev.ide.analysis.DiagnosticProvider
import dev.ide.analysis.DiagnosticSink
import dev.ide.analysis.DiagnosticSource
import dev.ide.analysis.FileAnalyzer
import dev.ide.analysis.NodeIndex
import dev.ide.analysis.ProjectAnalysisScope
import dev.ide.analysis.WorkspaceEdit
import dev.ide.bench.Bench
import dev.ide.index.IndexService
import dev.ide.lang.LanguageId
import dev.ide.lang.SourceAnalyzer
import dev.ide.lang.dom.DomNode
import dev.ide.lang.dom.NodeKind
import dev.ide.lang.dom.ParsedFile
import dev.ide.lang.dom.Severity
import dev.ide.lang.dom.TextRange
import dev.ide.model.Module
import dev.ide.testkit.InMemoryVirtualFile
import dev.ide.vfs.VirtualFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * What one file pass costs in the engine itself: the work every language pays regardless of how many
 * [FileAnalyzer]s actually apply (opt-in: `./gradlew :analysis-impl:regressionTest`). Two costs sit in a
 * pass, and the point of the measurement is that a pass now avoids both unless it needs them:
 *
 *  - **a DOM traversal**: the node-kind gate. It happens on the [dev.ide.analysis.NodeIndex]'s first
 *    query, so a pass whose analyzers are all whole-file ones never pays it.
 *  - **suppression**: a [SuppressionFilter] parsed out of the file's text. Built at most once per pass
 *    and only for a bucket that actually reported something.
 *
 * The wiring measured is a Kotlin file's: ONE whole-file analyzer (`packageMismatch`) plus a diagnostic
 * provider. The two costs are also measured standalone, as the yardstick for what the pass is skipping.
 *
 * The fixture is a file-shaped DOM at Compose-screen scale (~30KB of source, a few thousand nodes) with no
 * suppression directives in it, which is the overwhelmingly common case.
 */
@Tag("regression")
class EnginePassBenchmark {

    @Test
    fun enginePerPassOverhead() {
        val file = InMemoryVirtualFile("/src/Screen.kt")
        val src = source(lines = 600)
        val parsed = bigParsed(file, src, decls = 60, nodesPerDecl = 80)
        val target = FakeTarget(file, parsed)
        val nodeCount = parsed.nodesIn(parsed.range).count()

        // (1) suppression, standalone: what one build costs (a pass makes at most one, and only when a
        // bucket reported something).
        val suppressNs = Bench.nsPerOp { SuppressionFilter.from(parsed).retain(emptyList()).size.toLong() }
        val suppressAlloc = Bench.allocPerOp { SuppressionFilter.from(parsed).retain(emptyList()).size.toLong() }

        // (2) a full traversal, standalone: what the node-kind gate costs when an analyzer names kinds.
        val gateNs = Bench.nsPerOp { NodeIndex.over(parsed).kinds.size.toLong() }
        val gateAlloc = Bench.allocPerOp { NodeIndex.over(parsed).kinds.size.toLong() }

        // (3) a whole pass, wired the way a Kotlin file is: ONE whole-file SYNTAX analyzer
        // (`interestedIn = null`, like `packageMismatch`) plus a diagnostic provider (the compiler).
        val whole = WholeFileAnalyzer()
        val withAnalyzer = engine(listOf(whole), file, target)
        val passNs = Bench.nsPerOp { runBlocking { withAnalyzer.analyzeNow(file).size.toLong() } }
        val passAlloc = Bench.allocPerOp { runBlocking { withAnalyzer.analyzeNow(file).size.toLong() } }

        // The same pass with no FileAnalyzer at all: `collect` returns before walking, so the difference is
        // the gate walk plus the analyzer's own (trivial) work.
        val bare = engine(emptyList(), file, target)
        val bareNs = Bench.nsPerOp { runBlocking { bare.analyzeNow(file).size.toLong() } }
        val bareAlloc = Bench.allocPerOp { runBlocking { bare.analyzeNow(file).size.toLong() } }

        println("\n=== Engine per-pass overhead (${src.length} B source, $nodeCount DOM nodes) ===")
        println("standalone   one suppression build : %9s %9s".format(Bench.ns(suppressNs), Bench.bytes(suppressAlloc.toDouble())))
        println("             one DOM traversal     : %9s %9s".format(Bench.ns(gateNs), Bench.bytes(gateAlloc.toDouble())))
        println("analyzeNow   1 whole-file analyzer : %9s %9s".format(Bench.ns(passNs), Bench.bytes(passAlloc.toDouble())))
        println("             no file analyzers     : %9s %9s".format(Bench.ns(bareNs), Bench.bytes(bareAlloc.toDouble())))
        println("=================================================================\n")

        assertTrue(whole.invocations > 0, "the whole-file analyzer never ran")
        assertTrue(suppressNs > 0.0 && gateNs > 0.0 && passNs > 0.0 && bareNs > 0.0, "measured nothing")
        // The pass must not have traversed the DOM: a walk allocates a wrapper per node, so a pass that
        // walked could not come in at a fraction of one walk's allocation.
        assertTrue(
            passAlloc < gateAlloc / 4,
            "a whole-file-analyzer pass allocated ${Bench.bytes(passAlloc.toDouble())}, near a full " +
                "traversal's ${Bench.bytes(gateAlloc.toDouble())}, so it is walking the DOM again",
        )
        assertTrue(Bench.sink != Long.MIN_VALUE)
    }

    private fun engine(analyzers: List<FileAnalyzer>, file: VirtualFile, target: AnalysisTarget) = AnalysisEngine(
        analyzers,
        emptyList(),
        listOf(Compiler()),
        FixedEnv(file, target),
        CoroutineScope(Job()),
        config = SchedulerConfig(0, 0, 0),
    )

    // ---- fixtures ----

    private companion object {
        /** ~600 lines of plausible Kotlin, with no `@Suppress` / `// noinspection` in it. */
        fun source(lines: Int): String = buildString {
            appendLine("package com.example.ui.screens")
            appendLine("import androidx.compose.runtime.Composable")
            for (i in 0 until lines / 6) {
                appendLine("@Composable")
                appendLine("fun Section$i(state: UiState, onEvent: (UiEvent) -> Unit) {")
                appendLine("    Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {")
                appendLine("        Text(text = state.title$i, style = MaterialTheme.typography.titleMedium)")
                appendLine("        Button(onClick = { onEvent(UiEvent.Tap($i)) }) { Text(\"action $i\") }")
                appendLine("    }")
                appendLine("}")
            }
        }

        /** A file-shaped DOM: [decls] declarations, each holding [nodesPerDecl] leaves. */
        fun bigParsed(file: VirtualFile, src: String, decls: Int, nodesPerDecl: Int): ParsedFile {
            val span = (src.length / decls).coerceAtLeast(nodesPerDecl + 2)
            val top = (0 until decls).map { d ->
                val start = d * span
                val leaves = (0 until nodesPerDecl).map { n ->
                    BenchNode(if (n % 3 == 0) NodeKind.METHOD_CALL else NodeKind.LOCAL_VAR, TextRange(start + n, start + n + 1))
                }
                BenchNode(NodeKind.METHOD_DECL, TextRange(start, start + span - 1), leaves)
            }
            return BenchParsed(file, src, top)
        }
    }

    private class WholeFileAnalyzer : FileAnalyzer {
        override val id = AnalyzerId("packageMismatch.like")
        override val displayName = "whole-file analyzer"
        override val languages = setOf(LanguageId("kotlin"))
        override val defaultSeverity = Severity.WARNING
        override val tier = AnalyzerTier.SYNTAX
        override val interestedIn: Set<NodeKind>? = null // whole-file, like every ide-core analyzer
        var invocations = 0; private set
        override fun analyze(target: AnalysisTarget, sink: DiagnosticSink) {
            invocations++
            // What packageMismatch does: read the head of the file and compare it to the path.
            val text = target.parsed.text()
            if (!text.startsWith("package com.example.ui.screens")) {
                sink.report(TextRange(0, 7), defaultSeverity, "package does not match", code = "packageMismatch")
            }
        }
    }

    /** Stands in for the Kotlin/JDT diagnostic provider: returns a handful of findings, as a real file has. */
    private class Compiler : DiagnosticProvider {
        override val id = "compiler"
        override val languages = setOf(LanguageId("kotlin"))
        override suspend fun diagnose(target: AnalysisTarget): List<Diagnostic> =
            (0 until 5).map { Diagnostic(TextRange(it * 10, it * 10 + 4), Severity.WARNING, "finding $it", DiagnosticSource.Compiler, "kt.x$it") }
    }

    private class FixedEnv(private val file: VirtualFile, private val target: AnalysisTarget) : AnalysisEnvironment {
        override suspend fun targetFor(f: VirtualFile, needsBindings: Boolean): AnalysisTarget? =
            if (f.path == file.path) target else null
        override fun languageOf(f: VirtualFile): LanguageId = LanguageId("kotlin")
        override fun projectScope(): ProjectAnalysisScope = error("no project pass in this benchmark")
        override suspend fun applyEdit(edit: WorkspaceEdit): WorkspaceEdit = edit
    }

    private class FakeTarget(override val file: VirtualFile, override val parsed: ParsedFile) : AnalysisTarget {
        override val documentVersion = 1L
        override val resolver: SourceAnalyzer get() = error("unused")
        override val index: IndexService get() = error("unused")
        override val module: Module get() = error("unused")
        override fun checkCanceled() {}
    }

    private class BenchNode(
        override val kind: NodeKind,
        override val range: TextRange,
        override val children: List<BenchNode> = emptyList(),
    ) : DomNode {
        override var parent: DomNode? = null
        init { children.forEach { it.parent = this } }
        override fun text(): CharSequence = ""
    }

    private class BenchParsed(
        override val file: VirtualFile,
        private val src: String,
        private val top: List<BenchNode>,
    ) : ParsedFile {
        override val kind = NodeKind.COMPILATION_UNIT
        override val range = TextRange(0, src.length)
        override val parent: DomNode? = null
        override val children: List<DomNode> = top
        override val documentVersion = 1L
        override val diagnostics: List<dev.ide.lang.dom.Diagnostic> = emptyList()
        init { top.forEach { it.parent = this } }
        override fun text(): CharSequence = src
        override fun nodeAt(offset: Int): DomNode {
            var best: DomNode = this
            fun visit(n: DomNode) { if (offset in n.range) { best = n; n.children.forEach(::visit) } }
            children.forEach(::visit)
            return best
        }
        override fun nodesIn(range: TextRange): Sequence<DomNode> {
            val out = ArrayList<DomNode>()
            fun walk(n: DomNode) { if (n.range.intersects(range)) { out += n; n.children.forEach(::walk) } }
            children.forEach(::walk)
            return out.asSequence()
        }
    }
}
