package dev.ide.lang.jdt.analysis

import dev.ide.analysis.AnalysisTarget
import dev.ide.analysis.AnalyzerId
import dev.ide.analysis.AnalyzerTier
import dev.ide.analysis.Codes
import dev.ide.analysis.Diagnostic
import dev.ide.analysis.DiagnosticProvider
import dev.ide.analysis.DiagnosticSink
import dev.ide.analysis.DiagnosticSource
import dev.ide.analysis.DiagnosticTag
import dev.ide.analysis.FileAnalyzer
import dev.ide.analysis.NodeIndex
import dev.ide.lang.LanguageId
import dev.ide.lang.jdt.JdtSourceAnalyzer
import dev.ide.lang.dom.DomNode
import dev.ide.lang.dom.NodeKind
import dev.ide.lang.dom.Severity
import dev.ide.lang.dom.TextRange

/**
 * Built-in Java analyzers and the JDT compiler adapter, contributed through [JdtAnalysisSupport.register].
 * They run the two analyzer tiers over the neutral DOM alongside the compiler, all merging into one
 * diagnostic stream the editor renders inline.
 */
private val JAVA = LanguageId("java")

/** SYNTAX: flag `System.out` / `System.err` used for logging (gated on METHOD_CALL nodes). */
class SystemOutCallAnalyzer : FileAnalyzer {
    override val id = AnalyzerId("java.systemOut")
    override val displayName = "System.out/err used for logging"
    override val languages = setOf(JAVA)
    override val defaultSeverity = Severity.WARNING
    override val tier = AnalyzerTier.SYNTAX
    override val interestedIn = setOf(NodeKind.METHOD_CALL)

    override fun analyze(target: AnalysisTarget, sink: DiagnosticSink) =
        analyze(target, sink, NodeIndex.over(target.parsed))

    override fun analyze(target: AnalysisTarget, sink: DiagnosticSink, nodes: NodeIndex) {
        // The prefix is tested against the source at the call's own start, not against a copy of the
        // call's text: an enclosing call's text includes every nested call's, so materializing it to
        // read eleven characters re-copies the inner ones at every level of nesting.
        val source = target.parsed.text()
        for (call in nodes.nodes(NodeKind.METHOD_CALL)) {
            val start = call.range.start
            // Require the member boundary (`System.out.`), so a user type's own member — `System.outLog.append(x)`
            // where `System` is the project's class — isn't mistaken for `java.lang.System.out`.
            val prefix = when {
                call.startsWith(source, "System.out.") -> "System.out"
                call.startsWith(source, "System.err.") -> "System.err"
                else -> continue
            }
            sink.report(
                range = TextRange(start, start + prefix.length),
                severity = defaultSeverity,
                message = "Avoid $prefix for logging; use a logger",
                code = "java.systemOut",
            )
        }
    }

    /** Whether this node's own text starts with [prefix], read off [source] in place, and never past
     *  the node's end (a node shorter than [prefix] cannot start with it, whatever follows it). */
    private fun DomNode.startsWith(source: CharSequence, prefix: String): Boolean =
        range.end - range.start >= prefix.length && source.startsWith(prefix, range.start)
}

/**
 * Flag a single-type import whose name is never referenced in the file body (tag UNUSED). This is a
 * syntactic heuristic: it matches the name as text in the non-import lines, with no binding resolution,
 * so it runs in the SYNTAX tier (a binding-precise version, which would distinguish a real reference from
 * a name in a comment/string, would be SEMANTIC). Tagging it SYNTAX also keeps the default editor profile
 * off the binding DOM parse, which is unreliable on an android.jar platform (see JdtSourceAnalyzer).
 */
class UnusedImportAnalyzer : FileAnalyzer {
    override val id = AnalyzerId("java.unusedImport")
    override val displayName = "Unused import"
    override val languages = setOf(JAVA)
    override val defaultSeverity = Severity.WARNING
    override val tier = AnalyzerTier.SYNTAX
    override val interestedIn = setOf(NodeKind.IMPORT_DECL)

    override fun analyze(target: AnalysisTarget, sink: DiagnosticSink) =
        analyze(target, sink, NodeIndex.over(target.parsed))

