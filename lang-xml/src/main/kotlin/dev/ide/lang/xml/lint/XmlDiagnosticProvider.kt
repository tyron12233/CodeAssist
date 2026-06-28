package dev.ide.lang.xml.lint

import dev.ide.analysis.AnalysisTarget
import dev.ide.analysis.AnalyzerId
import dev.ide.analysis.Diagnostic
import dev.ide.analysis.DiagnosticProvider
import dev.ide.analysis.DiagnosticSource
import dev.ide.analysis.QuickFix
import dev.ide.lang.dom.Severity
import dev.ide.lang.dom.TextRange
import dev.ide.lang.xml.XmlLanguageBackend
import dev.ide.lang.xml.lint.XmlLintRules.AttributeProblem

/**
 * The XML editor diagnostics, unified onto the analysis pipeline. Three concerns, each delegated:
 *  - **well-formedness** - straight from the tolerant parser's own diagnostics;
 *  - **lint rules** - pure detection in [XmlLintRules] (namespaces, hardcoded text, missing size, and the
 *    wrong-attribute / wrong-value checks), with the Android *schema* questions answered by the injected
 *    [XmlAttributeChecker];
 *  - **resource resolution** - the unresolved-`@type/name` check, with lookups + the resource-creating fixes'
 *    filesystem I/O behind [XmlResourceHost].
 *
 * Quick-fixes are built by [XmlQuickFixes]; this class only locates problems and maps them to [Diagnostic]s.
 * Declares `languages = {xml}`.
 */
class XmlDiagnosticProvider(
    private val host: XmlResourceHost,
    private val attributes: XmlAttributeChecker = XmlAttributeChecker.NONE,
    override val id: String = "xml",
) : DiagnosticProvider {
    override val languages = setOf(XmlLanguageBackend.LANGUAGE_ID)

    override suspend fun diagnose(target: AnalysisTarget): List<Diagnostic> {
        val parsed = target.parsed
        val file = target.file
        val text = parsed.text().toString()
        val path = file.path.replace('\\', '/')
        val isLayout = path.contains("/res/layout")
        val out = ArrayList<Diagnostic>()

        // Well-formedness (the tolerant parser's own diagnostics).
        for (d in parsed.diagnostics) {
            out += Diagnostic(d.range, d.severity, d.message, DiagnosticSource.Analyzer(AnalyzerId("xml.syntax")), d.code)
        }

        // A) A namespace prefix (android/app/tools) is used but not declared on the root.
        for (hit in XmlLintRules.missingNamespaces(parsed)) {
            out += finding(
                hit.range, Severity.ERROR, "Missing xmlns:${hit.prefix} namespace declaration",
                "android.missingNamespace", listOf(XmlQuickFixes.addNamespace(hit.prefix, hit.uri, hit.insertAt)),
            )
        }

        if (isLayout) {
            // B) Hardcoded user-facing text → extract to @string.
            for (h in XmlLintRules.hardcodedText(parsed)) {
                out += finding(
                    h.range, Severity.WARNING, "Hardcoded string should be a @string resource",
                    "android.hardcodedText", listOf(XmlQuickFixes.extractToString(host, h.range, h.value)),
                )
            }
            // C) A view element missing layout_width / layout_height.
            for (m in XmlLintRules.missingSize(parsed, host::isViewLike)) {
                out += finding(
                    m.range, Severity.WARNING, "<${m.tag}> is missing android:${m.dim}",
                    "android.missingSize", listOf(XmlQuickFixes.addSize(m.dim, m.insertAt)),
                )
            }
        }

        // D) Wrong attributes + wrong attribute values (the Android schema is consulted via the checker, which
        //    is conservative - it only judges what it positively knows, so custom/cold cases aren't flagged).
        for (p in XmlLintRules.attributeProblems(parsed, path, attributes)) when (p) {
            is AttributeProblem.Unknown -> out += finding(
                p.range, Severity.WARNING, "Attribute ${p.attribute} is not allowed on <${p.tag}>",
                "android.unknownAttribute", listOf(XmlQuickFixes.removeAttribute(p.attribute, p.removalRange)),
            )
            is AttributeProblem.InvalidValue -> out += finding(
                p.range, Severity.WARNING,
                "Invalid value \"${p.value}\" for ${p.attribute} (expected: ${expectedList(p.allowed)})",
                "android.invalidAttributeValue", emptyList(),
            )
        }

        // E) Unresolved local resource references (host resolves via index + repository fallback).
        for (ref in host.scanResourceReferences(text)) {
            val resName = XmlQuickFixes.sanitizeResName(ref.name)
            if (!host.typeHasAny(file, ref.rClass)) continue    // type only sourced from framework/unindexed → don't flag
            if (host.hasResource(file, ref.rClass, resName)) continue
            val range = TextRange(ref.start, ref.endExclusive)
            val fixes = when {
                host.isValueType(ref.rClass) -> listOf(XmlQuickFixes.createValueResource(host, ref.rClass, resName))
                host.isFileType(ref.rClass) -> listOf(XmlQuickFixes.createResourceFile(host, ref.rClass, resName))
                else -> emptyList()
            }
            out += finding(range, Severity.WARNING, "Cannot resolve @${ref.rClass}/${ref.name}", "android.unresolvedResource", fixes)
        }
        return out
    }

    private fun finding(range: TextRange, severity: Severity, message: String, code: String, fixes: List<QuickFix>): Diagnostic =
        Diagnostic(range, severity, message, DiagnosticSource.Analyzer(AnalyzerId("android.xml")), code, fixes)

    /** The allowed-values hint for an invalid-value message, capped so a big flag set doesn't flood the message. */
    private fun expectedList(allowed: Set<String>): String {
        val shown = allowed.take(8)
        return shown.joinToString(", ") + if (allowed.size > shown.size) ", …" else ""
    }
}
