package dev.ide.core.analysis

import dev.ide.index.IndexService
import dev.ide.index.SourceDocValue
import dev.ide.lang.java.index.JavaSourceDocIndex
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

    /**
     * One type's docs. Java values hold cleaned text (raw comes from the live fallback); Kotlin values hold the
     * KDoc with its markup ([KotlinSourceDocIndex] stores it so), which is the raw doc as is and is cleaned on
     * read for the plain-text panels.
     */
    private class TypeDocs(
        val byMethod: Map<String, List<SourceDocValue>>,
        val classDoc: String?,
        val rawByMethod: Map<String, List<SourceDocValue>>,
        val rawClassDoc: String?,
    )

    private val cache = object : LinkedHashMap<String, TypeDocs>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TypeDocs>?) = size > 256
    }

    private fun fromIndex(fqn: String): TypeDocs? {
        synchronized(cache) { cache[fqn]?.let { return it } }
        val ix = index ?: return null
        val java = ix.exact<SourceDocValue>(JavaSourceDocIndex.id, fqn).toList()
        val kotlinRaw = ix.exact<SourceDocValue>(KotlinSourceDocIndex.id, fqn).toList()
        if (java.isEmpty() && kotlinRaw.isEmpty()) return null // not indexed (yet) → let the fallback parse live
        val kotlin = kotlinRaw.map { v -> v.doc?.let { v.copy(doc = KotlinSourceDocIndex.cleanKDoc(it)) } ?: v }
        val values = java + kotlin
        val docs = TypeDocs(
            byMethod = values.filter { it.name.isNotEmpty() }.groupBy { it.name },
            classDoc = values.firstOrNull { it.name.isEmpty() }?.doc,
            rawByMethod = kotlinRaw.filter { it.name.isNotEmpty() }.groupBy { it.name },
            rawClassDoc = kotlinRaw.firstOrNull { it.name.isEmpty() }?.doc,
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

    // Raw markup, for quick-doc's rich rendering: a Kotlin library's KDoc straight from the index, which keeps
    // it; a Java one from the live fallback, which re-parses the source on demand (project dir or
    // -sources.jar), fine for a user-triggered popup.
    override fun methodRaw(declaringFqn: String, methodName: String, arity: Int): String? {
        fromIndex(declaringFqn)?.rawByMethod?.get(methodName)?.let { cands ->
            val v = cands.firstOrNull { it.arity == arity } ?: cands.first()
            v.doc?.let { return it }
        }
        return fallback.methodRaw(declaringFqn, methodName, arity)
    }

    override fun classDocRaw(fqn: String): String? = fromIndex(fqn)?.rawClassDoc ?: fallback.classDocRaw(fqn)
}
