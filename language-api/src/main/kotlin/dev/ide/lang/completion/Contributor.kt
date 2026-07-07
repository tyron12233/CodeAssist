package dev.ide.lang.completion

import dev.ide.lang.LanguageId
import dev.ide.lang.dom.DomNode
import dev.ide.lang.dom.ParsedFile
import dev.ide.lang.dom.TextRange
import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.lang.patterns.DomPatterns
import dev.ide.lang.patterns.ElementPattern
import dev.ide.lang.resolve.Scope
import dev.ide.lang.resolve.TypeRef
import dev.ide.platform.ExtensionPoint

/**
 * IntelliJ-style **completion contribution** SPI. The engine ([dev.ide.core] `CompletionEngine`) has no
 * privileged per-language completion *service*; it runs every matching [CompletionContributor] over one
 * shared [CompletionResultSet]. The language backends (JDT / Kotlin / XML) publish their own completion as
 * contributors via [dev.ide.lang.SourceAnalyzer.completionContributions], alongside cross-cutting ones —
 * keywords, postfix templates, buffer words — and any a plugin registers. So a plugin can:
 *   • **add** items at a position ([CompletionResultSet.addElement]),
 *   • **filter** the assembled set ([CompletionResultSet.removeIf]),
 *   • **decorate** existing items ([CompletionResultSet.replaceAll] — change insert text, docs, caret),
 *   • **stop** later contributors ([CompletionResultSet.stopHere]),
 * and influence ordering by registering a [CompletionWeigher] rather than hardcoding `sortPriority`.
 *
 * A contributor declares *where* it applies through its [CompletionContribution] (a [LanguageId] set + an
 * [ElementPattern] over the neutral DOM), so the engine only runs the relevant ones at the caret.
 */
interface CompletionContributor {
    /** Stable identifier, for ordering ties / diagnostics (e.g. "kotlin.keywords", "platform.bufferWords"). */
    val id: String

    /** Add / filter / decorate items in [result] for the position described by [params]. */
    suspend fun fillCompletionVariants(params: CompletionParams, result: CompletionResultSet)
}

/**
 * The caret situation shared by all contributors for one completion. Built once by the engine from the
 * language's tolerant DOM, so contributors don't each re-parse. [position] is the deepest DOM node at the
 * caret (the pattern-matching subject); [scope] / [expectedType] are resolved lazily by the engine from the
 * language analyzer and may be null on a backend that can't supply them.
 */
class CompletionParams(
    val document: DocumentSnapshot,
    val offset: Int,
    val prefix: String,
    val language: LanguageId,
    val trigger: CompletionTrigger,
    /** Range the accepted item replaces (the identifier under the caret). */
    val replacementRange: TextRange,
    /** Deepest DOM node containing the caret; null if the file couldn't be parsed. */
    val position: DomNode?,
    val parsedFile: ParsedFile?,
    /** Visible names at the caret (name-reference candidates); null if unavailable. */
    val scope: Scope? = null,
    /** Type the context expects, for ranking; null if unavailable. */
    val expectedType: TypeRef? = null,
    /** Resolve the *produced* type of a DOM node (the language analyzer's `resolveType`), for contributors
     *  that need a receiver's type — e.g. postfix templates gated on Boolean/Iterable. Null when the backend
     *  can't resolve types or the file didn't parse. */
    val typeResolver: ((DomNode) -> TypeRef?)? = null,
) {
    /** The graded matcher for [prefix] — exact / prefix / camel-hump / substring. Contributors gate
     *  candidates through this (not a raw `startsWith`) so `mDL` completes `myDynamicList` uniformly. */
    val matcher: PrefixMatcher = PrefixMatcher(prefix)

    /** The common gate before adding an item: does [name] match the typed [prefix] at any grade? */
    fun prefixMatches(name: String): Boolean = matcher.matches(name)
}

/**
 * The mutable, shared accumulator each contributor receives in turn. Because a later contributor sees the
 * items earlier ones added, this single object delivers all four capabilities — add, filter, decorate,
 * and stop. The engine owns the backing list and applies [CompletionWeigher]s afterwards.
 */
interface CompletionResultSet {
    val params: CompletionParams
    val prefix: String

    fun addElement(item: CompletionItem)
    fun addAllElements(items: Iterable<CompletionItem>)

    /** Drop already-added items matching [predicate] (the *filter* capability). */
    fun removeIf(predicate: (CompletionItem) -> Boolean)

    /** Transform every already-added item (the *decorate* capability: rewrite insert text, docs, caret…). */
    fun replaceAll(transform: (CompletionItem) -> CompletionItem)

    /** Immutable snapshot of what has been added so far. */
    val elements: List<CompletionItem>

    /** Ask the engine to skip the remaining contributors (e.g. a definitive member-access result). */
    fun stopHere()
    val isStopped: Boolean

    /** Signal that this contributor truncated its candidates → the engine sets [CompletionResult.isIncomplete]
     *  so the editor re-queries as the user narrows the prefix. */
    fun markIncomplete()

    /** Override the range an accepted item replaces. The authoritative language backend sets this (it knows
     *  the language's word boundaries — XML's `:@?+/`, etc.); otherwise the engine's prefix-derived range stands. */
    fun setReplacementRange(range: TextRange)
}

/**
 * One registration: a [contributor] gated by the [LanguageId] set ([languages]; empty = every language) and
 * an [ElementPattern] over the DOM [position][CompletionParams.position]. [order] sets run order (lower
 * first) — the language backends run early (so cross-cutting contributors can filter/decorate their output),
 * buffer-words last.
 */
class CompletionContribution(
    val contributor: CompletionContributor,
    val pattern: ElementPattern<DomNode> = DomPatterns.anyNode(),
    val languages: Set<LanguageId> = emptySet(),
    val order: Int = 0,
) {
    fun appliesTo(language: LanguageId): Boolean = languages.isEmpty() || language in languages
}

/** The extension point through which completion contributors are contributed (cf. `platform.languageBackend`). */
val COMPLETION_CONTRIBUTOR_EP = ExtensionPoint<CompletionContribution>("platform.completionContributor")

/**
 * A composable ranking signal. The engine sorts the merged item set by every weigher (its own built-ins +
 * any contributed here), comparing items by each weigher in ascending [order], **higher weight first**. This
 * replaces scattering magic `sortPriority` numbers across backends — proximity, expected-type, composable
 * boosting, etc. each become an independent, overridable weigher.
 */
interface CompletionWeigher {
    val id: String

    /** A score for [item] in [params]; higher sorts earlier. */
    fun weigh(item: CompletionItem, params: CompletionParams): Double

    /** Lower-order weighers dominate the comparison (compared first). */
    val order: Int get() = 0
}

/** The extension point through which completion weighers (ranking signals) are contributed. */
val COMPLETION_WEIGHER_EP = ExtensionPoint<CompletionWeigher>("platform.completionWeigher")
