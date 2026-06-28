package dev.ide.core

import dev.ide.index.IndexService
import dev.ide.index.SourceDocValue
import dev.ide.lang.jdt.index.JavaSourceDocIndex
import dev.ide.lang.kotlin.index.KotlinSourceDocIndex
import dev.ide.lang.resolve.SourceDocProvider

/**
 * [SourceDocProvider] backed by the persistent source-doc indexes (`java.sourceDoc` + `kotlin.sourceDoc`,
 * built in the background over attached source archives), with an on-demand parse [fallback] for the window
 * before the index has built (or just-downloaded sources not yet indexed). Per-type lookups are memoized;
 * misses are not cached, so a type resolves through the index as soon as its segment is built.
 */
class IndexBackedSourceDocs(
    private val index: IndexService?,
    private val fallback: SourceDocProvider = SourceDocProvider.NONE,
) : SourceDocProvider {

    private class TypeDocs(val byMethod: Map<String, List<SourceDocValue>>, val classDoc: String?)

    private val cache = object : LinkedHashMap<String, TypeDocs>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TypeDocs>?) = size > 256
    }

    private fun fromIndex(fqn: String): TypeDocs? {
        synchronized(cache) { cache[fqn]?.let { return it } }
        val ix = index ?: return null
        val values = (ix.exact<SourceDocValue>(JavaSourceDocIndex.id, fqn) +
            ix.exact<SourceDocValue>(KotlinSourceDocIndex.id, fqn)).toList()
        if (values.isEmpty()) return null // not indexed (yet) → let the fallback parse live
        val docs = TypeDocs(
            byMethod = values.filter { it.name.isNotEmpty() }.groupBy { it.name },
            classDoc = values.firstOrNull { it.name.isEmpty() }?.doc,
        )
        synchronized(cache) { cache[fqn] = docs }
        return docs
    }

    override fun method(declaringFqn: String, methodName: String, arity: Int): SourceDocProvider.MethodDoc? {
        fromIndex(declaringFqn)?.byMethod?.get(methodName)?.let { cands ->
            val v = cands.firstOrNull { it.arity == arity } ?: cands.first()
            return SourceDocProvider.MethodDoc(v.names, v.doc)
        }
        return fallback.method(declaringFqn, methodName, arity)
    }

    override fun classDoc(fqn: String): String? =
        fromIndex(fqn)?.classDoc ?: fallback.classDoc(fqn)

    // The index stores only CLEANED doc, so raw markup (for quick-doc's rich rendering) always comes from the
    // live fallback — it re-parses the source on demand (project dir or -sources.jar), which is fine for a
    // user-triggered popup.
    override fun methodRaw(declaringFqn: String, methodName: String, arity: Int): String? =
        fallback.methodRaw(declaringFqn, methodName, arity)

    override fun classDocRaw(fqn: String): String? = fallback.classDocRaw(fqn)
}
