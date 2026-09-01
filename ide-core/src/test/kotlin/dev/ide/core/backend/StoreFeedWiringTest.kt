package dev.ide.core.backend

import dev.ide.store.RemoteCatalog
import dev.ide.store.RemoteStoreItem
import dev.ide.store.StoreCatalogSource
import dev.ide.store.StoreQuery
import dev.ide.store.StoreResult
import dev.ide.store.impl.StoreFeedParser
import dev.ide.ui.backend.UiFeedSection
import dev.ide.ui.backend.UiStoreItem
import dev.ide.ui.backend.UiStoreItemKind
import dev.ide.ui.backend.UiStoreMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The engine→UI mapping, including the **overlay** that makes "bundled = offline fallback, remote
 * overlays" true on the client.
 *
 * The feed documents here are the ones captured from a live `store_explore()` (they live in
 * `store-impl/src/test/resources`), so this exercises the same bytes the app will actually receive.
 */
class StoreFeedWiringTest {

    /**
     * The feed fixtures captured from a live `store_explore()`.
     *
     * Read from `:store-impl`'s test resources **by path** rather than off the classpath: another module's
     * test resources are not a published artifact, and the alternatives were either build plumbing
     * (`java-test-fixtures`) or a second copy of the fixtures that could drift from the one the parser
     * tests use. Tests run with the module directory as the working directory.
     */
    private fun fixture(name: String): String {
        val f = java.io.File("../store-impl/src/test/resources/$name")
        check(f.isFile) { "missing fixture ${f.absolutePath}" }
        return f.readText()
    }

    /** A bundled template whose id matches a remote row, so the overlay has something to merge. */
    private fun bundled(slug: String) = mapOf(
        slug to UiStoreItem(
            id = "sample:$slug",
            kind = UiStoreItemKind.Sample,
            title = "Bundled title",
            summary = "Bundled summary",
            category = "Java",
            iconId = "java",
            templateId = slug,
            previewKey = "sample-snake",
            accentColor = 0xFF112233L,
        ),
    )

    @Test
    fun mapsModeAndStoreState() {
        val feed = assertNotNull(StoreFeedParser.parse(fixture("explore-sparse.json")))
        val ui = StoreFeedMapper.toUi(feed, emptyMap())
        assertEquals(UiStoreMode.SPARSE, ui.mode)
        assertTrue(ui.state.publishedProjectCount > 0)
        assertEquals(feed.state.thresholds.charts, ui.state.thresholds.charts)
        assertTrue(ui.state.acceptingSubmissions)
    }

    @Test
    fun mapsEveryPopulatedSectionType() {
        val feed = assertNotNull(StoreFeedParser.parse(fixture("explore-populated.json")))
        val ui = StoreFeedMapper.toUi(feed, emptyMap())
        // Nothing may be silently dropped in the mapping — the parser already did that filtering.
        assertEquals(feed.sections.size, ui.sections.size)
        val kinds = ui.sections.map { it::class.simpleName }
        assertTrue("Charts" in kinds, kinds.toString())
        assertTrue("Collections" in kinds, kinds.toString())
        assertTrue("Personalized" in kinds, kinds.toString())
        assertTrue("Spotlight" in kinds, kinds.toString())
    }

    @Test
    fun chartMovementSurvivesTheMapping() {
        val feed = assertNotNull(StoreFeedParser.parse(fixture("explore-populated.json")))
        val ui = StoreFeedMapper.toUi(feed, emptyMap())
        val charts = ui.sections.filterIsInstance<UiFeedSection.Charts>().single()
        val source = feed.sections.filterIsInstance<dev.ide.store.StoreSection.Charts>().single()
        assertEquals(source.tabs.map { it.key }, charts.tabs.map { it.key })
        val srcEntries = source.tabs.first().entries
        val uiEntries = charts.tabs.first().entries
        assertEquals(srcEntries.map { it.rank }, uiEntries.map { it.rank })
        // previousRank must stay nullable through the mapping: null means "new entrant".
        assertEquals(srcEntries.map { it.previousRank }, uiEntries.map { it.previousRank })
    }

    /** The overlay's whole point: remote presentation wins, but it still creates locally. */
    @Test
    fun overlayKeepsRemoteTextAndBundledTemplateId() {
        val feed = assertNotNull(StoreFeedParser.parse(fixture("explore-sparse.json")))
        val remoteSlug = feed.allItems.first { it.templateId == null }.id
        val ui = StoreFeedMapper.toUi(feed, bundled(remoteSlug))
        val merged = ui.sections.filterIsInstance<UiFeedSection.Catalogue>()
            .single().items.single { it.id == remoteSlug }
        val remote = feed.allItems.single { it.id == remoteSlug }

        assertEquals(remote.title, merged.title, "remote presentation must win")
        assertEquals(remoteSlug, merged.templateId, "an overlaid item must still create locally")
        assertEquals("sample-snake", merged.previewKey, "bundled screenshots are invisible to the server")
        assertTrue(merged.available, "a locally-creatable item is available even with no payload")
    }