    override fun analyze(target: AnalysisTarget, sink: DiagnosticSink, nodes: NodeIndex) {
        val imports = nodes.nodes(NodeKind.IMPORT_DECL)
        if (imports.isEmpty()) return
        // The identifiers used outside the import lines, tokenized ONCE. Asking the question the other way
        // round (a `\bname\b` scan of the body per import) is O(imports x file size) and compiles a
        // pattern per import; a name either appears as an identifier token here or it does not.
        val used = identifiersOutsideImports(target.parsed.text())
        for (imp in imports) {
            val decl = imp.text().toString()
            if (decl.contains('*') || STATIC.containsMatchIn(decl)) continue // wildcard / static
            val name = decl.removePrefix("import").trim().removeSuffix(";").trim().substringAfterLast('.')
            if (name.isEmpty()) continue
            if (name !in used) {
                sink.report(
                    range = imp.range,
                    severity = defaultSeverity,
                    message = "Unused import '$name'",
                    code = "java.unusedImport",
                    tags = setOf(DiagnosticTag.UNUSED),
                )
            }
        }
    }

    private companion object {
        val STATIC = Regex("\\bstatic\\b")

        /**
         * Every identifier token in [source] outside the import lines, in one scan. Import lines are
         * excluded so a name that appears only in its own declaration reads as unused; everything else
         * counts, comments and string literals included, which is the same heuristic the `\bname\b`
         * scan applied (this analyzer resolves nothing; see the class doc).
         */
        fun identifiersOutsideImports(source: CharSequence): Set<String> {
            val used = HashSet<String>()
            var i = 0
            while (i < source.length) {
                // At a line start, skip the whole line when it is an import declaration.
                var lineStart = i
                while (lineStart < source.length && (source[lineStart] == ' ' || source[lineStart] == '\t')) lineStart++
                if (source.startsWith("import ", lineStart)) {
                    while (i < source.length && source[i] != '\n') i++
                    i++
                    continue
                }
                // Otherwise tokenize the line's identifiers.
                while (i < source.length && source[i] != '\n') {
                    if (!source[i].isJavaIdentifierStart()) { i++; continue }
                    val start = i
                    i++
                    while (i < source.length && source[i].isJavaIdentifierPart()) i++
                    used += source.subSequence(start, i).toString()
                }
                i++
            }
            return used
        }
    }
}

/**
 * The compiler unified into the pipeline: adapts the JDT analyzer's diagnostics
 * (which already carry source ranges over the live buffer) into [Diagnostic]s with
 * `source = `[DiagnosticSource.Compiler] and a stable [Codes] join key, so fixes can be attached by
 * code. From here it is indistinguishable from analyzer output. Scoped to `{java}` so it no longer runs
 * on Kotlin/XML targets now that every language flows through the one pipeline.
 *
 * When JDT is the editor backend the diagnostics come from its cached in-memory compiler; under the
 * IntelliJ-PSI editor backend the target's parsed tree already carries resolution-derived diagnostics, so
 * those are used directly (no JDT dependency in the `.java` diagnostic path).
 */
class CompilerDiagnosticProvider(override val id: String = "jdt") : DiagnosticProvider {
    override val languages = setOf(JAVA)

    override suspend fun diagnose(target: AnalysisTarget): List<Diagnostic> {
        // With a JDT editor resolver, diagnostics come from its cached in-memory compiler (no disk
        // environment scan, no shadow-file move), reusing the target's already-parsed syntactic tree for
        // noise filtering. Otherwise (IntelliJ-PSI backend) the parsed tree carries its own diagnostics.
        val analyzer = target.resolver as? JdtSourceAnalyzer
        val diagnostics = if (analyzer != null) analyzer.diagnose(target.file, target.parsed.text(), target.parsed)
        else target.parsed.diagnostics
        return diagnostics.map { d ->
            val code = compilerCode(d)
            Diagnostic(
                range = d.range,
                severity = d.severity,
                message = d.message,
                source = DiagnosticSource.Compiler,
                code = code,
                // Unused locals / private members render muted, like the analyzer-driven unused import.
                tags = if (code in JavaProblemCodes.UNUSED_CODES) setOf(DiagnosticTag.UNUSED) else emptySet(),
            )
        }
    }

    private fun compilerCode(d: dev.ide.lang.dom.Diagnostic): String? = when {
        d.code != null -> d.code
        SEMICOLON.containsMatchIn(d.message) -> Codes.MISSING_SEMICOLON
        d.message.contains("cannot be resolved") -> Codes.UNRESOLVED_REFERENCE
        else -> null
    }

    private companion object {
        val SEMICOLON = Regex("""insert ";"|';' expected|Syntax error.*insert""", RegexOption.IGNORE_CASE)
    }
}
