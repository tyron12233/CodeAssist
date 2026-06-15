package dev.ide.ui.editor.blocks

import dev.ide.ui.backend.UiCompletionItem
import dev.ide.ui.backend.UiCompletionKind

/**
 * Type-aware re-ranking for block-socket completion: items whose declared/return type (the short
 * readable [UiCompletionItem.detail] lang-jdt emits, e.g. "boolean", "Stream<Integer>") matches the
 * socket's expected [valueKind] float to the top; everything else keeps the backend's order (the sort
 * is stable). "object"/"unknown"/null sockets expect anything — the list is returned untouched.
 * Pure (no Compose, no I/O) so it is unit-testable on the JVM.
 */
internal fun rankForSocket(items: List<UiCompletionItem>, valueKind: String?): List<UiCompletionItem> {
    if (valueKind == null || valueKind == "unknown" || valueKind == "object") return items
    return items.sortedBy { matchPenalty(it, valueKind) } // sortedBy is stable ⇒ ties keep original order
}

private val BOOLEAN_TYPES = setOf("boolean", "Boolean")
private val NUMBER_TYPES = setOf(
    "int", "long", "short", "byte", "float", "double",
    "Integer", "Long", "Short", "Byte", "Float", "Double",
)
// char/Character count as STRING in the shape language — single characters read as text.
private val STRING_TYPES = setOf("String", "CharSequence", "char", "Character")
private val TYPE_KINDS = setOf(
    UiCompletionKind.Class, UiCompletionKind.Interface, UiCompletionKind.Enum,
    UiCompletionKind.Record, UiCompletionKind.AnnotationType, UiCompletionKind.TypeParameter,
)

/** 0 when [item]'s type/kind fits a socket expecting [valueKind], else 1. */
private fun matchPenalty(item: UiCompletionItem, valueKind: String): Int {
    val fits = when (valueKind) {
        "boolean" -> detailType(item) in BOOLEAN_TYPES
        "number" -> detailType(item) in NUMBER_TYPES
        "string" -> detailType(item) in STRING_TYPES
        "type" -> item.kind in TYPE_KINDS
        else -> true
    }
    return if (fits) 0 else 1
}

/** [UiCompletionItem.detail], trimmed with generics stripped — "Stream<Integer>" → "Stream". */
private fun detailType(item: UiCompletionItem): String? {
    val d = item.detail?.trim() ?: return null
    val generics = d.indexOf('<')
    return (if (generics >= 0) d.substring(0, generics) else d).trim()
}
