package dev.ide.core.backend

import dev.ide.analysis.CodeActionKind
import dev.ide.analysis.DiagnosticTag
import dev.ide.core.BackendContext
import dev.ide.lang.completion.CaretAction
import dev.ide.lang.completion.CompletionItemKind
import dev.ide.lang.dom.Severity
import dev.ide.lang.highlight.HighlightModifier
import dev.ide.lang.hints.InlayHintKind
import dev.ide.platform.EngineCanceledException
import dev.ide.ui.backend.AnalysisPreempted
import dev.ide.ui.backend.EditorService
import dev.ide.ui.backend.UiAction
import dev.ide.ui.backend.UiActionKind
import dev.ide.ui.backend.UiCaret
import dev.ide.ui.backend.UiCompletionItem
import dev.ide.ui.backend.UiCompletionKind
import dev.ide.ui.backend.UiCompletionResult
import dev.ide.ui.backend.UiDefinition
import dev.ide.ui.backend.UiDiagnostic
import dev.ide.ui.backend.UiFoldRegion
import dev.ide.ui.backend.UiHighlightModifier
import dev.ide.ui.backend.UiInlayHint
import dev.ide.ui.backend.UiInlayKind
import dev.ide.ui.backend.UiInlayPart
import dev.ide.ui.backend.UiRenameResult
import dev.ide.ui.backend.UiRenameTarget
import dev.ide.ui.backend.UiSemanticToken
import dev.ide.ui.backend.UiSeverity
import dev.ide.ui.backend.UiSignature
import dev.ide.ui.backend.UiSignatureHelp
import dev.ide.ui.backend.UiSignatureParam
import dev.ide.ui.backend.UiSnippet
import dev.ide.ui.backend.UiSnippetStop
import dev.ide.ui.backend.UiTextEdit
import dev.ide.ui.backend.UiTextRange
import java.nio.file.Paths
import kotlinx.coroutines.withContext

/**
 * [EditorService]: the editor-time language features for the active buffer. Runs on the shared serialized
 * engine dispatcher via the priority lanes ([BackendContext.interactive] for completion,
 * [BackendContext.background] for analysis/hints/etc.); a preemption surfaces as [AnalysisPreempted] for the
 * host to retry. Maps the framework results onto the neutral UI DTOs.
 */
internal class EditorBackend(private val ctx: BackendContext) : EditorService {

    override suspend fun breadcrumbAt(path: String, text: String, offset: Int): List<String> =
        try { ctx.background { ctx.services.breadcrumbAt(Paths.get(path), text, offset) } }
        catch (e: EngineCanceledException) { emptyList() } // re-runs on the next caret move

    override suspend fun definitionAt(path: String, text: String, offset: Int): UiDefinition? =
        withContext(ctx.engineDispatcher) { ctx.services.definitionAt(Paths.get(path), text, offset) }
            ?.let { (p, o) -> UiDefinition(p.toString(), o) }

    override suspend fun prepareRename(path: String, text: String, offset: Int): UiRenameTarget? =
        withContext(ctx.engineDispatcher) { ctx.services.prepareRename(Paths.get(path), text, offset)?.let { UiRenameTarget(it.oldName, it.kind) } }

    override suspend fun rename(path: String, text: String, offset: Int, newName: String): UiRenameResult =
        withContext(ctx.engineDispatcher) {
            val r = ctx.services.rename(Paths.get(path), text, offset, newName)
            if (r.success) ctx.bumpFileSystemEpoch() // the multi-file edit / file rename changed the tree + other buffers
            UiRenameResult(r.success, r.message, r.occurrences, r.filesChanged, r.newPath)
        }

    override fun updateDocument(path: String, text: String) =
        ctx.services.updateDocument(Paths.get(path), text)

    override fun saveFile(path: String, text: String) =
        ctx.services.save(Paths.get(path), text)

    override suspend fun complete(path: String, text: String, offset: Int): UiCompletionResult {
        val t0 = System.nanoTime()
        val result = ctx.interactive { ctx.services.complete(Paths.get(path), text, offset) }
        ctx.recordPerf(dev.ide.analytics.Events.COMPLETION_PERF, (System.nanoTime() - t0) / 1_000_000)
        return UiCompletionResult(
            items = result.items.map { item ->
                UiCompletionItem(
                    label = item.label, insertText = item.insertText, detail = item.detail,
                    container = item.container,
                    documentation = item.documentation, kind = mapKind(item.kind), sortPriority = item.sortPriority,
                    additionalEdits = item.additionalEdits.map { UiTextEdit(it.range.start, it.range.end, it.newText) },
                    caret = mapCaret(item.caret),
                    snippet = mapSnippet(item.caret),
                )
            },
            replaceStart = result.replacementRange.start,
            replaceEnd = result.replacementRange.end,
        )
    }