    /** An item the device does not have and the server gave no payload for cannot be installed. */
    @Test
    fun remoteItemWithNoPayloadAndNoTemplateIsUnavailable() {
        val json = """
        {"version":3,"mode":"sparse","storeState":{"publishedProjectCount":1},
         "sections":[{"type":"catalogue","id":"everything","title":"All","order":"recency","items":[
           {"id":"orphan","kind":"community","title":"Orphan","summary":"s","category":"java"}
         ]}]}
        """.trimIndent()
        val ui = StoreFeedMapper.toUi(assertNotNull(StoreFeedParser.parse(json)), emptyMap())
        val item = ui.sections.filterIsInstance<UiFeedSection.Catalogue>().single().items.single()
        assertTrue(!item.available)
        assertNull(item.templateId)
    }

    /** Unrated must stay unrated: the UI's sentinel is -1f, and 0f would render as "rated badly". */
    @Test
    fun unratedRemoteItemMapsToTheNegativeSentinelNotZero() {
        val feed = assertNotNull(StoreFeedParser.parse(fixture("explore-sparse.json")))
        val ui = StoreFeedMapper.toUi(feed, emptyMap())
        val items = ui.sections.filterIsInstance<UiFeedSection.Catalogue>().single().items
        val unrated = items.filter { it.ratingCount == 0 }
        assertTrue(unrated.isNotEmpty(), "the fixture should contain an unrated project")
        unrated.forEach { assertEquals(-1f, it.rating, "${it.id} must use the -1 sentinel, not 0") }
    }

    @Test
    fun blurbAndPublishedAtReachTheUiModel() {
        val feed = assertNotNull(StoreFeedParser.parse(fixture("explore-sparse.json")))
        val ui = StoreFeedMapper.toUi(feed, emptyMap())
        val items = ui.sections.filterIsInstance<UiFeedSection.Catalogue>().single().items
        assertTrue(items.any { !it.blurb.isNullOrBlank() }, "the sparse card needs a blurb")
        assertTrue(items.all { !it.publishedAt.isNullOrBlank() }, "the sparse card needs publishedAt")
    }

    @Test
    fun accentHexParsesToArgbAndFallsBackToBundled() {
        val json = """
        {"version":3,"mode":"sparse","storeState":{"publishedProjectCount":2},
         "sections":[{"type":"catalogue","id":"everything","title":"All","order":"recency","items":[
           {"id":"a","kind":"sample","title":"A","summary":"s","category":"java","accent":"#3FBDD9"},
           {"id":"b","kind":"sample","title":"B","summary":"s","category":"java","accent":"nonsense"}
         ]}]}
        """.trimIndent()
        val ui = StoreFeedMapper.toUi(assertNotNull(StoreFeedParser.parse(json)), bundled("b"))
        val items = ui.sections.filterIsInstance<UiFeedSection.Catalogue>().single().items
        assertEquals(0xFF3FBDD9L, items.single { it.id == "a" }.accentColor)
        // A malformed accent falls through to the bundled item's rather than becoming a broken colour.
        assertEquals(0xFF112233L, items.single { it.id == "b" }.accentColor)
    }

    /** An unconfigured source must never claim the store is empty — that is a different statement. */
    @Test
    fun unconfiguredSourceOffersNoFeed() {
        val src = StoreCatalogSource.Unconfigured
        assertTrue(!src.configured())
        assertTrue(src.feedDocument(null) is StoreResult.Unavailable)
    }

    /** A source that is configured but offline also yields no feed, not an empty one. */
    @Test
    fun offlineSourceYieldsUnavailableNotAnEmptyFeed() {
        val offline = object : StoreCatalogSource {
            override fun configured() = true
            override fun catalog(appBuild: Int) = StoreResult.Unavailable<RemoteCatalog>("offline")
            override fun search(query: StoreQuery, appBuild: Int) =
                StoreResult.Unavailable<List<RemoteStoreItem>>("offline")
            override fun feedDocument(seedSlug: String?) =
                StoreResult.Unavailable<String>("offline")
            override fun recordInstall(slug: String, installId: String) = Unit
        }
        assertTrue(offline.feedDocument(null) is StoreResult.Unavailable)
    }
}
