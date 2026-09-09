package dev.ide.core.completion

import dev.ide.core.customize.DefaultCustomizations
import dev.ide.core.customize.MacroDef
import dev.ide.lang.completion.CaretAction
import dev.ide.lang.completion.CompletionContributor
import dev.ide.lang.completion.CompletionItem
import dev.ide.lang.completion.CompletionItemKind
import dev.ide.lang.completion.CompletionParams
import dev.ide.lang.completion.CompletionResultSet
import dev.ide.lang.completion.TextEdit
import dev.ide.lang.dom.TextRange
import dev.ide.lang.resolve.TypeRef
import dev.ide.lang.template.DefaultSnippetEngine
import dev.ide.lang.template.SnippetContext
import dev.ide.lang.template.SnippetTemplate

/**
 * Wires user live-template **macros** into completion — the host-level contributor, so no language backend is
 * forked. It runs after the backends (order [ORDER]) and does three things for the file's language:
 *
 *  1. **Adds** net-new user macros (an abbreviation the backends don't ship) as `SNIPPET` items — context-blind,
 *     like buffer words.
 *  2. **Disables** a shipped built-in the user turned off — [CompletionResultSet.removeIf] drops the backend's
 *     item (matched by `kind == SNIPPET && label == abbreviation`).
 *  3. **Overrides** a shipped built-in the user edited — [CompletionResultSet.replaceAll] rewrites the backend's
 *     item to expand the user's template instead.
 *  4. **Type-scoped** macros ([MacroDef.receiverType]) are offered only at a `receiver.abbrev` position where
 *     the receiver resolves to (a subtype of) that type — instance — or is a static reference to it (read via
 *     [CompletionParams.typeResolver], the seam postfix templates use). An **instance** macro is postfix: the
 *     `receiver.` is consumed and the template (using `$EXPR$` = the receiver) replaces the whole `expr.abbrev`.
 *     A **static** macro keeps the `Type.` prefix and the template is just the member that follows it.
 *
 * Cases 2–3 only touch an item the backend actually emitted, so the built-ins keep their context-awareness (a
 * disabled/edited `sout` still only appears where the JDT backend would have offered it). With no user overrides
 * the pass is a no-op, so completion is byte-identical to before this existed.
 */
