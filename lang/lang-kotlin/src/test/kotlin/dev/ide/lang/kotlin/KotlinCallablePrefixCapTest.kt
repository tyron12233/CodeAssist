package dev.ide.lang.kotlin

import dev.ide.index.Hit
import dev.ide.index.IndexId
import dev.ide.index.IndexScope
import dev.ide.index.IndexService
import dev.ide.index.IndexStatus
import dev.ide.lang.kotlin.index.CallableShape
import dev.ide.lang.kotlin.index.KotlinCallableIndex
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import dev.ide.lang.resolve.SymbolKind
import dev.ide.platform.Disposable
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Regression (the reported bug): typing a callable whose name CONTAINS a capital — `listOf`, once the caret
 * passes the `O` — turns the query into a camel-"hump" query, which used to collapse the index pushdown to the
 * first character (`l`). On a large classpath the first-char query's [CALLABLE_QUERY_LIMIT] result cap could
 * truncate `listOf` out before it was reached, so the popup dropped it (intermittently, since it only bit once
 * the query broadened). The fix also pushes down the FULL prefix, a narrow uncapped query that rescues the
 * typed-in-full callable. Reproduced deterministically with an index whose first-char query is capped and
 * excludes `listOf`.
 */
class KotlinCallablePrefixCapTest {

    private fun shape(name: String) = CallableShape(
        name = name, kind = SymbolKind.METHOD, receiverFqn = null, signature = "()",
        packageName = "kotlin.collections", receiverTypeParam = null, typeParameters = emptyList(),
        returnType = null, paramTypes = emptyList(), receiverTypeArgs = emptyList(),
        declaringClassFqn = "kotlin.collections.CollectionsKt", paramNames = emptyList(),
        isComposable = false, isInline = false, isInfix = false, isSuspend = false,
    )

    @Test
    fun humpShapedCallableSurvivesTheFirstCharQueryCap() {
        val listOf = shape("listOf")
        @Suppress("UNCHECKED_CAST")
        val idx = object : IndexService {
            override fun <V : Any> exact(id: IndexId, key: String): Sequence<V> = emptySequence()
            override fun <V : Any> prefix(id: IndexId, prefix: String, limit: Int): Sequence<Hit<V>> {
                if (id != KotlinCallableIndex.id) return emptySequence()
                return when (prefix) {
                    // The first-char query is CAPPED to `limit` fillers and `listOf` is beyond the cap — exactly
                    // the large-classpath case that dropped it (the `la…` fillers don't match `listOf`, so the
                    // old first-char-only path returned nothing for this prefix).
                    KotlinCallableIndex.topKey("l") ->
                        (0 until limit).asSequence().map { Hit(KotlinCallableIndex.topKey("la$it"), shape("la$it") as V, 0) }
                    // The full-prefix query the fix adds is narrow and returns `listOf`.
                    KotlinCallableIndex.topKey("listOf") ->
                        sequenceOf(Hit(KotlinCallableIndex.topKey("listOf"), listOf as V, 0))
                    else -> emptySequence()
                }
            }
            override fun <V : Any> fuzzy(id: IndexId, pattern: String, limit: Int): Sequence<Hit<V>> = emptySequence()
            override suspend fun ensureUpToDate(scope: IndexScope) {}
            override suspend fun reindexSource(path: Path, text: String) {}
            override val status = IndexStatus(ready = true)
            override fun observeStatus(listener: (IndexStatus) -> Unit) = Disposable { }
        }
        val service = KotlinSymbolService(sourceRoots = emptyList(), classpathJars = emptyList(), index = idx)
        val names = service.topLevelCallables("listOf").map { it.name }
        assertTrue(
            "listOf" in names,
            "a hump-shaped prefix must not drop the plain-prefix callable past the first-char query cap; got ${names.take(10)}",
        )
    }
}
