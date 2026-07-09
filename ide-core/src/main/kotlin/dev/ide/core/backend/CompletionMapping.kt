package dev.ide.core.backend

import dev.ide.analysis.Diagnostic
import dev.ide.analysis.DiagnosticTag
import dev.ide.lang.completion.CaretAction
import dev.ide.lang.completion.CompletionItemKind
import dev.ide.lang.completion.CompletionResult
import dev.ide.lang.dom.Severity
import dev.ide.ui.backend.UiCaret
import dev.ide.ui.backend.UiDiagnostic
import dev.ide.ui.backend.UiSeverity
import dev.ide.ui.backend.UiCompletionItem
import dev.ide.ui.backend.UiCompletionKind
import dev.ide.ui.backend.UiCompletionResult
import dev.ide.ui.backend.UiSnippet
import dev.ide.ui.backend.UiSnippetStop
import dev.ide.ui.backend.UiTextEdit
import dev.ide.ui.backend.UiTextRange

/**
 * Map an engine [CompletionResult] to the surface-neutral [UiCompletionResult] the UI consumes. Shared by the
 * editor's completion and the Learn exercise editor (which completes against a hidden scratch engine).
 */
internal fun CompletionResult.toUi(): UiCompletionResult = UiCompletionResult(
    items = items.map { item ->
        UiCompletionItem(
            label = item.label,
            insertText = item.insertText,
            detail = item.detail,
            container = item.container,
            documentation = item.documentation,
            kind = mapCompletionKind(item.kind),
            sortPriority = item.sortPriority,
            additionalEdits = item.additionalEdits.map { UiTextEdit(it.range.start, it.range.end, it.newText) },
            caret = mapCompletionCaret(item.caret),
            snippet = mapCompletionSnippet(item.caret),
        )
    },
    replaceStart = replacementRange.start,
    replaceEnd = replacementRange.end,
    isIncomplete = isIncomplete,
)

private fun mapCompletionCaret(action: CaretAction): UiCaret? = when (action) {
    CaretAction.AtEnd -> null
    is CaretAction.At -> UiCaret(action.offset)
    is CaretAction.Select -> UiCaret(action.offset, action.length)
    is CaretAction.ExpandSnippet -> {
        val first = action.expansion.stops.filter { it.index != 0 }.minByOrNull { it.index }?.ranges?.firstOrNull()
        if (first != null) UiCaret(first.start, first.end - first.start)
        else UiCaret(action.expansion.finalCaretOffset)
    }
}

private fun mapCompletionSnippet(action: CaretAction): UiSnippet? {
    val exp = (action as? CaretAction.ExpandSnippet)?.expansion ?: return null
    return UiSnippet(
        stops = exp.stops.map { s -> UiSnippetStop(s.index, s.ranges.map { UiTextRange(it.start, it.end) }, s.choices) },
        finalCaretOffset = exp.finalCaretOffset,
    )
}

/** Map engine [Diagnostic]s to the UI DTO (with line/col computed from [text]). Shared by the editor and the
 *  Learn exercise editor (which analyzes against a hidden scratch engine). */
internal fun List<Diagnostic>.toUiDiagnostics(text: String): List<UiDiagnostic> = map { d ->
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

private fun lineColOf(text: String, offset: Int): Pair<Int, Int> {
    val end = offset.coerceIn(0, text.length)
    var line = 0
    var col = 0
    var i = 0
    while (i < end) {
        if (text[i] == '\n') { line++; col = 0 } else col++
        i++
    }
    return line to col
}

private fun mapCompletionKind(k: CompletionItemKind): UiCompletionKind = when (k) {
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
