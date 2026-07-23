package dev.ide.core.completion

import dev.ide.lang.completion.BasicCompletionResultSet
import dev.ide.lang.completion.COMPLETION_CONTRIBUTOR_EP
import dev.ide.lang.completion.COMPLETION_WEIGHER_EP
import dev.ide.lang.completion.CompletionContribution
import dev.ide.lang.completion.CompletionContributor
import dev.ide.lang.completion.CompletionItem
import dev.ide.lang.completion.CompletionItemKind
import dev.ide.lang.completion.CompletionParams
import dev.ide.lang.completion.CompletionResult
import dev.ide.lang.completion.CompletionWeigher
import dev.ide.lang.completion.STANDARD_WEIGHERS
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
    suspend fun complete(
        params: CompletionParams,
        perCall: List<CompletionContribution> = emptyList(),
        options: CompletionOptions = CompletionOptions(),
    ): CompletionResult {
        val sink = BasicCompletionResultSet(params)

        val runList = (extensions.extensions(COMPLETION_CONTRIBUTOR_EP) + perCall)
            .filter { it.appliesTo(params.language) }
            .filter { params.position == null || it.pattern.accepts(params.position) }
            .filter { options.allows(it.contributor.id) }
            .sortedBy { it.order }

        for (c in runList) {
            if (sink.isStopped) break
            c.contributor.fillCompletionVariants(params, sink)
        }

        val ranked = rank(sink.elements, params)
            // Key on `container` too: two DISTINCT types that share a simple name but live in different
            // packages (e.g. androidx.compose.ui.Modifier vs java.lang.reflect.Modifier) are not duplicates —
            // both must survive so the user can pick, disambiguated by package. Without it, whichever a
            // contributor emitted first shadowed the other (dropping the Compose type on some devices).
            .distinctBy { listOf(it.kind, it.label, it.insertText, it.container) }
            .take(options.maxItems)
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
        // Built-ins, dominant first: the structural tier ([ItemTierWeigher], order 100) keeps real symbols
        // above fallback snippets/words regardless of the backend's `sortPriority` scale; the standard
        // relevance chain (`STANDARD_WEIGHERS`, orders 200..399 — expected type, match grade, context boost,
        // callable weight, imported, kind, deprecation) ranks within the tier off each item's
        // [dev.ide.lang.completion.CompletionRelevance]; and [SortPriorityWeigher] (1000) honours the legacy
        // `sortPriority` (lower = earlier) as the final within-backend tiebreaker. Plugins layer more
        // weighers via the EP (the host registers the per-project stats weigher there).
        val BUILT_IN_WEIGHERS = listOf(ItemTierWeigher, SortPriorityWeigher) + STANDARD_WEIGHERS
    }
}

/**
 * User-tunable completion knobs (from the Settings screen), passed per call so a change takes effect on the
 * next keystroke without rebuilding the engine. [maxItems] caps the ranked list; [postfixTemplates] /
 * [wordCompletion] drop the corresponding built-in contributors from the run when off.
 */
data class CompletionOptions(
    val maxItems: Int = CompletionEngine.MAX_ITEMS,
    val postfixTemplates: Boolean = true,
    val wordCompletion: Boolean = true,
) {
    /** Whether the contributor [id] runs under these options (filters the optional built-ins). */
    fun allows(id: String): Boolean = when (id) {
        "platform.postfix" -> postfixTemplates
        "platform.bufferWords" -> wordCompletion
        else -> true
    }
}

/** Built-in weigher mapping the legacy [CompletionItem.sortPriority] (lower = better) to a weight. */
object SortPriorityWeigher : CompletionWeigher {
    override val id = "platform.sortPriority"
    override val order = 1000 // last tiebreaker — explicit weighers dominate
    override fun weigh(item: CompletionItem, params: CompletionParams): Double = -item.sortPriority.toDouble()
}

/**
 * The structural ranking tier — the first built-in signal, so it dominates the raw [CompletionItem.sortPriority]
 * numbers. Those numbers live on **incompatible per-backend scales** (the JDT ranker scores real members
 * ~500–1000; the Kotlin backend 0–3), so comparing them directly across contributors is meaningless: a
 * postfix/live template's small fixed priority (e.g. 60) would outrank a JDT member (500+) purely because
 * `60 < 500`, floating snippets above real code. This weigher buckets items first, then lets the remaining
 * weighers ([SortPriorityWeigher] last) order *within* a bucket — where each backend's own scale is consistent.
 *
 * Three tiers (higher ranks earlier):
 *   - **2** real symbols, plus the keyword/snippet items a contributor deliberately boosted — an exact-key
 *     match the user fully typed (`.var`) or a contextual literal (`true`/`false`). The signal is a
 *     **non-positive** [CompletionItem.sortPriority] on a SNIPPET/KEYWORD item (the contributor convention).
 *   - **1** fallback keyword / live / postfix-template suggestions not yet fully typed (a **positive**
 *     [CompletionItem.sortPriority] on a SNIPPET/KEYWORD item) — kept below real symbols.
 *   - **0** buffer/hippie words ([CompletionItemKind.WORD]) — no semantic backing, always last.
 */
object ItemTierWeigher : CompletionWeigher {
    override val id = "platform.itemTier"
    // Before SortPriorityWeigher (1000) so the tier dominates the legacy numbers; after explicit plugin
    // weighers (default order 0) so a plugin can still override the structural order when it means to.
    override val order = 100

    override fun weigh(item: CompletionItem, params: CompletionParams): Double = when {
        item.kind == CompletionItemKind.WORD -> 0.0
        (item.kind == CompletionItemKind.SNIPPET || item.kind == CompletionItemKind.KEYWORD) && item.sortPriority > 0 -> 1.0
        else -> 2.0
    }
}

