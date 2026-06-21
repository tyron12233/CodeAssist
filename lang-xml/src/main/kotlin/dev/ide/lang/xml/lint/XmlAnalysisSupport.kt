package dev.ide.lang.xml.lint

import dev.ide.analysis.AnalysisTarget
import dev.ide.analysis.AnalyzerId
import dev.ide.analysis.CodeActionKind
import dev.ide.analysis.DIAGNOSTIC_PROVIDER_EP
import dev.ide.analysis.Diagnostic
import dev.ide.analysis.DiagnosticProvider
import dev.ide.analysis.DiagnosticSource
import dev.ide.analysis.FixContext
import dev.ide.analysis.QuickFix
import dev.ide.analysis.WorkspaceEdit
import dev.ide.lang.dom.Severity
import dev.ide.lang.dom.TextRange
import dev.ide.lang.incremental.DocumentEdit
import dev.ide.lang.xml.XmlLanguageBackend
import dev.ide.platform.ExtensionRegistry
import dev.ide.platform.PluginId
import dev.ide.vfs.VirtualFile

/**
 * A local `@type/name` resource reference the host considers worth resolving (already filtered to local,
 * non-`@+id`-create, non-`?theme` references with a real resource type and name). [rClass] is the R class
 * id (`"string"`, `"drawable"`, …); [name] is the original reference name (for the message); the range is
 * `[start, endExclusive)` over the buffer.
 */
data class XmlResourceRef(val rClass: String, val name: String, val start: Int, val endExclusive: Int)

/**
 * The host data the XML lint provider needs but that lives outside lang-xml (the Android resource index /
 * repository and the project's `res/` filesystem). ide-core implements it per project. Keeps lang-xml a
 * generic XML backend — it knows the lint *rules*, the host knows the *resources*.
 */
interface XmlResourceHost {
    /** A tag that should carry layout params: a known framework widget or a custom view. */
    fun isViewLike(tag: String): Boolean

    /** Local `@type/name` references in [text] worth resolving (pre-filtered as described on [XmlResourceRef]). */
    fun scanResourceReferences(text: String): List<XmlResourceRef>

    /** Does [file]'s module define ANY resource of [rClass]? When false the type is framework-only/unindexed
     *  and an unresolved reference to it is NOT flagged (avoids false positives while the index is cold). */
    fun typeHasAny(file: VirtualFile, rClass: String): Boolean

    /** Is `@rClass/name` defined for [file]'s module (resource index, falling back to the repository while
     *  the index builds)? */
    fun hasResource(file: VirtualFile, rClass: String, name: String): Boolean

    /** Can [rClass] be created as a value resource (string/color/dimen/bool/integer/id)? */
    fun isValueType(rClass: String): Boolean

    /** Create/append `<rClass name=…>value</rClass>` to the module's `res/values/…` (host filesystem I/O),
     *  de-duplicating the name; returns the (possibly suffixed) name actually written. */
    fun appendValueResource(file: VirtualFile, rClass: String, name: String, value: String): String
}

/**
 * The XML editor diagnostics, unified onto the analysis pipeline: well-formedness (the tolerant parser's
 * own diagnostics) plus the Android lint rules ([XmlLintRules]) and the unresolved-resource check, with
 * quick-fixes attached. Resource lookups and the resource-creating fixes' filesystem I/O go through the
 * injected [XmlResourceHost]; everything else is pure over the neutral DOM. Declares `languages = {xml}`.
 */
