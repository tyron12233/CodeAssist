package dev.ide.core.analysis

import dev.ide.analysis.AnalysisTarget
import dev.ide.analysis.AnalyzerId
import dev.ide.analysis.AnalyzerTier
import dev.ide.analysis.DiagnosticSink
import dev.ide.analysis.FileAnalyzer
import dev.ide.android.support.aidl.AidlCompiler
import dev.ide.android.support.aidl.AidlDiagnostic
import dev.ide.android.support.aidl.AidlFile
import dev.ide.android.support.aidl.AidlJavaGenerator
import dev.ide.android.support.aidl.AidlParser
import dev.ide.android.support.aidl.AidlPos
import dev.ide.android.support.aidl.AidlSeverity
import dev.ide.android.support.aidl.AidlSyntaxException
import dev.ide.android.support.aidl.AidlTypeTable
import dev.ide.lang.LanguageId
import dev.ide.lang.dom.NodeKind
import dev.ide.lang.dom.Severity
import dev.ide.lang.dom.TextRange
import dev.ide.model.ContentRole
import dev.ide.model.DependencyScope
import dev.ide.model.Module
import dev.ide.model.ModuleDependency
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Editor diagnostics for `.aidl` files, produced by the same parser and generator the build runs, so what
 * the editor reports and what a build reports cannot drift apart.
 *
 * AIDL has no language backend (the file is a declaration; what needs resolving is the Java it produces),
 * so without this an invalid interface stays silent until a build fails. The checks that matter are exactly
 * the ones a developer hits: a syntax slip, a parameter missing its `in`/`out`/`inout`, a `oneway` method
 * with a return value, a misspelled or unimported type.
 *
 * SEMANTIC tier: resolving a type reference needs the module's other `.aidl` files, which are read from
 * disk, so this runs on the settled buffer rather than on every keystroke. Only errors are surfaced; the
 * compiler's advisory warnings (a framework type classified by name, a package that does not match the
 * file's location) belong to the build, where the full SDK type table is available.
 */
class AidlAnalyzer : FileAnalyzer {
    override val id = AnalyzerId("aidl")
    override val displayName = "AIDL problems"
    override val languages = setOf(LanguageId("aidl"))
    override val defaultSeverity = Severity.ERROR
    override val tier = AnalyzerTier.SEMANTIC
    override val interestedIn: Set<NodeKind>? = null // whole-file, invoked once

    override fun analyze(target: AnalysisTarget, sink: DiagnosticSink) {
        val path = target.file.path
        val text = target.parsed.text().toString()
        val lines = LineOffsets(text)

        val parsed = try {
            AidlParser.parse(text, path)
        } catch (e: AidlSyntaxException) {
            sink.report(lines.rangeAt(e.pos), Severity.ERROR, e.message.orEmpty(), CODE)
            return
        }

        // The buffer's own declarations plus the rest of the module's, so a type declared in a sibling file
        // resolves. The on-disk copy of this file is dropped in favour of what is being edited.
        val table = AidlTypeTable.of(siblings(target.module, Paths.get(path)) + parsed)
        for (decl in parsed.declarations) {
            AidlJavaGenerator.generate(parsed, decl, table) { diagnostic ->
                if (diagnostic.severity == AidlSeverity.ERROR) {
                    sink.report(lines.rangeAt(diagnostic.pos), Severity.ERROR, diagnostic.message, CODE)
                }
            }
        }
    }

    /** The module's other `.aidl` files, and its direct dependencies', parsed for the types they declare. */
    private fun siblings(module: Module, self: Path): List<AidlFile> {
        val ignored = ArrayList<AidlDiagnostic>()
        return aidlRoots(module).flatMap { AidlCompiler.aidlFilesUnder(it) }
            .filter { runCatching { !it.toRealPath().equals(self.toRealPath()) }.getOrDefault(it != self) }
            .mapNotNull { AidlCompiler.parse(it, ignored, AidlSeverity.WARNING) }
    }

    private fun aidlRoots(module: Module): List<Path> {
        val own = module.sourceSets.filter { it.scope != DependencyScope.TEST_IMPLEMENTATION }
            .flatMap { it.contentRoots }
            .filter { ContentRole.AIDL in it.roles }
            .map { Paths.get(it.dir.path) }
        // Dependency modules are reached by name through the model, which the analyzer does not carry; the
        // module's own roots cover the common case (a service and its parcelables live together).
        return own
    }

    /** 1-based line/column positions resolved against the buffer's line starts. */
    private class LineOffsets(private val text: String) {
        private val starts: IntArray = buildList {
            add(0)
            text.forEachIndexed { i, c -> if (c == '\n') add(i + 1) }
        }.toIntArray()

        /**
         * The range to underline for a diagnostic at [pos]: the identifier under it when there is one, else
         * the rest of the line. A whole-line squiggle for a one-token problem reads as noise.
         */
        fun rangeAt(pos: AidlPos): TextRange {
            if (pos.line <= 0) return TextRange(0, minOf(1, text.length))
            val lineStart = starts.getOrElse(pos.line - 1) { return TextRange(0, minOf(1, text.length)) }
            val lineEnd = starts.getOrElse(pos.line) { text.length + 1 } - 1
            val start = (lineStart + (pos.column - 1).coerceAtLeast(0)).coerceIn(lineStart, lineEnd)
            var end = start
            while (end < lineEnd && (text[end].isLetterOrDigit() || text[end] == '_' || text[end] == '.')) end++
            if (end == start) end = lineEnd
            return TextRange(start, end.coerceAtLeast(minOf(start + 1, text.length)))
        }
    }

    private companion object {
        const val CODE = "aidl"
    }
}
