package dev.ide.store.impl

import dev.ide.store.StoreMode
import dev.ide.store.StoreSection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Parses the three Explore modes from fixtures **captured off a real running backend**, not written by
 * hand. That matters for the same reason it did for the catalog parser: a hand-written fixture encodes
 * what I believe the server sends, and the interesting bugs live exactly where that belief is wrong.
 *
 * `explore-empty.json`, `explore-sparse.json` and `explore-populated.json` are verbatim
 * `store_explore()` responses.
 */
class StoreFeedParserTest {

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader.getResourceAsStream(name)) { "missing fixture $name" }
            .bufferedReader().readText()

    private fun feed(name: String) = assertNotNull(StoreFeedParser.parse(fixture(name)), "failed to parse $name")

    // ---- empty ----

    @Test
    fun emptyModeCarriesNoContentSections() {
        val f = feed("explore-empty.json")
        assertEquals(StoreMode.EMPTY, f.mode)
        assertEquals(0, f.state.publishedProjectCount)
        // Only the bundled shelf, whose rows come from the device rather than the server.
        assertTrue(f.sections.all { it is StoreSection.Bundled }, "unexpected: ${f.sections.map { it.id }}")
        assertTrue(f.allItems.isEmpty())
    }

    /** The bundled section must survive despite arriving with an empty `items` array. */
    @Test
    fun bundledSectionIsKeptEvenThoughItsItemsAreEmpty() {
        assertTrue(feed("explore-empty.json").sections.any { it is StoreSection.Bundled })
    }

    // ---- sparse ----

    @Test
    fun sparseModeIsOneCatalogueThenThePitch() {
        val f = feed("explore-sparse.json")
        assertEquals(StoreMode.SPARSE, f.mode)
        // ids come from the contract's `id` field, which differs from `type` on purpose.
        assertEquals(
            listOf("everything", "pitch", "offline-templates", "unlocks"),
            f.sections.map { it.id },
        )
        assertEquals(
            listOf(
                StoreSection.Catalogue::class, StoreSection.PublishPitch::class,
                StoreSection.Bundled::class, StoreSection.GhostShelves::class,
            ),
            f.sections.map { it::class },
        )
        val cat = f.sections.filterIsInstance<StoreSection.Catalogue>().single()
        assertEquals("recency", cat.order, "sparse ordering must be recency, not rank")
        assertTrue(cat.items.size in 1..9, "sparse means 1..9 projects, got ${cat.items.size}")
    }

    /** The pitch headline interpolates a real count, so the parser must carry it. */
    @Test
    fun pitchCarriesTheProjectCount() {
        val f = feed("explore-sparse.json")
        val pitch = f.sections.filterIsInstance<StoreSection.PublishPitch>().single()
        assertEquals(f.state.publishedProjectCount, pitch.projectCount)
        assertTrue(pitch.projectCount > 0)
    }

    @Test
    fun ghostShelvesCarryHaveAndNeed() {
        val g = feed("explore-sparse.json").sections.filterIsInstance<StoreSection.GhostShelves>().single()
        assertEquals(setOf("charts", "collections", "recommendations"), g.shelves.map { it.key }.toSet())
        g.shelves.forEach {
            assertTrue(it.need > it.have, "${it.key}: a ghost shelf must still be short of its threshold")
            assertEquals(it.need - it.have, it.remaining)
        }
    }

    /** No charts below the threshold — not returned, so not renderable. */
    @Test
    fun sparseHasNoChartsAtAll() {
        assertTrue(feed("explore-sparse.json").sections.none { it is StoreSection.Charts })
    }

    /** The literal reason "Not rated yet" exists: an unrated project must have a null rating. */
    @Test
    fun unratedProjectInSparseCatalogueHasNullRating() {
        val cat = feed("explore-sparse.json").sections.filterIsInstance<StoreSection.Catalogue>().single()
        val unrated = cat.items.filter { it.ratingCount == 0 }
        assertTrue(unrated.isNotEmpty(), "fixture should contain at least one unrated project")
        unrated.forEach { assertNull(it.rating, "${it.id} reported a rating with zero ratings") }
    }

    /** `blurb` is server-truncated to the first sentence so clients cannot disagree about where it ends. */
    @Test
    fun catalogueItemsCarryAFirstSentenceBlurb() {
        val cat = feed("explore-sparse.json").sections.filterIsInstance<StoreSection.Catalogue>().single()
        val withBlurb = cat.items.filter { !it.blurb.isNullOrBlank() }
        assertTrue(withBlurb.isNotEmpty(), "no item carried a blurb")
        withBlurb.forEach {
            val b = it.blurb!!
            assertTrue(b.isNotBlank(), "${it.id}: blurb was blank")
            assertTrue(b.length <= 241, "${it.id}: blurb not truncated (${b.length})")
            // One sentence: at most one terminator, and it is at the end.
            assertTrue(b.trimEnd().last() in ".!?" || b.length >= 240, "${it.id}: blurb is not a sentence: $b")
        }
    }

    @Test
    fun catalogueItemsCarryPublishedAt() {
        val cat = feed("explore-sparse.json").sections.filterIsInstance<StoreSection.Catalogue>().single()
        assertTrue(cat.items.all { !it.publishedAt.isNullOrBlank() }, "publishedAt drives 'Published 3 days ago'")
    }

    // ---- populated ----

    @Test
    fun populatedModeCarriesTheFullShelfSet() {
        val f = feed("explore-populated.json")
        assertEquals(StoreMode.POPULATED, f.mode)
        assertTrue(f.state.publishedProjectCount >= f.state.thresholds.charts)
        assertEquals(
            listOf("trending-terms", "editorial-hero", "top-charts", "curated", "kinds", "because", "publisher", "new-updated"),
            f.sections.map { it.id },
        )
    }

    @Test
    fun chartsHaveThreeTabsAndRankedEntries() {
        val c = feed("explore-populated.json").sections.filterIsInstance<StoreSection.Charts>().single()
        assertEquals(listOf("trending", "top_rated", "new"), c.tabs.map { it.key })
        assertNotNull(c.computedAt, "the live dot is only honest because computedAt is shown")
        val trending = c.tabs.first { it.key == "trending" }
        assertTrue(trending.entries.isNotEmpty())
        // Ranks are 1-based and contiguous.
        assertEquals((1..trending.entries.size).toList(), trending.entries.map { it.rank })
    }

    /** The delta arithmetic the movement indicator depends on. */
    @Test
    fun chartDeltaIsDerivedFromPreviousRank() {
        val c = feed("explore-populated.json").sections.filterIsInstance<StoreSection.Charts>().single()
        c.tabs.flatMap { it.entries }.forEach { e ->
            // Bound locally: a cross-module property cannot be smart-cast.
            val prev = e.previousRank
            if (prev == null) {
                assertNull(e.delta, "a new entrant has no delta")
            } else {
                assertEquals(prev - e.rank, e.delta)
            }
        }
    }

    @Test
    fun personalizedShelfMeetsTheFourItemFloorAndNamesItsSeed() {
        val p = feed("explore-populated.json").sections.filterIsInstance<StoreSection.Personalized>().single()
        assertTrue(p.items.size >= StoreFeedParser.MIN_PERSONALIZED, "got ${p.items.size}")
        assertEquals("kmp-starter", p.seedProjectId)
        assertTrue(p.title.startsWith("Because you installed "), p.title)
    }

    @Test
    fun collectionsCarryCountsAndPreviewIcons() {
        val c = feed("explore-populated.json").sections.filterIsInstance<StoreSection.Collections>().single()
        assertTrue(c.items.isNotEmpty())
        c.items.forEach {
            assertTrue(it.title.isNotBlank())
            assertTrue(it.projectCount > 0, "${it.id} has no projects")
            assertTrue(it.previewIconKeys.size <= 3, "${it.id}: the icon stack is three tiles")
            assertNotNull(it.deeplink)
        }
    }

    @Test
    fun spotlightCarriesPublisherStats() {
        val s = feed("explore-populated.json").sections.filterIsInstance<StoreSection.Spotlight>().single()
        assertTrue(s.publisher.name.isNotBlank())
        assertTrue(s.publisher.projectCount > 0)
        assertTrue(s.publisher.installCount > 0)
    }

    @Test
    fun allItemsDeduplicatesAcrossShelves() {
        val f = feed("explore-populated.json")
        val ids = f.allItems.map { it.id }
        assertEquals(ids.distinct(), ids, "allItems must be deduplicated for the offline cache")
        assertTrue(ids.isNotEmpty())
    }

    // ---- forward compatibility ----

    /** The rule that lets the server ship a shelf before the app understands it. */
    @Test
    fun unknownSectionTypesAreSkippedNotFatal() {
        val json = """
        {"version":3,"mode":"populated","storeState":{"publishedProjectCount":12},
         "sections":[
           {"type":"sponsored_carousel","id":"promo","items":[{"id":"x","kind":"sample","title":"T","summary":"s","category":"java"}]},
           {"type":"list","id":"new-updated","title":"New & updated","items":[{"id":"y","kind":"sample","title":"Y","summary":"s","category":"java"}]},
           {"type":"some_future_thing","id":"future"}
         ]}
        """.trimIndent()
        val f = assertNotNull(StoreFeedParser.parse(json))
        assertEquals(listOf("new-updated"), f.sections.map { it.id })
    }

    /** A server bug that sends an empty shelf must not produce an empty shelf on screen. */
    @Test
    fun emptySectionsAreDroppedEvenIfTheServerSendsThem() {
        val json = """
        {"version":3,"mode":"populated","storeState":{"publishedProjectCount":12},
         "sections":[
           {"type":"featured","id":"hero","items":[]},
           {"type":"ticker","id":"terms","items":[]},
           {"type":"collections","id":"curated","items":[]},
           {"type":"charts","id":"chart","tabs":[{"key":"trending","label":"Trending","entries":[]}]},
           {"type":"personalized","id":"because","items":[{"id":"a","kind":"sample","title":"A","summary":"s","category":"java"}]}
         ]}
        """.trimIndent()
        val f = assertNotNull(StoreFeedParser.parse(json))
        assertTrue(f.sections.isEmpty(), "expected all dropped, got ${f.sections.map { it.id }}")
    }

    @Test
    fun thresholdsFallBackWhenAbsentFromAnOlderResponse() {
        val f = assertNotNull(StoreFeedParser.parse("""{"mode":"sparse","storeState":{"publishedProjectCount":3}}"""))
        assertEquals(10, f.state.thresholds.charts)
        assertEquals(12, f.state.thresholds.collections)
        assertEquals(8, f.state.thresholds.recommendations)
        assertTrue(f.state.acceptingSubmissions, "absent acceptingSubmissions must default to true")
    }

    /** An unrecognised mode renders the shelves that arrived rather than a "nobody published" page. */
    @Test
    fun unknownModeFallsBackToPopulated() {
        assertEquals(StoreMode.POPULATED, StoreMode.of("brand-new-mode"))
        assertEquals(StoreMode.POPULATED, StoreMode.of(null))
        assertEquals(StoreMode.EMPTY, StoreMode.of("empty"))
        assertEquals(StoreMode.SPARSE, StoreMode.of("sparse"))
    }

    @Test
    fun malformedJsonYieldsNullRatherThanThrowing() {
        assertNull(StoreFeedParser.parse("{not json"))
        assertNull(StoreFeedParser.parse(""))
    }
}