    override suspend fun analyze(path: String, text: String): List<UiDiagnostic> {
        // Routes through the full analysis engine: JDT compiler errors + the Java analyzers, merged,
        // suppression-filtered, and profile-adjusted into one set. Runs in the preemptible lane so a
        // completion request can cut ahead; a preempted pass surfaces as AnalysisPreempted for the host to retry.
        val t0 = System.nanoTime()
        val diagnostics = try {
            ctx.background { ctx.services.analyzeDiagnostics(Paths.get(path), text) }
        } catch (e: EngineCanceledException) {
            throw AnalysisPreempted() // preempted: don't record a (misleadingly short) latency sample
        }
        ctx.recordPerf(dev.ide.analytics.Events.ANALYSIS_PERF, (System.nanoTime() - t0) / 1_000_000)
        return diagnostics.map { d ->
            val (line, col) = lineColOf(text, d.range.start)
            UiDiagnostic(
                severity = when (d.severity) {
                    Severity.ERROR -> UiSeverity.Error
                    Severity.WARNING -> UiSeverity.Warning
                    Severity.INFO -> UiSeverity.Info
                    Severity.HINT -> UiSeverity.Hint
                },
                line = line,
                col = col,
                message = d.message,
                startOffset = d.range.start,
                endOffset = d.range.end,
                unused = DiagnosticTag.UNUSED in d.tags,
            )
        }
    }

    override suspend fun hintsAt(path: String, text: String, startOffset: Int, endOffset: Int): List<UiInlayHint> {
        val hints = try {
            ctx.background { ctx.services.inlayHints(Paths.get(path), text, startOffset, endOffset) }
        } catch (e: EngineCanceledException) {
            // Preempted by a higher-priority call (e.g. completion) on the shared engine thread. Surface it so
            // the host retries once the buffer settles — returning empty here would CLEAR the hints and they'd
            // only reappear on the next edit (the "type a space to get them back" bug).
            throw AnalysisPreempted()
        }
        return hints.map { h ->
            UiInlayHint(
                offset = h.offset,
                parts = h.parts.map { UiInlayPart(it.text) },
                kind = when (h.kind) {
                    InlayHintKind.TYPE -> UiInlayKind.Type
                    InlayHintKind.PARAMETER -> UiInlayKind.Parameter
                    InlayHintKind.CHAINING -> UiInlayKind.Chaining
                    InlayHintKind.OTHER -> UiInlayKind.Other
                },
                tooltip = h.tooltip,
                paddingLeft = h.paddingLeft,
                paddingRight = h.paddingRight,
            )
        }
    }

    override suspend fun signatureHelp(path: String, text: String, offset: Int): UiSignatureHelp? {
        // Background lane: completion (interactive) preempts it. A preemption just means "no panel this round";
        // the editor re-queries on the next caret move / edit, so swallowing it to null is correct (no retry needed).
        val help = try {
            ctx.background { ctx.services.signatureHelp(Paths.get(path), text, offset) }
        } catch (e: EngineCanceledException) {
            return null
        } ?: return null
        return UiSignatureHelp(
            signatures = help.signatures.map { s ->
                UiSignature(
                    label = s.label,
                    parameters = s.parameters.map { p -> UiSignatureParam(p.label, p.labelStart, p.labelEnd) },
                    documentation = s.documentation,
                    activeParameter = s.activeParameter,
                )
            },
            activeSignature = help.activeSignature,
            activeParameter = help.activeParameter,
        )
    }

    override suspend fun semanticTokens(path: String, text: String): List<UiSemanticToken> {
        val tokens = try {
            ctx.background { ctx.services.semanticTokens(Paths.get(path), text) }
        } catch (e: EngineCanceledException) {
            // Preempted by completion on the shared engine thread — surface it so the host retries and keeps
            // the current coloring meanwhile (returning empty would clear it until the next edit).
            throw AnalysisPreempted()
        }
        return tokens.map { t ->
            UiSemanticToken(
                startOffset = t.range.start,
                endOffset = t.range.end,
                kind = t.kind.id,
                modifiers = t.modifiers.mapTo(LinkedHashSet()) { mapHighlightModifier(it) },
            )
        }
    }

    override suspend fun codeFolds(path: String, text: String): List<UiFoldRegion> {
        val folds = try {
            ctx.background { ctx.services.codeFolds(Paths.get(path), text) }
        } catch (e: EngineCanceledException) {
            throw AnalysisPreempted() // preempted by completion — host retries, keeps current folds meanwhile
        }
        return folds.map { f ->
            UiFoldRegion(
                startOffset = f.range.start,
                endOffset = f.range.end,
                placeholder = f.placeholder,
                kind = f.kind.id,
                collapsedByDefault = f.collapsedByDefault,
            )
        }
    }

    // ---- code actions (quick-fixes + intentions) ----

