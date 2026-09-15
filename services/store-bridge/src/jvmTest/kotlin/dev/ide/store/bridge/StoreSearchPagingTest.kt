package dev.ide.store.bridge

import dev.ide.store.RemoteItemKind
import dev.ide.store.RemoteStoreItem
import dev.ide.store.StoreResult
import dev.ide.ui.backend.UiStoreItem
import dev.ide.ui.backend.UiStoreItemKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * How a page of search results is put together, which is where every paging rule lives.
 *
 * The cases that matter are all about the second page: the store's catalogue pages and the device's
 * bundled templates do not, and the app spent its whole life so far searching only the latter.
 */
class StoreSearchPagingTest {

    private fun remote(id: String) = RemoteStoreItem(
        id = id,
        kind = RemoteItemKind.COMMUNITY,
        title = "Remote $id",
        summary = "summary",
        category = "kotlin",
    )

    private fun bundledItem(templateId: String) = UiStoreItem(
        id = "template:$templateId",
        kind = UiStoreItemKind.Template,
        title = "Bundled $templateId",
        summary = "summary",
        category = "Kotlin",
        templateId = templateId,
    )

    @Test
    fun bundledTemplatesRideTheFirstPageOnly() {
        val local = listOf(bundledItem("compose-app"), bundledItem("kotlin-console"))
        val first = StoreSearchPaging.page(
            local = local,
            remote = StoreResult.Ok(listOf(remote("a"), remote("b"))),
            bundled = emptyMap(),
            offset = 0,
            limit = 30,
        )
        assertEquals(
            listOf("template:compose-app", "template:kotlin-console", "a", "b"),
            first.items.map { it.id },
            "the device's own templates lead the first page",
        )

        val second = StoreSearchPaging.page(
            local = local,
            remote = StoreResult.Ok(listOf(remote("c"))),
            bundled = emptyMap(),
            offset = 30,
            limit = 30,
        )
        assertEquals(listOf("c"), second.items.map { it.id }, "and appear on no later page")
    }

    @Test
    fun aPublishedTemplateIsNotAlsoListedAsItsBundledCopy() {
        // The remote row and the bundled template are the same project: one card, not two.
        val page = StoreSearchPaging.page(
            local = listOf(bundledItem("compose-app")),
            remote = StoreResult.Ok(listOf(remote("compose-app"))),
            bundled = mapOf("compose-app" to bundledItem("compose-app")),
            offset = 0,
            limit = 30,
        )
        assertEquals(listOf("compose-app"), page.items.map { it.id })
        assertEquals(
            "compose-app",
            page.items.single().templateId,
            "and it still creates locally, because the overlay kept the template id",
        )
    }

    @Test
    fun aFullPageMeansThereMayBeMoreAndAShortOneIsTheEnd() {
        val full = StoreSearchPaging.page(
            local = emptyList(),
            remote = StoreResult.Ok((1..4).map { remote("r$it") }),
            bundled = emptyMap(),
            offset = 0,
            limit = 4,
        )
        assertTrue(full.hasMore, "a page that came back full may have more behind it")

        val short = StoreSearchPaging.page(
            local = emptyList(),
            remote = StoreResult.Ok((1..3).map { remote("r$it") }),
            bundled = emptyMap(),
            offset = 0,
            limit = 4,
        )
        assertFalse(short.hasMore, "a short page is the last one")
    }

    @Test
    fun offlineTheFirstPageIsStillWhatTheDeviceHas() {
        val local = listOf(bundledItem("compose-app"))
        val first = StoreSearchPaging.page(
            local = local,
            remote = StoreResult.Unavailable("Network unavailable"),
            bundled = emptyMap(),
            offset = 0,
            limit = 30,
        )
        assertEquals(listOf("template:compose-app"), first.items.map { it.id })
        assertEquals("Network unavailable", first.error, "and the screen can say why the rest is missing")
        assertFalse(first.hasMore, "there is nothing to scroll to")

        val second = StoreSearchPaging.page(
            local = local,
            remote = StoreResult.Unavailable("Network unavailable"),
            bundled = emptyMap(),
            offset = 30,
            limit = 30,
        )
        assertTrue(second.items.isEmpty(), "a failed later page must not repeat the bundled list")
    }

    @Test
    fun aRefusedSearchCarriesTheBackendsOwnMessage() {
        val page = StoreSearchPaging.page(
            local = emptyList(),
            remote = StoreResult.Failed("Store rejected the request", 400),
            bundled = emptyMap(),
            offset = 0,
            limit = 30,
        )
        assertEquals("Store rejected the request", page.error)
    }

    @Test
    fun withNoRemoteStoreTheBundledListPagesLocally() {
        val local = (1..5).map { bundledItem("t$it") }
        val first = StoreSearchPaging.local(local, offset = 0, limit = 2)
        assertEquals(listOf("template:t1", "template:t2"), first.items.map { it.id })
        assertTrue(first.hasMore)

        val last = StoreSearchPaging.local(local, offset = 4, limit = 2)
        assertEquals(listOf("template:t5"), last.items.map { it.id })
        assertFalse(last.hasMore, "the final page does not offer another")
    }
}
