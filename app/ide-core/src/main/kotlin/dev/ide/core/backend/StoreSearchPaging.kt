package dev.ide.core.backend

import dev.ide.store.RemoteStoreItem
import dev.ide.store.StoreResult
import dev.ide.ui.backend.UiStoreItem
import dev.ide.ui.backend.UiStoreSearchPage

/**
 * How a page of search results is assembled from the two sources a search has.
 *
 * Pulled out of [StoreBackend] because this is the part with rules in it, and the rules are all about
 * what a second page may contain: the device's bundled templates are a fixed local list with no offset of
 * their own, while the store's catalogue is a paged result set. Mixing them on every page would repeat the
 * bundled half forever; pretending the offset covers both would skip rows.
 */
internal object StoreSearchPaging {

    /** A page when there is no remote store: the bundled list is the whole result, paged locally. */
    fun local(local: List<UiStoreItem>, offset: Int, limit: Int): UiStoreSearchPage = UiStoreSearchPage(
        items = local.drop(offset).take(limit),
        hasMore = local.size > offset + limit,
    )

    /**
     * A page from the store, with the bundled templates ahead of it on the first page only.
     *
     * A bundled template the store also publishes is dropped from the bundled half when this page carries
     * its remote row: the remote row IS that template with the store's metadata over it, and showing both
     * would read as two projects with the same name.
     *
     * A failed page keeps whatever the device can answer by itself and carries the reason. That matters
     * most offline, where the bundled templates are the only projects that could be installed anyway.
     */
    fun page(
        local: List<UiStoreItem>,
        remote: StoreResult<List<RemoteStoreItem>>,
        bundled: Map<String, UiStoreItem>,
        offset: Int,
        limit: Int,
    ): UiStoreSearchPage = when (remote) {
        is StoreResult.Ok -> {
            val overlaid = remote.value.mapTo(HashSet()) { it.id }
            val head =
                if (offset == 0) local.filterNot { templateIdOf(it) in overlaid } else emptyList()
            UiStoreSearchPage(
                items = head + remote.value.map { StoreFeedMapper.itemToUi(it, bundled) },
                // A short page is the end of the results: the backend has no "more" flag of its own.
                hasMore = remote.value.size >= limit,
            )
        }

        is StoreResult.Unavailable -> UiStoreSearchPage(
            items = if (offset == 0) local else emptyList(),
            error = remote.reason,
        )

        is StoreResult.Failed -> UiStoreSearchPage(
            items = if (offset == 0) local else emptyList(),
            error = remote.message,
        )
    }

    /** The template id behind a bundled item's `template:`/`sample:` prefixed id, which is its overlay key. */
    private fun templateIdOf(item: UiStoreItem): String = item.id.substringAfter(':')
}
