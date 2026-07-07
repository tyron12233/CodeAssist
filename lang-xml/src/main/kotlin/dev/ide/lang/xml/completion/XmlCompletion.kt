package dev.ide.lang.xml.completion

import dev.ide.lang.completion.CompletionContributor
import dev.ide.lang.completion.CompletionItem
import dev.ide.lang.completion.CompletionParams
import dev.ide.lang.completion.CompletionRequest
import dev.ide.lang.completion.CompletionResultSet
import dev.ide.lang.dom.ParsedFile

/**
 * The XML language backend's completion [CompletionContributor]: computes the [XmlCompletionPosition] at the
 * caret, asks every registered [XmlCompletionContributor] for candidates, then prefix-filters and orders them.
 * The engine owns *where* (context); contributors own *what* (Android widgets, attributes, resource refs).
 * With no contributors registered the result is empty — `lang-xml` ships no Android knowledge of its own.
 *
 * [parseFor] lets the owning analyzer share its already-built tree instead of re-parsing; it defaults to a
 * fresh full parse (cheap for XML) so it is usable standalone (and in tests).
 */
class XmlCompletion(
    private val contributors: () -> List<XmlCompletionContributor>,
    private val parseFor: (CompletionRequest) -> ParsedFile = {
        dev.ide.lang.xml.XmlIncrementalParser().parseFull(it.document)
    },
) : CompletionContributor {

    override val id = "xml.completion"

    override suspend fun fillCompletionVariants(params: CompletionParams, result: CompletionResultSet) {
        val request = CompletionRequest(params.document, params.offset, params.trigger)
        val text = request.document.text
        val parsed = parseFor(request)
        val pos = XmlContextScanner.scan(text, request.offset, parsed, request.document.file.path)
        result.setReplacementRange(pos.replacementRange)
        if (pos.kind == XmlCompletionKind.UNKNOWN) return

        val candidates = contributors().flatMap { runCatching { it.contribute(pos) }.getOrDefault(emptyList()) }
        val items = candidates
            .filter { matchesPrefix(it, pos.prefix) }
            .sortedWith(compareBy({ it.sortPriority }, { it.label.lowercase() }))
        result.addAllElements(items)
    }

    private fun matchesPrefix(item: CompletionItem, prefix: String): Boolean =
        nameMatches(item.label, prefix) || nameMatches(item.insertText, prefix)

    companion object {
        /**
         * Namespace-aware match: the candidate matches if it does at any [PrefixMatcher] grade
         * (prefix / camel-hump / substring — so `lw` matches `layout_width`), OR its **local name**
         * does — the part after a namespace `:` (so `layout_w` matches `android:layout_width`), a
         * resource `/` (so `home` matches `@string/home`), or a package `.` (so `MaterialButton` matches
         * the fully-qualified custom view `com.google.android.material.button.MaterialButton`). This is what
         * lets the user complete an attribute or a custom view by its short name. Case-insensitive.
         */
        fun nameMatches(candidate: String, prefix: String): Boolean {
            if (prefix.isEmpty()) return true
            val m = dev.ide.lang.completion.PrefixMatcher(prefix)
            if (m.matches(candidate)) return true
            val afterColon = candidate.substringAfter(':', "")
            if (afterColon.isNotEmpty() && m.matches(afterColon)) return true
            val afterSlash = candidate.substringAfterLast('/', "")
            if (afterSlash.isNotEmpty() && m.matches(afterSlash)) return true
            val afterDot = candidate.substringAfterLast('.', "")
            return afterDot.isNotEmpty() && m.matches(afterDot)
        }
    }
}
