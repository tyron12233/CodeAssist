package dev.ide.lang.jdt.completion

import dev.ide.lang.completion.CompletionItem
import org.eclipse.jdt.internal.compiler.lookup.TypeBinding

/**
 * Context-aware ranking (lower `sortPriority` = shown higher). Strongest first: exact prefix; a type
 * assignable to the expected type at the caret (smart); proximity (locals/params over the enclosing
 * class's members over inherited, with `java.lang.Object` members pushed down); deprecated demoted.
 */
internal object CompletionRanker {

    fun rank(candidates: List<Candidate>, ctx: AnalyzedContext): List<CompletionItem> =
        candidates
            .map { it to score(it, ctx) }
            .sortedWith(compareBy({ it.second }, { it.first.name.length }, { it.first.name }))
            .map { (c, s) ->
                CompletionItem(
                    label = c.presentation, insertText = c.insertText, kind = c.kind, detail = c.tail,
                    container = c.container, documentation = c.documentation,
                    sortPriority = s, additionalEdits = c.importEdits, caret = c.caret,
                )
            }

    private fun score(c: Candidate, ctx: AnalyzedContext): Int {
        var s = 1000

        when {
            c.name == ctx.prefix -> s -= 500
            ctx.prefix.isNotEmpty() && c.name.startsWith(ctx.prefix) -> s -= 200
            ctx.prefix.isNotEmpty() && c.name.startsWith(ctx.prefix, ignoreCase = true) -> s -= 120
        }

        val expected = ctx.expectedType
        if (expected != null && c.type != null && assignable(c.type, expected)) s -= 300

        s += when (c.proximity) {
            Proximity.LOCAL -> 0
            Proximity.PARAMETER -> 8
            Proximity.OWN_MEMBER -> 25
            Proximity.NESTED_TYPE -> 35
            Proximity.INHERITED -> 55
            Proximity.OBJECT_MEMBER -> 180
            Proximity.PACKAGE -> 40
            Proximity.UNIMPORTED_TYPE -> 300 // below in-scope; index types appear, but never hide your code
        }

        if (c.discouraged) s += 200 // a static member reached via an instance: valid, but instance members first
        if (c.deprecated) s += 400
        return s
    }

    // Compatibility on *erasures* — a ranking signal, not a type check: `new ArrayList<…>` should rank for
    // an expected `List<String>` even though `ArrayList<E>` (the bare resolved type) isn't itself compatible
    // with the parameterized `List<String>`. Erasure-level subtype (ArrayList ⊑ List) captures the intent.
    private fun assignable(from: TypeBinding, to: TypeBinding): Boolean {
        val f = runCatching { from.erasure() }.getOrNull() ?: from
        val t = runCatching { to.erasure() }.getOrNull() ?: to
        return runCatching { f.isCompatibleWith(t) }.getOrDefault(false) ||
            String(from.readableName()) == String(to.readableName())
    }
}
