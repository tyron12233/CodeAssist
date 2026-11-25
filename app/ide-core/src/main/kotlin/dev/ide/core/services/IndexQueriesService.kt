package dev.ide.core.services

import dev.ide.index.Hit
import dev.ide.index.IndexId
import dev.ide.index.IndexQueries
import dev.ide.index.IndexService
import dev.ide.index.IndexStatus

/**
 * What a plugin gets for [dev.ide.index.INDEX_QUERIES]: the workspace's live index, asked questions and nothing
 * else.
 *
 * A view rather than the [IndexService] itself, because that instance also rebuilds and invalidates. A plugin
 * holding it could downcast and drop the cache the editor is querying, and the library half of that cache is
 * shared by every project on the device.
 */
internal class IndexQueriesService(private val index: IndexService) : IndexQueries {

    override fun <V : Any> exact(id: IndexId, key: String): Sequence<V> = index.exact(id, key)

    override fun <V : Any> prefix(id: IndexId, prefix: String, limit: Int): Sequence<Hit<V>> =
        index.prefix(id, prefix, limit)

    override fun <V : Any> fuzzy(id: IndexId, pattern: String, limit: Int): Sequence<Hit<V>> =
        index.fuzzy(id, pattern, limit)

    override fun filePath(id: Int): String? = index.filePath(id)

    override val status: IndexStatus get() = index.status
}
