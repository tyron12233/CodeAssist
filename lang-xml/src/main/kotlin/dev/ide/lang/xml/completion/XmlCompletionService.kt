package dev.ide.lang.xml.completion

import dev.ide.lang.completion.CompletionItem
import dev.ide.lang.completion.CompletionRequest
import dev.ide.lang.completion.CompletionResult
import dev.ide.lang.completion.CompletionService
import dev.ide.lang.dom.ParsedFile

/**
 * Computes the [XmlCompletionPosition] at the caret, asks every registered [XmlCompletionContributor] for
 * candidates, then prefix-filters and orders them. The engine owns *where* (context); contributors own
 * *what* (Android widgets, attributes, resource references). With no contributors registered the result
 * is empty — `lang-xml` ships no Android knowledge of its own.
 *
 * [parseFor] lets the owning analyzer share its already-built tree instead of re-parsing; it defaults to a
 * fresh full parse (cheap for XML) so the service is usable standalone (and in tests).
 */
class XmlCompletionService(
    private val contributors: () -> List<XmlCompletionContributor>,
    private val parseFor: (CompletionRequest) -> ParsedFile = {
        dev.ide.lang.xml.XmlIncrementalParser().parseFull(it.document)
    },
) : CompletionService {

    override suspend fun complete(request: CompletionRequest): CompletionResult {
        val text = request.document.text
        val parsed = parseFor(request)
        val pos = XmlContextScanner.scan(text, request.offset, parsed, request.document.file.path)
        if (pos.kind == XmlCompletionKind.UNKNOWN) {
            return CompletionResult(emptyList(), isIncomplete = false, replacementRange = pos.replacementRange)
        }

        val candidates = contributors().flatMap { runCatching { it.contribute(pos) }.getOrDefault(emptyList()) }
        val items = candidates
            .filter { matchesPrefix(it, pos.prefix) }
            .sortedWith(compareBy({ it.sortPriority }, { it.label.lowercase() }))

        return CompletionResult(items, isIncomplete = false, replacementRange = pos.replacementRange)
    }

    private fun matchesPrefix(item: CompletionItem, prefix: String): Boolean =
        nameMatches(item.label, prefix) || nameMatches(item.insertText, prefix)

    companion object {
        /**
         * Namespace-aware prefix match: the candidate matches if it starts with [prefix], OR its **local
         * name** does — the part after a namespace `:` (so `layout_w` matches `android:layout_width`) or a
         * resource `/` (so `home` matches `@string/home`). This is what lets the user complete an attribute
         * without typing the `android:` prefix every time. Case-insensitive.
         */
        fun nameMatches(candidate: String, prefix: String): Boolean {
            if (prefix.isEmpty()) return true
            if (candidate.startsWith(prefix, ignoreCase = true)) return true
            val afterColon = candidate.substringAfter(':', "")
            if (afterColon.isNotEmpty() && afterColon.startsWith(prefix, ignoreCase = true)) return true
            val afterSlash = candidate.substringAfterLast('/', "")
            return afterSlash.isNotEmpty() && afterSlash.startsWith(prefix, ignoreCase = true)
        }
    }
}
