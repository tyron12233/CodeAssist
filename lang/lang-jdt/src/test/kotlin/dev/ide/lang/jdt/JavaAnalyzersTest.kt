package dev.ide.lang.jdt

import dev.ide.analysis.AnalysisTarget
import dev.ide.analysis.DiagnosticSink
import dev.ide.analysis.DiagnosticTag
import dev.ide.analysis.NodeIndex
import dev.ide.analysis.QuickFix
import dev.ide.analysis.RelatedRange
import dev.ide.index.IndexService
import dev.ide.lang.SourceAnalyzer
import dev.ide.lang.dom.ParsedFile
import dev.ide.lang.dom.Severity
import dev.ide.lang.dom.TextRange
import dev.ide.lang.jdt.analysis.SystemOutCallAnalyzer
import dev.ide.lang.jdt.analysis.UnusedImportAnalyzer
import dev.ide.model.Module
import dev.ide.vfs.VirtualFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The two built-in Java [dev.ide.analysis.FileAnalyzer]s, driven the way the engine drives them (over the
 * pass's shared [NodeIndex]). Both are syntactic heuristics with a boundary rule that is easy to get
 * subtly wrong (`System.out.` must not match `System.outLog.`, and an import of `List` must not read
 * as used because the body mentions `ListView`), so the boundaries are what these cover.
 */
class JavaAnalyzersTest {

    // ---- java.systemOut ----

    @Test
    fun flagsSystemOutAndErrWithTheRangeOfTheStreamOnly() {
        val src = """
            package app;
            class T {
                void m() {
                    System.out.println("hi");
                    System.err.print("bad");
                }
            }
        """.trimIndent()
        val found = systemOut(src)
        assertEquals(2, found.size, "expected one finding per stream; got $found")
        assertTrue(found.all { it.code == "java.systemOut" })
        // The squiggle covers `System.out`, not the whole statement.
        assertEquals(listOf("System.out", "System.err"), found.map { src.substring(it.range.start, it.range.end) })
    }

    @Test
    fun doesNotFlagAMemberWhoseNameMerelyStartsWithOut() {
        // `System` here is the project's own class: `System.outLog` is not `java.lang.System.out`, and the
        // member boundary (the `.` after `out`) is the only thing separating them syntactically.
        val src = """
            package app;
            class T {
                static Appendable outLog;
                void m() { System.outLog.append("x"); }
            }
        """.trimIndent()
        assertEquals(emptyList(), systemOut(src).map { it.message })
    }

    @Test
    fun flagsTheOuterCallOnceWhenCallsNest() {
        val src = """
            package app;
            class T {
                void m() { System.out.println(compute(name())); }
                String compute(String s) { return s; }
                String name() { return "n"; }
            }
        """.trimIndent()
        assertEquals(1, systemOut(src).size, "the nested calls must not each report")
    }

    @Test
    fun ignoresShortCallsWithoutReadingPastThem() {
        // A call shorter than "System.out." must not be tested against the text that follows it.
        val src = "package app;\nclass T { void m() { f(); }\n void f() {} }"
        assertEquals(emptyList(), systemOut(src).map { it.message })
    }

    // ---- java.unusedImport ----

    @Test
    fun flagsAnUnusedSingleTypeImport() {
        val src = """
            package app;
            import java.util.List;
            import java.util.Map;
            class T { Map<String, String> m; }
        """.trimIndent()
        val found = unusedImports(src)
        assertEquals(listOf("Unused import 'List'"), found.map { it.message })
        assertTrue(DiagnosticTag.UNUSED in found.single().tags, "the fix's muted rendering keys on the UNUSED tag")
        assertEquals("import java.util.List;", src.substring(found.single().range.start, found.single().range.end))
    }

    @Test
    fun leavesWildcardAndStaticImportsAlone() {
        val src = """
            package app;
            import java.util.*;
            import static java.util.Arrays.asList;
            class T {}
        """.trimIndent()
        assertEquals(emptyList(), unusedImports(src).map { it.message })
    }

    @Test
    fun aNameUsedOnlyInAnotherImportLineIsStillUnused() {
        // `List` appears in the file only inside import declarations, so it is not used by the code.
        val src = """
            package app;
            import java.util.List;
            import java.util.ListIterator;
            class T { ListIterator<String> it; }
        """.trimIndent()
        assertEquals(listOf("Unused import 'List'"), unusedImports(src).map { it.message })
    }

    @Test
    fun aNameIsUsedOnlyAsAWholeIdentifier() {
        // `ListView` must not count as a use of `List`: the boundary this analyzer's whole answer rests on.
        val src = """
            package app;
            import java.util.List;
            class T { ListView v; }
            class ListView {}
        """.trimIndent()
        assertEquals(listOf("Unused import 'List'"), unusedImports(src).map { it.message })
    }

    @Test
    fun aMentionInACommentOrStringCountsAsUse() {
        // The documented heuristic: no resolution, so any identifier token outside the imports counts.
        val src = """
            package app;
            import java.util.List;
            class T { /* returns a List one day */ void m() {} }
        """.trimIndent()
        assertEquals(emptyList(), unusedImports(src).map { it.message })
    }

    // ---- harness ----

    private fun systemOut(src: String) = run(src) { target, sink, nodes ->
        SystemOutCallAnalyzer().analyze(target, sink, nodes)
    }

    private fun unusedImports(src: String) = run(src) { target, sink, nodes ->
        UnusedImportAnalyzer().analyze(target, sink, nodes)
    }

    private fun run(
        src: String, analyze: (AnalysisTarget, DiagnosticSink, NodeIndex) -> Unit,
    ): List<Finding> {
        val (analyzer, dir) = workspaceWith("app/Empty.java" to "package app; class Empty {}")
        return try {
            val file = StubFile(dir.resolve("app/T.java").toString(), src)
            val parsed = analyzer.parseSyntactic(file, src)
            val sink = RecordingSink()
            analyze(Target(file, parsed, analyzer), sink, NodeIndex.over(parsed))
            sink.found
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private class Finding(val range: TextRange, val message: String, val code: String?, val tags: Set<DiagnosticTag>) {
        override fun toString() = "$code@${range.start}..${range.end}: $message"
    }

    private class RecordingSink : DiagnosticSink {
        val found = ArrayList<Finding>()
        override fun report(
            range: TextRange, severity: Severity, message: String, code: String?,
            fixes: List<QuickFix>, tags: Set<DiagnosticTag>, related: List<RelatedRange>,
        ) { found += Finding(range, message, code, tags) }
    }

    private class Target(
        override val file: VirtualFile,
        override val parsed: ParsedFile,
        override val resolver: SourceAnalyzer,
    ) : AnalysisTarget {
        override val documentVersion = 1L
        override val index: IndexService get() = error("these analyzers do not query the index")
        override val module: Module get() = error("these analyzers do not read the module")
        override fun checkCanceled() {}
    }
}