    override suspend fun actionsAt(path: String, text: String, selStart: Int, selEnd: Int): List<UiAction> =
        withContext(ctx.engineDispatcher) { ctx.services.editorActions(Paths.get(path), text, selStart, selEnd) }
            .mapIndexed { i, fix -> UiAction(i, fix.title, mapActionKind(fix.kind)) }

    override suspend fun applyAction(path: String, text: String, selStart: Int, selEnd: Int, actionId: Int): List<UiTextEdit> =
        withContext(ctx.engineDispatcher) { ctx.services.applyEditorAction(Paths.get(path), text, selStart, selEnd, actionId) }
            .map { UiTextEdit(it.offset, it.offset + it.oldLength, it.newText.toString()) }

    // ---- mappers (editor-local) ----

    private fun mapHighlightModifier(m: HighlightModifier): UiHighlightModifier = when (m) {
        HighlightModifier.DECLARATION -> UiHighlightModifier.Declaration
        HighlightModifier.STATIC -> UiHighlightModifier.Static
        HighlightModifier.ABSTRACT -> UiHighlightModifier.Abstract
        HighlightModifier.DEPRECATED -> UiHighlightModifier.Deprecated
        HighlightModifier.READONLY -> UiHighlightModifier.Readonly
        HighlightModifier.MUTABLE -> UiHighlightModifier.Mutable
        HighlightModifier.EXTENSION -> UiHighlightModifier.Extension
        HighlightModifier.COMPOSABLE -> UiHighlightModifier.Composable
        HighlightModifier.SUSPEND -> UiHighlightModifier.Suspend
    }

    private fun mapActionKind(k: CodeActionKind): UiActionKind = when (k) {
        CodeActionKind.QUICK_FIX -> UiActionKind.QUICK_FIX
        CodeActionKind.INTENTION -> UiActionKind.INTENTION
        CodeActionKind.REFACTOR -> UiActionKind.REFACTOR
    }

    private fun lineColOf(text: String, offset: Int): Pair<Int, Int> {
        var line = 1
        var lineStart = 0
        val end = offset.coerceIn(0, text.length)
        for (i in 0 until end) {
            if (text[i] == '\n') { line++; lineStart = i + 1 }
        }
        return line to (end - lineStart + 1)
    }

    /** Flatten the framework's [CaretAction] to the UI's neutral [UiCaret] (null = caret at end of insert). */
    private fun mapCaret(action: CaretAction): UiCaret? = when (action) {
        CaretAction.AtEnd -> null
        is CaretAction.At -> UiCaret(action.offset)
        is CaretAction.Select -> UiCaret(action.offset, action.length)
        // Full snippet stepping (linked cursors, choice popups) is a follow-up in the editor; until then
        // degrade to selecting the first tab stop (or the final caret) so a snippet item is still usable.
        is CaretAction.ExpandSnippet -> {
            val first = action.expansion.stops
                .filter { it.index != 0 }
                .minByOrNull { it.index }
                ?.ranges?.firstOrNull()
            if (first != null) UiCaret(first.start, first.end - first.start)
            else UiCaret(action.expansion.finalCaretOffset)
        }
    }

    /** The full snippet payload (tab stops) for an [CaretAction.ExpandSnippet] item; null otherwise. */
    private fun mapSnippet(action: CaretAction): UiSnippet? {
        val exp = (action as? CaretAction.ExpandSnippet)?.expansion ?: return null
        return UiSnippet(
            stops = exp.stops.map { s -> UiSnippetStop(s.index, s.ranges.map { UiTextRange(it.start, it.end) }, s.choices) },
            finalCaretOffset = exp.finalCaretOffset,
        )
    }

    private fun mapKind(k: CompletionItemKind): UiCompletionKind = when (k) {
        CompletionItemKind.CLASS -> UiCompletionKind.Class
        CompletionItemKind.INTERFACE -> UiCompletionKind.Interface
        CompletionItemKind.ENUM -> UiCompletionKind.Enum
        CompletionItemKind.ANNOTATION_TYPE -> UiCompletionKind.AnnotationType
        CompletionItemKind.RECORD -> UiCompletionKind.Record
        CompletionItemKind.METHOD -> UiCompletionKind.Method
        CompletionItemKind.CONSTRUCTOR -> UiCompletionKind.Constructor
        CompletionItemKind.FIELD -> UiCompletionKind.Field
        CompletionItemKind.ENUM_CONSTANT -> UiCompletionKind.EnumConstant
        CompletionItemKind.VARIABLE -> UiCompletionKind.Variable
        CompletionItemKind.PARAMETER -> UiCompletionKind.Parameter
        CompletionItemKind.TYPE_PARAMETER -> UiCompletionKind.TypeParameter
        CompletionItemKind.PACKAGE -> UiCompletionKind.Package
        CompletionItemKind.KEYWORD -> UiCompletionKind.Keyword
        CompletionItemKind.SNIPPET -> UiCompletionKind.Snippet
        CompletionItemKind.WORD -> UiCompletionKind.Word
    }
}