class UserLiveTemplateContributor(
    private val userMacros: (languageId: String) -> List<MacroDef>,
) : CompletionContributor {
    override val id = "platform.userTemplates"

    private val engine = DefaultSnippetEngine()
    private val resolver = MacroVariableResolver()

    override suspend fun fillCompletionVariants(params: CompletionParams, result: CompletionResultSet) {
        val user = userMacros(params.language.id)
        if (user.isEmpty()) return // nothing customized → don't touch the backend's items at all

        val prefix = params.prefix
        val indent = leadingIndent(params.document.text, params.replacementRange.start)
        fun expand(m: MacroDef) = runCatching {
            engine.expand(
                SnippetTemplate(m.template),
                SnippetContext(params.document, offset = params.replacementRange.start, indent = indent),
                resolver,
            )
        }.getOrNull()

        // Which abbreviations are shipped built-ins for this language (so an override edits/removes the backend
        // item rather than adding a duplicate).
        val builtinAbbrevs = DefaultCustomizations.MACROS
            .asSequence()
            .filter { it.languages.isEmpty() || params.language.id in it.languages }
            .map { it.abbreviation }
            .toHashSet()

        // Type-scoped macros need the receiver before the abbreviation; resolve it once (only when there's a
        // member-access dot before the caret, so plain statement positions skip all of this).
        val text = params.document.text
        val keyStart = params.replacementRange.start
        val dot = dotBefore(text, keyStart)
        val receiverText: String
        val receiverType: TypeRef?
        val recvStart: Int
        if (dot != null) {
            recvStart = receiverStart(text, dot)
            receiverText = text.subSequence(recvStart.coerceAtMost(dot), dot).toString().trim()
            val node = params.parsedFile?.nodeAt((dot - 1).coerceIn(0, (text.length - 1).coerceAtLeast(0)))
            receiverType = node?.let { params.typeResolver?.invoke(it) }
        } else {
            receiverText = ""
            receiverType = null
            recvStart = -1
        }

        for (m in user) {
            if (m.typeScoped) {
                // Offered only at `receiver.abbrev` where the receiver matches the target type.
                if (dot == null || !m.enabled || prefix.isEmpty()) continue
                if (!m.abbreviation.startsWith(prefix, ignoreCase = true)) continue
                val fqn = m.receiverType!!
                val matches = if (m.static) staticMatches(receiverText, fqn)
                else receiverType != null && typeMatches(receiverType, fqn)
                if (!matches) continue
                if (result.elements.any { it.label == m.abbreviation }) continue
                val exp = expand(m) ?: continue
                // Instance = POSTFIX: consume the `receiver.` so the template (using $EXPR$ = the receiver)
                // replaces the whole `expr.abbrev`. Static = member snippet: keep `Type.`, template is the member.
                val consumeReceiver = !m.static && recvStart in 0 until keyStart
                result.addElement(
                    CompletionItem(
                        label = m.abbreviation,
                        insertText = exp.text,
                        kind = CompletionItemKind.SNIPPET,
                        detail = m.description.ifBlank { fqn.substringAfterLast('.') + if (m.static) " (static)" else "" },
                        documentation = m.description.ifBlank { null },
                        sortPriority = if (m.abbreviation == prefix) -35 else 60,
                        additionalEdits = if (consumeReceiver) listOf(TextEdit(TextRange(recvStart, keyStart), "")) else emptyList(),
                        caret = CaretAction.ExpandSnippet(exp),
                    ),
                )
                continue
            }
            if (m.abbreviation in builtinAbbrevs) {
                // Override of a built-in: act only on the backend's already-emitted item.
                if (!m.enabled) {
                    result.removeIf { it.kind == CompletionItemKind.SNIPPET && it.label == m.abbreviation }
                } else {
                    val exp = expand(m) ?: continue
                    result.replaceAll { item ->
                        if (item.kind == CompletionItemKind.SNIPPET && item.label == m.abbreviation) {
                            item.copy(
                                insertText = exp.text,
                                documentation = m.description.ifBlank { item.documentation },
                                caret = CaretAction.ExpandSnippet(exp),
                            )
                        } else {
                            item
                        }
                    }
                }
                continue
            }
            // A net-new user macro: add it (context-blind), prefix-gated so the bare-caret popup isn't flooded.
            if (!m.enabled || prefix.isEmpty()) continue
            if (!m.abbreviation.startsWith(prefix, ignoreCase = true)) continue
            if (result.elements.any { it.label == m.abbreviation }) continue
            val exp = expand(m) ?: continue
            result.addElement(
                CompletionItem(
                    label = m.abbreviation,
                    insertText = exp.text,
                    kind = CompletionItemKind.SNIPPET,
                    detail = m.description.ifBlank { exp.text.lineSequence().firstOrNull()?.take(48) },
                    documentation = m.description.ifBlank { null },
                    // Sign convention (see ItemTierWeigher): positive keeps a not-yet-fully-typed macro below
                    // real symbols; a fully-typed abbreviation opts into the symbol tier and tops.
                    sortPriority = if (m.abbreviation == prefix) -35 else 66,
                    caret = CaretAction.ExpandSnippet(exp),
                ),
            )
        }
    }

    companion object {
        /** Runs after the language backend (0) and postfix (5_000), before buffer-words (10_000). */
        const val ORDER = 6_000

        /** Leading whitespace of the line containing [offset] — prepended to a multi-line expansion's inner rows. */
        private fun leadingIndent(text: CharSequence, offset: Int): String {
            var lineStart = offset.coerceIn(0, text.length)
            while (lineStart > 0 && text[lineStart - 1] != '\n') lineStart--
            val sb = StringBuilder()
            var i = lineStart
            while (i < text.length && (text[i] == ' ' || text[i] == '\t')) { sb.append(text[i]); i++ }
            return sb.toString()
        }

        /** The `.` immediately before [keyStart] (skipping spaces/tabs), or null if not a member access. Mirrors
         *  the receiver detection the postfix contributor uses. */
        private fun dotBefore(text: CharSequence, keyStart: Int): Int? {
            var i = keyStart.coerceIn(0, text.length) - 1
            while (i >= 0 && (text[i] == ' ' || text[i] == '\t')) i--
            return if (i >= 0 && text[i] == '.') i else null
        }

        /** Start of the receiver expression ending just before [dot] — a backward scan over a balanced suffix
         *  (identifiers, dots, bracket/paren groups, string/char literals). Language-agnostic. */
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

        /** True if [type] is, or is a subtype of, the type named [fqn] (BFS over the supertype graph). */
        private fun typeMatches(type: TypeRef, fqn: String): Boolean {
            val seen = HashSet<String>()
            val queue = ArrayDeque<TypeRef>()
            queue.add(type)
            while (queue.isNotEmpty()) {
                val t = queue.removeFirst()
                if (!seen.add(t.qualifiedName)) continue
                if (t.qualifiedName == fqn) return true
                queue.addAll(runCatching { t.supertypes() }.getOrDefault(emptyList()))
            }
            return false
        }

        /** Heuristic match for a STATIC receiver: the receiver text is the type's FQN or its simple name
         *  (e.g. `String` or `java.lang.String` matches `java.lang.String`). */
        private fun staticMatches(receiverText: String, fqn: String): Boolean {
            if (receiverText.isEmpty()) return false
            return receiverText == fqn || receiverText.substringAfterLast('.') == fqn.substringAfterLast('.')
        }
    }
}
