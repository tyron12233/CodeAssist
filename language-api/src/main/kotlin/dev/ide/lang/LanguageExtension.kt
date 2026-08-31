package dev.ide.lang

/**
 * An extension that declares which languages it applies to, so the host can route by language without
 * knowing the extension's concrete type. This is the shape IntelliJ expresses as a `language=` attribute on
 * a language-keyed extension point; here it is a property, and [LanguageExtensionIndex] is the keyed lookup
 * over it.
 *
 * **Empty [languages] means every language.** That is the convention the contributed extension points use
 * ([dev.ide.lang.completion.CompletionContribution], [dev.ide.lang.postfix.PostfixTemplate], the analysis
 * providers): an extension that names no language is cross-cutting and runs everywhere. Note this is the
 * opposite of [dev.ide.analysis.Analyzer], which must name its languages to run at all.
 */
interface LanguageScoped {
    val languages: Set<LanguageId>
}

/** True when this extension applies to [language]: it named [language], or it named nothing at all. */
fun LanguageScoped.appliesTo(language: LanguageId): Boolean =
    languages.isEmpty() || language in languages

/**
 * A per-language index over a set of [LanguageScoped] extensions: IntelliJ's `LanguageExtension`, which
 * resolves a language-keyed extension point by language id rather than scanning every registration.
 *
 * Two reasons this exists rather than a `filter { it.appliesTo(lang) }` at each call site. First, cost:
 * completion resolves its contributor list on nearly every keystroke, so the scan is on the interactive
 * path, and a keyed lookup makes it proportional to the matching extensions instead of to every registered
 * one. Second, uniformity: language routing is one policy (including "empty means every language"), and
 * hand-rolled filters drift — the same predicate was previously spelled three different ways across the
 * engine.
 *
 * Build one per snapshot of an extension point's contents. It is immutable, so a consumer that caches an
 * index must rebuild it when the registry changes; a consumer on a hot path should cache by the extension
 * list's identity rather than rebuilding per call.
 */
class LanguageExtensionIndex<T : LanguageScoped>(all: List<T>) {

    /** The extensions that named no language: they apply to every language. */
    private val anyLanguage: List<T> = all.filter { it.languages.isEmpty() }

    /** For each named language, its applicable extensions **in the original registration order** (so a
     *  consumer that relies on registration order, as the completion engine does before it sorts by
     *  `order`, sees exactly what a flat filter would have produced). */
    private val byLanguage: Map<String, List<T>> =
        all.flatMapTo(LinkedHashSet()) { e -> e.languages.map { it.id } }
            .associateWith { id -> all.filter { it.languages.isEmpty() || it.languages.any { l -> l.id == id } } }

    /** Every extension applying to [language], in registration order. A language no extension named
     *  resolves to the cross-cutting ones alone. */
    fun forLanguage(language: LanguageId): List<T> = byLanguage[language.id] ?: anyLanguage

    /** The language ids some extension named. Cross-cutting extensions are not represented here. */
    val declaredLanguages: Set<String> get() = byLanguage.keys
}
