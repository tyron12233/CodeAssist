package dev.ide.core.completion

import dev.ide.lang.completion.CaretAction
import dev.ide.lang.completion.CompletionItem
import dev.ide.lang.completion.CompletionItemKind
import dev.ide.lang.completion.CompletionParams
import dev.ide.lang.completion.CompletionResultSet
import dev.ide.lang.completion.CompletionContributor
import dev.ide.lang.completion.TextEdit
import dev.ide.lang.dom.TextRange
import dev.ide.lang.postfix.POSTFIX_TEMPLATE_EP
import dev.ide.lang.postfix.PostfixContext
import dev.ide.platform.ExtensionRegistry

/**
 * The generic driver for `platform.postfixTemplate` ([POSTFIX_TEMPLATE_EP]) — finally wiring the
 * previously-declared-but-dead extension point through the unified engine. At a `receiver.key` position it
 * reconstructs the receiver (a language-agnostic backward text scan, like the JDT impl), resolves the
 * receiver's type via [CompletionParams.typeResolver] (so a template can gate on Boolean/Iterable/etc.), and
 * surfaces each applicable [dev.ide.lang.postfix.PostfixTemplate] as a `kind = SNIPPET` item.
 *
 * Convention for the generic path (documented here since the EP shipped interfaces-only): a template's
 * [PostfixExpansion.snippet][dev.ide.lang.postfix.PostfixExpansion.snippet] IS the rewrite text (with tab
 * stops) and becomes the item's insert text driven by [CaretAction.ExpandSnippet]; the contributor itself
 * adds the edit that deletes the original `receiver.` span, and the template's
 * [PostfixExpansion.edits][dev.ide.lang.postfix.PostfixExpansion.edits] are any *extra* edits (imports, …).
 *
 * It is purely additive: the language backends keep their own built-in postfix, so this exists for plugins
 * (and future backends) to add postfix templates without forking a backend. Registered with no built-in
 * templates, so it is a no-op until something contributes to the EP.
 */
class PostfixContributor(private val extensions: ExtensionRegistry) : CompletionContributor {
    override val id = "platform.postfix"

    override suspend fun fillCompletionVariants(params: CompletionParams, result: CompletionResultSet) {
        val templates = extensions.extensions(POSTFIX_TEMPLATE_EP)
        if (templates.isEmpty()) return
        val parsed = params.parsedFile ?: return
        val text = params.document.text
        val keyStart = params.replacementRange.start

        val dot = dotBefore(text, keyStart) ?: return
        val recvStart = receiverStart(text, dot)
        if (recvStart >= dot) return
        val receiverText = text.subSequence(recvStart, dot).toString().trim()
        if (receiverText.isEmpty()) return

        val recvRange = TextRange(recvStart, dot)
        val recvNode = parsed.nodeAt((dot - 1).coerceIn(recvStart, text.length - 1))
        val type = params.typeResolver?.invoke(recvNode)
        val ctx = PostfixContext(recvNode, receiverText, recvRange, type, params.document)
        val deleteReceiver = TextEdit(TextRange(recvStart, keyStart), "")

        for (t in templates) {
            if (t.languages.isNotEmpty() && params.language !in t.languages) continue
            if (!t.key.startsWith(params.prefix, ignoreCase = true)) continue
            if (!runCatching { t.isApplicable(ctx) }.getOrDefault(false)) continue
            val exp = runCatching { t.expand(ctx) }.getOrNull() ?: continue
            val snippet = exp.snippet
            result.addElement(
                CompletionItem(
                    label = t.key,
                    insertText = snippet?.text ?: "",
                    kind = CompletionItemKind.SNIPPET,
                    detail = t.example,
                    documentation = t.description,
                    sortPriority = if (t.key == params.prefix) -40 else 65,
                    additionalEdits = listOf(deleteReceiver) + exp.edits,
                    caret = snippet?.let { CaretAction.ExpandSnippet(it) } ?: CaretAction.AtEnd,
                ),
            )
        }
    }

    /** Registered to run just before buffer-words but after the language backend (so backend members win). */
    companion object {
        const val ORDER = 5_000

        /** The `.` immediately before [keyStart] (skipping spaces/tabs), or null if not a member access. */
        private fun dotBefore(text: CharSequence, keyStart: Int): Int? {
            var i = keyStart - 1
            while (i >= 0 && (text[i] == ' ' || text[i] == '\t')) i--
            return if (i >= 0 && text[i] == '.') i else null
        }

        /**
         * Start of the receiver expression ending just before [dot] — a backward scan over a balanced suffix
         * (identifiers, dots, bracket groups, string/char literals), stopping at the first operator/separator
         * at depth 0. Mirrors lang-jdt's receiver scan, so it's language-agnostic.
         */
        private fun receiverStart(text: CharSequence, dot: Int): Int {
            var i = dot - 1
            while (i >= 0 && text[i].isWhitespace()) i--
            var depth = 0
            while (i >= 0) {
                val c = text[i]
                when {
                    c == ')' || c == ']' -> { depth++; i-- }
                    c == '(' || c == '[' -> { if (depth == 0) return i + 1; depth--; i-- }
                    depth > 0 -> i--
                    c == '"' || c == '\'' -> {
                        val q = c; i--
                        while (i >= 0 && !(text[i] == q && (i == 0 || text[i - 1] != '\\'))) i--
                        i--
                    }
                    c.isLetterOrDigit() || c == '_' || c == '$' || c == '.' -> i--
                    else -> return i + 1
                }
            }
            return i + 1
        }
    }
}
