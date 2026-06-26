package dev.ide.lang.completion

import dev.ide.lang.LanguageId
import dev.ide.lang.SourceAnalyzer
import dev.ide.lang.dom.TextRange

/**
 * A concrete [CompletionResultSet] backed by a mutable list — the shared accumulator the completion engine
 * hands to each contributor, and the sink the engine-free [complete] runners below use. Keeping it in
 * language-api lets the engine, plugins, and in-module language tests all drive contributors through one
 * implementation instead of re-rolling a sink.
 */
class BasicCompletionResultSet(override val params: CompletionParams) : CompletionResultSet {
    private val items = ArrayList<CompletionItem>()
    private var stopped = false

    /** Set by a contributor via [markIncomplete]; the engine maps it to [CompletionResult.isIncomplete]. */
    var isIncomplete: Boolean = false
        private set

    /** Set by the authoritative backend via [setReplacementRange]; null = use the params' prefix range. */
    var replacementRange: TextRange? = null
        private set

    override val prefix: String get() = params.prefix
    override val elements: List<CompletionItem> get() = items
    override val isStopped: Boolean get() = stopped

    override fun addElement(item: CompletionItem) { items.add(item) }
    override fun addAllElements(items: Iterable<CompletionItem>) { this.items.addAll(items) }
    override fun removeIf(predicate: (CompletionItem) -> Boolean) { items.removeAll(predicate) }
    override fun replaceAll(transform: (CompletionItem) -> CompletionItem) {
        for (i in items.indices) items[i] = transform(items[i])
    }
    override fun stopHere() { stopped = true }
    override fun markIncomplete() { isIncomplete = true }
    override fun setReplacementRange(range: TextRange) { replacementRange = range }

    /** Snapshot the accumulated items into a [CompletionResult] (used by the engine-free runners). */
    fun toResult(): CompletionResult =
        CompletionResult(items.toList(), isIncomplete, replacementRange ?: params.replacementRange)
}

/** Build [CompletionParams] for the engine-free runners from a [CompletionRequest]. `position`/`parsedFile`
 *  are null (no DOM is supplied here); a backend contributor does its own context analysis. */
private fun paramsFromRequest(request: CompletionRequest, language: LanguageId): CompletionParams {
    val text = request.document.text
    val offset = request.offset.coerceIn(0, text.length)
    var i = offset
    while (i > 0 && isWordChar(text[i - 1])) i--
    val prefix = text.subSequence(i, offset).toString()
    return CompletionParams(
        document = request.document,
        offset = offset,
        prefix = prefix,
        language = language,
        trigger = request.trigger,
        replacementRange = TextRange(offset - prefix.length, offset),
        position = null,
        parsedFile = null,
    )
}

private fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '$'

/**
 * Run a single [CompletionContributor] over [request] with no engine, EP contributors, or weighers — just
 * this contributor into a fresh [BasicCompletionResultSet]. The simple driver for tests and one-off use.
 */
suspend fun CompletionContributor.complete(
    request: CompletionRequest,
    language: LanguageId = LanguageId(""),
): CompletionResult {
    val params = paramsFromRequest(request, language)
    val sink = BasicCompletionResultSet(params)
    fillCompletionVariants(params, sink)
    return sink.toResult()
}

/**
 * Run all of a [SourceAnalyzer]'s [completionContributions][SourceAnalyzer.completionContributions] over
 * [request], without the engine's EP contributors or weighers. Reproduces the analyzer's own completion
 * (what the old `analyzer.completion.complete(...)` did). For tests and engine-free drivers; production goes
 * through `dev.ide.core.completion.CompletionEngine`.
 */
suspend fun SourceAnalyzer.complete(
    request: CompletionRequest,
    language: LanguageId = LanguageId(""),
): CompletionResult {
    val params = paramsFromRequest(request, language)
    val sink = BasicCompletionResultSet(params)
    for (c in completionContributions()) {
        if (sink.isStopped) break
        if (c.appliesTo(language) && (params.position == null || c.pattern.accepts(params.position))) {
            c.contributor.fillCompletionVariants(params, sink)
        }
    }
    return sink.toResult()
}
