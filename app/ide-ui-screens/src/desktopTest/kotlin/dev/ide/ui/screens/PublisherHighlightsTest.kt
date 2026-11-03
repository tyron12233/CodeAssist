package dev.ide.ui.screens

import dev.ide.ui.backend.UiStoreItem
import dev.ide.ui.backend.UiStoreItemKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PublisherHighlightsTest {

    private fun item(
        id: String,
        installs: Int = 0,
        likes: Int = 0,
        rating: Float = -1f,
        ratingCount: Int = 0,
        publishedAt: String? = null,
        category: String = "games",
        tags: List<String> = emptyList(),
    ) = UiStoreItem(
        id = id, kind = UiStoreItemKind.Community, title = id, summary = "", category = category, tags = tags,
        installs = installs, likes = likes, rating = rating, ratingCount = ratingCount, publishedAt = publishedAt,
    )

    @Test
    fun eachAwardGoesToItsOwnWinner() {
        val items = listOf(
            item("libgdx", installs = 693, likes = 2, rating = 4.7f, ratingCount = 12, publishedAt = "2026-08-01T00:00:00Z"),
            item("colorpicker", installs = 200, likes = 6, rating = 4.5f, ratingCount = 4, publishedAt = "2026-09-01T00:00:00Z"),
            item("strings", installs = 150, likes = 1, rating = 4.9f, ratingCount = 5, publishedAt = "2026-10-20T10:15:00.123456+00:00"),
        )
        val awards = highlightsOf(items).associate { it.item.id to it.awards }
        assertEquals(listOf(ProfileAward.MostInstalled), awards["libgdx"])
        assertEquals(listOf(ProfileAward.MostLiked), awards["colorpicker"])
        // Postgres writes microseconds and an offset; the newest has to parse from exactly that.
        assertEquals(listOf(ProfileAward.TopRated, ProfileAward.Newest), awards["strings"])
    }

    @Test
    fun oneProjectWinningSeveralAwardsIsOneCard() {
        val items = listOf(
            item("big", installs = 900, likes = 9, rating = 4.8f, ratingCount = 30),
            item("small", installs = 20, likes = 1, rating = 4.0f, ratingCount = 3),
        )
        val highlights = highlightsOf(items)
        assertEquals(1, highlights.size)
        assertEquals(listOf(ProfileAward.MostInstalled, ProfileAward.MostLiked, ProfileAward.TopRated), highlights[0].awards)
    }

    @Test
    fun tiesThinDataAndSingleProjectsNameNobody() {
        assertTrue(highlightsOf(listOf(item("only", installs = 900, likes = 9))).isEmpty())
        val tied = highlightsOf(listOf(item("a", likes = 3), item("b", likes = 3)))
        assertTrue(tied.none { ProfileAward.MostLiked in it.awards })
        // One five-star rating is not enough to be called top rated.
        val thin = highlightsOf(listOf(item("a", rating = 5f, ratingCount = 1), item("b", rating = 4f, ratingCount = 2)))
        assertTrue(thin.none { ProfileAward.TopRated in it.awards })
        // Under ten installs the store says "New", so nobody is "most installed".
        assertTrue(highlightsOf(listOf(item("a", installs = 8), item("b", installs = 3))).isEmpty())
    }

    @Test
    fun equalAverageGoesToTheMoreRatedProject() {
        val top = highlightsOf(
            listOf(item("few", rating = 5f, ratingCount = 3), item("many", rating = 5f, ratingCount = 40)),
        ).single { ProfileAward.TopRated in it.awards }
        assertEquals("many", top.item.id)
    }

    @Test
    fun sortsPutUnknownFiguresLast() {
        val items = listOf(
            item("unrated", likes = 5),
            item("rated", rating = 4.2f, ratingCount = 3, publishedAt = "2026-01-01T00:00:00Z"),
            item("best", rating = 4.9f, ratingCount = 3, publishedAt = "2026-02-01T00:00:00Z"),
        )
        assertEquals(listOf("best", "rated", "unrated"), sortedForProfile(items, ProfileSort.TopRated).map { it.id })
        assertEquals(listOf("best", "rated", "unrated"), sortedForProfile(items, ProfileSort.Newest).map { it.id })
        assertEquals("unrated", sortedForProfile(items, ProfileSort.MostLiked).first().id)
        assertEquals(items, sortedForProfile(items, ProfileSort.Popular))
    }

    @Test
    fun sortsThatChangeNothingAreNotOffered() {
        assertTrue(usefulSorts(listOf(item("a"))).isEmpty())
        assertTrue(usefulSorts(listOf(item("a"), item("b"))).isEmpty())
        val offered = usefulSorts(listOf(item("a"), item("b", likes = 2)))
        assertEquals(listOf(ProfileSort.Popular, ProfileSort.MostLiked), offered)
    }

    @Test
    fun groupsSplitOnlyWhenBothKindsArePresent() {
        val app = item("app")
        val plugin = item("plugin", category = "build")
        val tagged = item("tagged", tags = listOf("plugin"))
        val groups = groupedForProfile(listOf(app, plugin, tagged))
        assertEquals(listOf(ProfileGroup.Projects, ProfileGroup.Plugins), groups.map { it.first })
        assertEquals(listOf("plugin", "tagged"), groups[1].second.map { it.id })
        assertEquals(listOf(null), groupedForProfile(listOf(plugin, tagged)).map { it.first })
    }

    @Test
    fun compactNumbersKeepWhatTheySay() {
        val n: (Int) -> String = { dev.ide.ui.components.compactNumber(it) }
        assertEquals("1024", n(1_024))
        assertEquals("9999", n(9_999))
        assertEquals("12K", n(12_000))
        assertEquals("12.3K", n(12_345))
        assertEquals("123K", n(123_456))
        assertEquals("1M", n(1_000_000))
        assertEquals("4.5M", n(4_512_000))
        assertNotNull(isoMillis("2026-09-02T12:34:56.123456+00:00"))
    }
}
