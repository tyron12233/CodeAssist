package dev.ide.testkit

import dev.ide.lang.completion.CompletionResult

/**
 * The bare identifier of each completion item: its `insertText` up to the first `(`, so a method whose
 * insert text carries `()` still compares as the plain name these member-presence / ranking tests care
 * about. This is the label-extraction shape previously inlined in `completeLabels` / `completeAtCaret`.
 */
fun CompletionResult.labels(): List<String> = items.map { it.insertText.substringBefore('(') }

/** Each item's shown label, verbatim. */
fun CompletionResult.displayLabels(): List<String> = items.map { it.label }
