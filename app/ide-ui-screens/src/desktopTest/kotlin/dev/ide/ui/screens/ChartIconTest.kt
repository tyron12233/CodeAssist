package dev.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import dev.ide.ui.backend.UiChartEntry
import dev.ide.ui.backend.UiChartTab
import dev.ide.ui.backend.UiFeedSection
import dev.ide.ui.backend.UiShelfLayout
import dev.ide.ui.backend.UiStoreFeed
import dev.ide.ui.backend.UiStoreItem
import dev.ide.ui.backend.UiStoreItemKind
import dev.ide.ui.backend.UiStoreMode
import dev.ide.ui.backend.UiStoreState
import dev.ide.ui.theme.CodeAssistTheme
import org.jetbrains.skia.Bitmap
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The chart rows draw a project's OWN app icon, not the glyph tile.
 *
 * Every other shelf has done this since icons were published; the chart row hardcoded `TemplateIcon`,
 * so the store's most prominent section was the one place a published icon never appeared. The feed
 * always carried it (`store_item_json` emits `iconPath` for chart entries too), which is why this is a
 * render test rather than a parser one: nothing about the payload said anything was wrong.
 *
 * Asserted on pixels because that is the claim. [ShotBackend] answers every media request with one flat
 * blue image, so blue anywhere in the frame means an icon was fetched and drawn, and a chart that fell
 * back to its tonal tile has none.
 */
class ChartIconTest {

    private fun item(id: String, title: String, withIcon: Boolean) = UiStoreItem(
        id = id,
        kind = UiStoreItemKind.Community,
        title = title,
        summary = "A short summary line",
        category = "Kotlin",
        iconId = "hub",
        language = "Kotlin",
        author = "Nordlys Labs",
        installs = 21_004,
        version = "1.0.0",
        // What a published listing carries and a bundled template does not.
        iconPath = if (withIcon) "$id/1.0.0/icon.png" else null,
    )

    private fun items(withIcon: Boolean) = listOf(
        item("kmp-starter", "Compose Multiplatform Starter", withIcon),
        item("ktor-service", "Ktor + Exposed Service", withIcon),
        item("android-clean", "Android Clean Architecture", withIcon),
    )

    /** A feed of nothing but the charts, so any blue in the frame came from a chart row. */
    private fun chartsFeed(withIcon: Boolean) = feedOf(
        UiFeedSection.Charts(
            id = "top-charts",
            tabs = listOf(
                UiChartTab(
                    key = "trending",
                    label = "Trending",
                    entries = items(withIcon).mapIndexed { i, it -> UiChartEntry(i + 1, null, it) },
                    metric = "installs",
                ),
            ),
            title = "Top charts",
        ),
    )

    /** The same rows as a server-defined ranked shelf, which goes through the same card. */
    private fun rankedShelfFeed(withIcon: Boolean) = feedOf(
        UiFeedSection.Shelf(
            id = "most-installed",
            title = "Most installed",
            layout = UiShelfLayout.RANK,
            items = items(withIcon),
        ),
    )

    private fun feedOf(section: UiFeedSection) = UiStoreFeed(
        mode = UiStoreMode.POPULATED,
        state = UiStoreState(publishedProjectCount = 42),
        sections = listOf(section),
    )

    @Test
    fun topChartsDrawThePublishedAppIcon() {
        assertTrue(
            drawsPublishedIcon(chartsFeed(withIcon = true)),
            "the chart rows fell back to their glyph tiles",
        )
    }

    @Test
    fun rankedShelvesDrawThePublishedAppIcon() {
        assertTrue(
            drawsPublishedIcon(rankedShelfFeed(withIcon = true)),
            "a RANK shelf shares ChartCard, so it must have gained the icon too",
        )
    }

    /**
     * The control, and the reason the other two assertions mean anything: with no `iconPath` the row
     * keeps its glyph tile rather than drawing an empty plate or the media cache's placeholder.
     */
    @Test
    fun aListingWithNoIconKeepsItsGlyphTile() {
        assertFalse(
            drawsPublishedIcon(chartsFeed(withIcon = false), timeoutMs = 2_000),
            "something was fetched for a listing that published no icon",
        )
    }

    /**
     * Renders until the fetched icon appears, or until [timeoutMs] says it never will.
     *
     * A loop rather than a fixed frame count because the fetch is genuinely asynchronous: the media path
     * resolves on the scene's dispatcher and the decode hops to `Dispatchers.Default`, so the frame that
     * shows the icon is whichever one follows the hop back.
     */
    @OptIn(ExperimentalComposeUiApi::class)
    private fun drawsPublishedIcon(feed: UiStoreFeed, timeoutMs: Long = 15_000): Boolean {
        val backend = ShotBackend()
        val scene = ImageComposeScene(width = WIDTH, height = HEIGHT, density = Density(2f)) {
            CodeAssistTheme(dark = false) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    ExploreFeed(
                        feed = feed,
                        onOpenItem = {},
                        onInstallItem = {},
                        onOpenSearch = {},
                        backend = backend,
                    )
                }
            }
        }
        try {
            val deadline = System.currentTimeMillis() + timeoutMs
            var nanos = 0L
            while (System.currentTimeMillis() < deadline) {
                val image = scene.render(nanos)
                nanos += FRAME_NANOS
                if (Bitmap.makeFromImage(image).hasShotBlue()) return true
                Thread.sleep(16)
            }
            return false
        } finally {
            scene.close()
        }
    }

    /**
     * Whether [ShotBackend]'s flat blue covers a plausible tile.
     *
     * A count rather than a single pixel: the tile is 44 dp at density 2, and one stray blue pixel from
     * antialiasing somewhere else in the frame is not an icon.
     */
    private fun Bitmap.hasShotBlue(): Boolean {
        var found = 0
        // Every fourth pixel on both axes: 16x cheaper than the full frame, and a 88 px tile still
        // contributes hundreds of samples.
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                if (getColor(x, y) == SHOT_BLUE && ++found >= MIN_TILE_SAMPLES) return true
                x += 4
            }
            y += 4
        }
        return false
    }

    private companion object {
        const val WIDTH = 824
        const val HEIGHT = 900
        const val FRAME_NANOS = 16_000_000L

        /** The colour [solidPng] fills, as an opaque ARGB int. */
        const val SHOT_BLUE = 0xFF2F6FED.toInt()

        /** A 44 dp tile at density 2 sampled every fourth pixel is 22x22; well clear of noise. */
        const val MIN_TILE_SAMPLES = 100
    }
}
