package dev.codeassist.csharp.completion

import dev.codeassist.csharp.project.csproj.CsprojCompleter
import dev.ide.lang.LanguageId
import dev.ide.lang.completion.CaretAction
import dev.ide.lang.completion.CompletionContribution
import dev.ide.lang.completion.CompletionContributor
import dev.ide.lang.completion.CompletionItem
import dev.ide.lang.completion.CompletionItemKind
import dev.ide.lang.completion.CompletionParams
import dev.ide.lang.completion.CompletionResultSet

/**
 * Provides completion in .csproj, .props, and .targets files using [CsprojCompleter].
 *
 * Device behavior still needs verification: whether the host invokes contributors without a language
 * backend, and what [CompletionParams.replacementRange] covers around `<`, `"` and `.`.
 */
internal class CsprojCompletionContributor : CompletionContributor {

    override val id = "csharp.csproj"

    override suspend fun fillCompletionVariants(
        params: CompletionParams,
        result: CompletionResultSet,
    ) {
        val text = params.document.text.toString()
        val caret = params.replacementRange.end.coerceIn(0, text.length)

        // Use only project-file suggestions; buffer words are not useful in XML.
        result.stopHere()

        for (suggestion in CsprojCompleter.suggest(text, caret)) {
            val kind = when (suggestion.kind) {
                CsprojCompleter.Kind.TAG -> CompletionItemKind.CLASS
                CsprojCompleter.Kind.ATTRIBUTE -> CompletionItemKind.FIELD
                CsprojCompleter.Kind.VALUE -> CompletionItemKind.VARIABLE
            }

            result.addElement(
                CompletionItem(
                    label = suggestion.label,
                    insertText = suggestion.insert,
                    kind = kind,
                    detail = suggestion.detail,
                    caret = suggestion.caret?.let { CaretAction.At(it) } ?: CaretAction.AtEnd,
                ),
            )
        }
    }
}

/** Registers the contributor for the csproj language. */
internal fun csprojCompletionContribution(): CompletionContribution =
    CompletionContribution(
        contributor = CsprojCompletionContributor(),
        languages = setOf(LanguageId("csproj")),
    )
