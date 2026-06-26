package dev.ide.core.completion

import dev.ide.lang.completion.BasicCompletionResultSet
import dev.ide.lang.completion.COMPLETION_CONTRIBUTOR_EP
import dev.ide.lang.completion.COMPLETION_WEIGHER_EP
import dev.ide.lang.completion.CompletionContribution
import dev.ide.lang.completion.CompletionContributor
import dev.ide.lang.completion.CompletionItem
import dev.ide.lang.completion.CompletionParams
import dev.ide.lang.completion.CompletionResult
import dev.ide.lang.completion.CompletionWeigher
import dev.ide.platform.ExtensionRegistry

/**
 * The unified, IntelliJ-style completion engine. There is **no privileged language backend**: the engine
 * runs a list of [CompletionContributor]s (the language backend's own contributors, published via
 * [dev.ide.lang.SourceAnalyzer.completionContributions], plus every cross-cutting / plugin contributor from
 * [COMPLETION_CONTRIBUTOR_EP]) over one shared
 * [CompletionResultSet], then ranks the merged set with the [CompletionWeigher]s (built-in + [COMPLETION_WEIGHER_EP]).
 *
 * Contributors run in ascending [CompletionContribution.order] (the backend early, so a later contributor can
 * filter/decorate its items; buffer-words last). A contributor may [CompletionResultSet.stopHere] to skip the
 * rest. Pattern gating is by the contribution's DOM [pattern][CompletionContribution.pattern] evaluated at
 * [CompletionParams.position]; when the file couldn't be parsed (`position == null`) language-matching
 * contributors all run (patterns can't be evaluated without a node).
 */
class CompletionEngine(private val extensions: ExtensionRegistry) {

    /**
     * Run completion for [params]. [perCall] are contributions sourced per-completion rather than from the EP
     * — the current file's language backend (wrapped as a contributor) and any analyzer-bound contributors the
     * backend exposes ([dev.ide.lang.SourceAnalyzer.completionContributions]). They are merged into the same run
     * list as the EP contributors and treated uniformly (no privileged backend).
     */
    suspend fun complete(params: CompletionParams, perCall: List<CompletionContribution> = emptyList()): CompletionResult {
        val sink = BasicCompletionResultSet(params)

        val runList = (extensions.extensions(COMPLETION_CONTRIBUTOR_EP) + perCall)
            .filter { it.appliesTo(params.language) }
            .filter { params.position == null || it.pattern.accepts(params.position) }
            .sortedBy { it.order }

        for (c in runList) {
            if (sink.isStopped) break
            c.contributor.fillCompletionVariants(params, sink)
        }

        val ranked = rank(sink.elements, params)
            .distinctBy { Triple(it.kind, it.label, it.insertText) }
            .take(MAX_ITEMS)
        return CompletionResult(
            ranked,
            isIncomplete = sink.isIncomplete,
            replacementRange = sink.replacementRange ?: params.replacementRange,
        )
    }

    /** Stable-sort by every weigher (built-ins + contributed), comparing in ascending order, higher first. */
    private fun rank(items: List<CompletionItem>, params: CompletionParams): List<CompletionItem> {
        if (items.isEmpty()) return items
        val weighers = (BUILT_IN_WEIGHERS + extensions.extensions(COMPLETION_WEIGHER_EP)).sortedBy { it.order }
        val comparator = Comparator<CompletionItem> { a, b ->
            for (w in weighers) {
                val wa = w.weigh(a, params)
                val wb = w.weigh(b, params)
                if (wa != wb) return@Comparator wb.compareTo(wa) // higher weight ranks earlier
            }
            0
        }
        return items.sortedWith(comparator)
    }

    companion object {
        const val MAX_ITEMS = 200
        /** Run order for the language backend's own contributor — early, so cross-cutting contributors can
         *  filter/decorate its items. */
        const val BACKEND_ORDER = 0
        // The only built-in: honour the legacy `sortPriority` (lower number = earlier) so existing backend
        // ranking is preserved. Plugins layer extra weighers on top via the EP.
        val BUILT_IN_WEIGHERS = listOf(SortPriorityWeigher)
    }
}

/** Built-in weigher mapping the legacy [CompletionItem.sortPriority] (lower = better) to a weight. */
object SortPriorityWeigher : CompletionWeigher {
    override val id = "platform.sortPriority"
    override val order = 1000 // last tiebreaker — explicit weighers dominate
    override fun weigh(item: CompletionItem, params: CompletionParams): Double = -item.sortPriority.toDouble()
}

