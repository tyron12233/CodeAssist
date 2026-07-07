package dev.ide.lang.completion

/**
 * The standard relevance weighers — the IntelliJ signal set (`kotlin.expected.type`, `kotlin.kind`,
 * `kotlin.callableWeight`, `stats`, prefix-match quality) expressed as [CompletionWeigher]s so every
 * backend and plugin ranks through ONE chain instead of private `compareBy` ladders. All read the
 * producer-computed [CompletionRelevance] off the item (no re-resolution at rank time); the match-grade
 * weigher alone consults [CompletionParams.matcher].
 *
 * Order layout (lower order = compared first = dominates): the engine's structural `ItemTierWeigher` (100)
 * stays the coarse tier, these occupy 200..399, and the legacy `sortPriority` (1000) stays the final
 * within-backend tiebreaker. Plugins wanting to dominate everything register below 100.
 */

/** The primary lever: candidates whose produced type fits the type the context expects rank first. */
object ExpectedTypeWeigher : CompletionWeigher {
    override val id = "platform.expectedType"
    override val order = 200
    override fun weigh(item: CompletionItem, params: CompletionParams): Double =
        if ((item.relevance ?: CompletionRelevance.NONE).fitsExpectedType) 1.0 else 0.0
}

/**
 * Prefix-match quality: exact > case-sensitive prefix > case-insensitive prefix > camel-hump > substring.
 * Graded against the item's leading identifier (a function label carries its signature — `Text(text: …)`
 * must still grade as `Text`). Items that don't match at all (a contributor chose to keep them) sink.
 */
object MatchGradeWeigher : CompletionWeigher {
    override val id = "platform.matchGrade"
    override val order = 220
    override fun weigh(item: CompletionItem, params: CompletionParams): Double {
        if (params.prefix.isEmpty()) return 0.0
        val name = item.label.takeWhile { it.isLetterOrDigit() || it == '_' || it == '$' }
        val grade = params.matcher.grade(name.ifEmpty { item.label }) ?: return -1.0
        return (PrefixMatcher.Grade.entries.size - 1 - grade.ordinal).toDouble()
    }
}

/** A backend-judged contextual fit beyond types — e.g. `@Composable` callables inside a composable
 *  calling context (Android Studio's Compose weigher). A boost, never a filter. */
object ContextBoostWeigher : CompletionWeigher {
    override val id = "platform.contextBoost"
    override val order = 240
    override fun weigh(item: CompletionItem, params: CompletionParams): Double =
        if ((item.relevance ?: CompletionRelevance.NONE).contextBoost) 1.0 else 0.0
}

/**
 * Per-project completion-acceptance frequency (IntelliJ's `stats` weigher): items the user actually
 * accepts float up over time. The counter lives with the host ([counts] is injected); the weight is
 * log-compressed so a runaway favorite cannot pin the list.
 */
class StatsWeigher(private val counts: (CompletionItem) -> Int) : CompletionWeigher {
    override val id = "platform.stats"
    override val order = 260
    override fun weigh(item: CompletionItem, params: CompletionParams): Double {
        val n = counts(item)
        return if (n <= 0) 0.0 else kotlin.math.ln(1.0 + n.coerceAtMost(1000))
    }
}

/** Callable grouping on a receiver (IntelliJ's `kotlin.callableWeight`): own members over source
 *  extensions over library extensions over universal scope functions over `Object`/`Any` methods. */
object CallableMetadataWeigher : CompletionWeigher {
    override val id = "platform.callableWeight"
    override val order = 300
    override fun weigh(item: CompletionItem, params: CompletionParams): Double =
        -(item.relevance ?: CompletionRelevance.NONE).callableWeight.toDouble()
}

/** In-scope / already-imported candidates over ones whose acceptance must add an import. */
object NotImportedWeigher : CompletionWeigher {
    override val id = "platform.notImported"
    override val order = 320
    override fun weigh(item: CompletionItem, params: CompletionParams): Double =
        if ((item.relevance ?: CompletionRelevance.NONE).inScope) 1.0 else 0.0
}

/**
 * Coarse declaration-kind/proximity ordering (IntelliJ's `kotlin.kind` + `proximity`): locals and
 * parameters over members over project symbols over library. Reads the producer-computed
 * [CompletionRelevance.proximity] rather than [CompletionItem.kind] so a backend whose `sortPriority`
 * already encodes finer cross-kind ranking (JDT's ecj relevance, the K2 runtime) is not reordered
 * until it opts in by filling the field.
 */
object KindWeigher : CompletionWeigher {
    override val id = "platform.kind"
    override val order = 340
    override fun weigh(item: CompletionItem, params: CompletionParams): Double =
        -(item.relevance ?: CompletionRelevance.NONE).proximity.toDouble()
}

/** Deprecated candidates sink within their group. */
object DeprecatedWeigher : CompletionWeigher {
    override val id = "platform.deprecated"
    override val order = 360
    override fun weigh(item: CompletionItem, params: CompletionParams): Double =
        if ((item.relevance ?: CompletionRelevance.NONE).deprecated) 0.0 else 1.0
}

/** The standard chain in one list, for the engine's built-ins (Stats is host-registered — it needs the
 *  per-project acceptance store). */
val STANDARD_WEIGHERS: List<CompletionWeigher> = listOf(
    ExpectedTypeWeigher, MatchGradeWeigher, ContextBoostWeigher,
    CallableMetadataWeigher, NotImportedWeigher, KindWeigher, DeprecatedWeigher,
)
