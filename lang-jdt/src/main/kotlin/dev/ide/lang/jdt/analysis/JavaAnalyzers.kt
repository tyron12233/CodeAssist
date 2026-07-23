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
import dev.ide.lang.LanguageId
import dev.ide.lang.jdt.JdtSourceAnalyzer
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

    override fun analyze(target: AnalysisTarget, sink: DiagnosticSink) {
        for (call in target.parsed.nodesIn(target.parsed.range)) {
            if (call.kind != NodeKind.METHOD_CALL) continue
            val text = call.text().toString()
            // Require the member boundary (`System.out.`), so a user type's own member — `System.outLog.append(x)`
            // where `System` is the project's class — isn't mistaken for `java.lang.System.out`.
            val prefix = when {
                text.startsWith("System.out.") -> "System.out"
                text.startsWith("System.err.") -> "System.err"
                else -> continue
            }
            val start = call.range.start
            sink.report(
                range = TextRange(start, start + prefix.length),
                severity = defaultSeverity,
                message = "Avoid $prefix for logging; use a logger",
                code = "java.systemOut",
            )
        }
    }
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

    override fun analyze(target: AnalysisTarget, sink: DiagnosticSink) {
        val source = target.parsed.text().toString()
        // The "body" is everything but the import lines, so a name that appears only in its own import
        // declaration reads as unused. Cheap, and good enough without full reference resolution.
        val body = source.lineSequence().filterNot { it.trimStart().startsWith("import ") }.joinToString("\n")
        for (imp in target.parsed.nodesIn(target.parsed.range)) {
            if (imp.kind != NodeKind.IMPORT_DECL) continue
            val decl = imp.text().toString()
            if (decl.contains('*') || Regex("\\bstatic\\b").containsMatchIn(decl)) continue // wildcard / static
            val name = decl.removePrefix("import").trim().removeSuffix(";").trim().substringAfterLast('.')
            if (name.isEmpty()) continue
            if (!Regex("\\b${Regex.escape(name)}\\b").containsMatchIn(body)) {
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
