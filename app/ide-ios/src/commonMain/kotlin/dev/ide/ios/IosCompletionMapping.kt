package dev.ide.ios

import dev.ide.lang.completion.CaretAction
import dev.ide.lang.completion.CompletionItemKind
import dev.ide.lang.completion.CompletionResult
import dev.ide.ui.backend.UiCaret
import dev.ide.ui.backend.UiCompletionItem
import dev.ide.ui.backend.UiCompletionKind
import dev.ide.ui.backend.UiCompletionResult
import dev.ide.ui.backend.UiSnippet
import dev.ide.ui.backend.UiSnippetStop
import dev.ide.ui.backend.UiTextEdit
import dev.ide.ui.backend.UiTextRange

/**
 * The engine's [CompletionResult] as the UI contract spells it.
 *
 * A second copy of `:ide-core`'s `CompletionMapping`, and the duplication is deliberate for now rather than
 * accidental: that one lives in the JVM host, this module cannot see it, and the shared home for it would be
 * a bridge module between `:language-api` and `:ide-ui-api` that does not exist yet. The mapping is total
 * over both enums, so the compiler catches a new kind on both sides — which is the property worth having
 * until there is one copy.
 */
internal fun CompletionResult.toUi(): UiCompletionResult = UiCompletionResult(
    items = items.map { item ->
        UiCompletionItem(
            label = item.label,
            insertText = item.insertText,
            detail = item.detail,
            container = item.container,
            documentation = item.documentation,
            kind = mapKind(item.kind),
            sortPriority = item.sortPriority,
            additionalEdits = item.additionalEdits.map { UiTextEdit(it.range.start, it.range.end, it.newText) },
            caret = mapCaret(item.caret),
            snippet = mapSnippet(item.caret),
        )
    },
    replaceStart = replacementRange.start,
    replaceEnd = replacementRange.end,
    isIncomplete = isIncomplete,
)

private fun mapCaret(action: CaretAction): UiCaret? = when (action) {
    CaretAction.AtEnd -> null
    is CaretAction.At -> UiCaret(action.offset)
    is CaretAction.Select -> UiCaret(action.offset, action.length)
    is CaretAction.ExpandSnippet -> {
        val first = action.expansion.stops.filter { it.index != 0 }.minByOrNull { it.index }?.ranges?.firstOrNull()
        if (first != null) UiCaret(first.start, first.end - first.start)
        else UiCaret(action.expansion.finalCaretOffset)
    }
}

private fun mapSnippet(action: CaretAction): UiSnippet? {
    val expansion = (action as? CaretAction.ExpandSnippet)?.expansion ?: return null
    return UiSnippet(
        stops = expansion.stops.map { s -> UiSnippetStop(s.index, s.ranges.map { UiTextRange(it.start, it.end) }, s.choices) },
        finalCaretOffset = expansion.finalCaretOffset,
    )
}

private fun mapKind(kind: CompletionItemKind): UiCompletionKind = when (kind) {
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