class XmlDiagnosticProvider(
    private val host: XmlResourceHost,
    override val id: String = "xml",
) : DiagnosticProvider {
    override val languages = setOf(XmlLanguageBackend.LANGUAGE_ID)

    override suspend fun diagnose(target: AnalysisTarget): List<Diagnostic> {
        val parsed = target.parsed
        val file = target.file
        val text = parsed.text().toString()
        val isLayout = file.path.replace('\\', '/').contains("/res/layout")
        val out = ArrayList<Diagnostic>()

        // Well-formedness (the tolerant parser's own diagnostics).
        for (d in parsed.diagnostics) {
            out += Diagnostic(d.range, d.severity, d.message, DiagnosticSource.Analyzer(AnalyzerId("xml.syntax")), d.code)
        }

        // A) Missing xmlns:android when android: attributes are used.
        XmlLintRules.missingNamespace(parsed)?.let { hit ->
            val fix = bufferFix("Add xmlns:android declaration") {
                DocumentEdit(hit.insertAt, 0, " xmlns:android=\"${hit.uri}\"")
            }
            out += finding(hit.range, Severity.ERROR, "Missing xmlns:android namespace declaration", "android.missingNamespace", listOf(fix))
        }

        if (isLayout) {
            // B) Hardcoded user-facing text → extract to @string.
            for (h in XmlLintRules.hardcodedText(parsed)) {
                val resName = snakeName(h.value)
                val fix = object : QuickFix {
                    override val title = "Extract to @string resource"
                    override val kind = CodeActionKind.QUICK_FIX
                    override suspend fun computeEdits(ctx: FixContext): WorkspaceEdit {
                        val n = host.appendValueResource(ctx.target.file, "string", resName, h.value)
                        return WorkspaceEdit.of(ctx.target.file, DocumentEdit(h.range.start, h.range.length, "@string/$n"))
                    }
                }
                out += finding(h.range, Severity.WARNING, "Hardcoded string should be a @string resource", "android.hardcodedText", listOf(fix))
            }
            // C) A view element missing layout_width / layout_height.
            for (m in XmlLintRules.missingSize(parsed, host::isViewLike)) {
                val fix = bufferFix("Add android:${m.dim}=\"wrap_content\"") {
                    DocumentEdit(m.insertAt, 0, "\n    android:${m.dim}=\"wrap_content\"")
                }
                out += finding(m.range, Severity.WARNING, "<${m.tag}> is missing android:${m.dim}", "android.missingSize", listOf(fix))
            }
        }

        // D) Unresolved local resource references (host resolves via index + repository fallback).
        for (ref in host.scanResourceReferences(text)) {
            val resName = sanitizeResName(ref.name)
            if (!host.typeHasAny(file, ref.rClass)) continue    // type only sourced from framework/unindexed → don't flag
            if (host.hasResource(file, ref.rClass, resName)) continue
            val range = TextRange(ref.start, ref.endExclusive)
            val fixes = if (host.isValueType(ref.rClass)) listOf(object : QuickFix {
                override val title = "Create @${ref.rClass}/$resName"
                override val kind = CodeActionKind.QUICK_FIX
                override suspend fun computeEdits(ctx: FixContext): WorkspaceEdit {
                    host.appendValueResource(ctx.target.file, ref.rClass, resName, "")
                    return WorkspaceEdit.EMPTY
                }
            }) else emptyList()
            out += finding(range, Severity.WARNING, "Cannot resolve @${ref.rClass}/${ref.name}", "android.unresolvedResource", fixes)
        }
        return out
    }

    private fun finding(range: TextRange, severity: Severity, message: String, code: String, fixes: List<QuickFix>): Diagnostic =
        Diagnostic(range, severity, message, DiagnosticSource.Analyzer(AnalyzerId("android.xml")), code, fixes)

    /** A fix whose edit is a single in-buffer [DocumentEdit] (no host I/O). */
    private fun bufferFix(title: String, edit: () -> DocumentEdit): QuickFix = object : QuickFix {
        override val title = title
        override val kind = CodeActionKind.QUICK_FIX
        override suspend fun computeEdits(ctx: FixContext): WorkspaceEdit = WorkspaceEdit.of(ctx.target.file, edit())
    }

    private companion object {
        /** A deterministic snake_case resource name from arbitrary [value] (for extract-to-@string). */
        fun snakeName(value: String): String {
            val base = value.trim().lowercase().map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")
                .trim('_').replace(Regex("_+"), "_").take(40).ifEmpty { "text" }
            return if (base.first().isDigit()) "_$base" else base
        }

        fun sanitizeResName(s: String): String = s.replace('.', '_').replace('-', '_').trim()
    }
}

object XmlAnalysisSupport {
    val PLUGIN = PluginId("xml-analysis")

    fun register(extensions: ExtensionRegistry, host: XmlResourceHost, plugin: PluginId = PLUGIN) {
        extensions.register(DIAGNOSTIC_PROVIDER_EP, XmlDiagnosticProvider(host), plugin)
    }
}
