package dev.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import dev.ide.ui.StubBackend
import dev.ide.ui.ads.LocalAds
import dev.ide.ui.backend.UiChartEntry
import dev.ide.ui.backend.UiChartTab
import dev.ide.ui.backend.UiFeedSection
import dev.ide.ui.backend.UiGhostShelf
import dev.ide.ui.backend.UiShelfLayout
import dev.ide.ui.backend.UiStoreCollection
import dev.ide.ui.backend.UiStoreFeed
import dev.ide.ui.backend.UiStoreItem
import dev.ide.ui.backend.UiStoreItemKind
import dev.ide.ui.backend.UiStoreMode
import dev.ide.ui.backend.UiStorePublisher
import dev.ide.ui.backend.UiStoreState
import dev.ide.ui.fakeAdController
import dev.ide.ui.theme.CodeAssistTheme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/**
 * Renders all three Explore modes, so the two design rules are checkable rather than asserted:
 *
 *  - **Never fake abundance** — the sparse frame must be one generous catalogue, not five thin shelves.
 *  - **Content before pitch** — in sparse, the catalogue comes before the publish band.
 *
 * The fixtures mirror the shapes `store_explore()` actually returns (verified against the live stack in
 * `store-impl`), including an unrated project, so "Not rated yet" is in frame rather than a `0.0`.
 */
class ExploreFeedSnapshot {

    private fun item(
        id: String, title: String, lang: String, icon: String,
        rating: Float = -1f, ratingCount: Int = 0, installs: Int = -1,
        tags: List<String> = emptyList(), template: Boolean = false, blurb: String? = null,
    ) = UiStoreItem(
        id = id,
        kind = if (template) UiStoreItemKind.Template else UiStoreItemKind.Sample,
        title = title,
        summary = "A short summary line",
        description = "A longer description. It has a second sentence that must not appear in the blurb.",
        blurb = blurb ?: "A longer description.",
        category = lang,
        iconId = icon,
        language = lang,
        author = "Nordlys Labs",
        rating = rating,
        ratingCount = ratingCount,
        installs = installs,
        tags = tags,
        templateId = if (template) id else null,
        downloadBytes = 13_002_342,
        publishedAt = "2026-08-26T00:00:00Z",
    )

    private val catalogue = listOf(
        item("kmp-starter", "Compose Multiplatform Starter", "Kotlin", "hub", 4.8f, 1284, 48_213, listOf("Compose", "KMP", "Kotlin"), template = true),
        item("ktor-service", "Ktor + Exposed Service", "Kotlin", "dns", 4.5f, 612, 19_004, listOf("Ktor", "Postgres")),
        // Unrated and barely installed: exercises "Not rated yet" and the "New" install label.
        item("fresh-one", "Just Published Sample", "Java", "coffee", -1f, 0, 3, listOf("console")),
        item("android-clean", "Android Clean Architecture", "Kotlin", "layers", 4.6f, 870, 31_022, listOf("Hilt", "Room")),
    )

    private val sparseFeed = UiStoreFeed(
        mode = UiStoreMode.SPARSE,
        state = UiStoreState(publishedProjectCount = 4),
        sections = listOf(
            UiFeedSection.Catalogue("everything", "Everything in the store", catalogue),
            UiFeedSection.PublishPitch("pitch", 4),
            UiFeedSection.Bundled("offline-templates"),
            UiFeedSection.GhostShelves(
                "unlocks",
                listOf(
                    UiGhostShelf("charts", 4, 10),
                    UiGhostShelf("collections", 4, 12),
                    UiGhostShelf("recommendations", 4, 8),
                ),
            ),
        ),
    )

    private val populatedFeed = UiStoreFeed(
        mode = UiStoreMode.POPULATED,
        state = UiStoreState(publishedProjectCount = 42),
        sections = listOf(
            UiFeedSection.Ticker("terms", listOf("Compose 1.8 starters", "KMP + SQLDelight", "JDK 21 samples", "Gradle catalogs")),
            UiFeedSection.Featured("hero", catalogue.take(3)),
            UiFeedSection.Charts(
                "top-charts",
                listOf(
                    UiChartTab(
                        "trending", "Trending",
                        listOf(
                            UiChartEntry(1, 3, catalogue[1]),   // up 2
                            UiChartEntry(2, 1, catalogue[0]),   // down 1
                            UiChartEntry(3, 3, catalogue[3]),   // flat
                            UiChartEntry(4, null, catalogue[2]), // new entrant
                        ),
                        metric = "installs",
                    ),
                    UiChartTab("top_rated", "Top rated", listOf(UiChartEntry(1, 1, catalogue[0])), "rating"),
                    UiChartTab("new", "New", listOf(UiChartEntry(1, null, catalogue[2])), "recency"),
                    // A tab this build has no hardcoded copy for: its meta line comes from `metric`.
                    UiChartTab("most_liked", "Most liked", listOf(UiChartEntry(1, 2, catalogue[1])), "likes"),
                ),
                computedAt = "2026-09-01T09:00:00Z",
                title = "Top charts",
            ),
            UiFeedSection.Collections(
                "curated", "Collections", "curated by the team",
                listOf(
                    UiStoreCollection("first-android-app", "Starter path", "Ship your first Android app", "rocket_launch", 6, listOf("phone_android", "palette", "layers")),
                    UiStoreCollection("learn-by-reading", "Coursework", "Read a real codebase end to end", "school", 4, listOf("dns", "coffee")),
                ),
            ),
            UiFeedSection.Categories("kinds", "Browse by kind", listOf("Kotlin", "Java", "Android", "Compose"), mapOf("Kotlin" to 18, "Java" to 12, "Android" to 8, "Compose" to 4)),
            UiFeedSection.Personalized(
                "because", "Because you installed Compose Multiplatform Starter",
                "Kotlin picks from the same shelf", catalogue,
            ),
            UiFeedSection.Spotlight(
                "publisher",
                UiStorePublisher(
                    id = "nordlys", handle = "nordlys", name = "Nordlys Labs",
                    bio = "Production-shaped starters for Kotlin, KMP and Android. Every template ships with tests and a documented module layout.",
                    verified = true, projectCount = 14, installCount = 92_000, rating = 4.7f, followerCount = 128,
                ),
            ),
            // One section type, every look the server can ask for. Rendering all five in one frame is
            // what makes "a new shelf needs no app release" checkable rather than a claim.
            UiFeedSection.Shelf(
                id = "editors-choice", title = "Editor's Choice", eyebrow = "Editorial",
                subtitle = "Hand-picked by the review team",
                layout = UiShelfLayout.POSTER, items = catalogue,
            ),
            UiFeedSection.Shelf(
                id = "most-liked", title = "Most liked",
                subtitle = "What the community keeps coming back to",
                layout = UiShelfLayout.CAROUSEL, items = catalogue,
            ),
            UiFeedSection.Shelf(
                id = "kotlin-picks", title = "Kotlin picks",
                layout = UiShelfLayout.GRID, items = catalogue,
            ),
            UiFeedSection.Shelf(
                id = "all-time", title = "All time",
                layout = UiShelfLayout.RANK, items = catalogue,
            ),
            UiFeedSection.Shelf(
                id = "new-updated", title = "New & updated",
                layout = UiShelfLayout.ROWS, items = catalogue,
            ),
        ),
    )

    private val emptyFeed = UiStoreFeed(
        mode = UiStoreMode.EMPTY,
        state = UiStoreState(publishedProjectCount = 0),
        sections = listOf(UiFeedSection.Bundled("offline-templates")),
    )

    private val bundled = listOf(
        item("empty-kotlin", "Empty Kotlin project", "Kotlin", "kotlin", template = true),
        item("empty-java", "Empty Java project", "Java", "java", template = true),
        item("android-app", "Android app (Compose)", "Kotlin", "module.android", template = true),
        item("console-app", "Console application", "Kotlin", "terminal", template = true),
    )

    @Test fun sparseDark() = snapshot("explore-sparse-dark.png", sparseFeed, dark = true, height = 4600)
    @Test fun sparseLight() = snapshot("explore-sparse-light.png", sparseFeed, dark = false, height = 3600)
    @Test fun populatedDark() = snapshot("explore-pop-dark.png", populatedFeed, dark = true, height = 7200)
    @Test fun emptyDark() = snapshot("explore-empty-dark.png", emptyFeed, dark = true, height = 2600)
    @Test fun emptyLight() = snapshot("explore-empty-light.png", emptyFeed, dark = false, height = 2600)

    /** With a submission in flight, the status card sits ABOVE the hero and the CTA changes. */
    @Test
    fun emptyWithSubmission() = snapshot(
        "explore-empty-submission.png", emptyFeed, dark = true, height = 2600,
        submission = dev.ide.ui.backend.UiStoreSubmission(
            itemId = "my-first", projectName = "aurora-app", version = "1.0.0",
            status = dev.ide.ui.backend.UiSubmissionStatus.SUBMITTED,
        ),
    )

    /**
     * A locked-down instance: `acceptingSubmissions = false` must drop the hero and the publishing
     * steps while keeping search, bundled templates and the ghost shelves.
     */
    @Test
    fun emptyNotAcceptingSubmissions() = snapshot(
        "explore-empty-locked.png",
        emptyFeed.copy(state = emptyFeed.state.copy(acceptingSubmissions = false)),
        dark = true, height = 1800,
    )

    @OptIn(ExperimentalComposeUiApi::class)
    private fun snapshot(
        name: String,
        feed: UiStoreFeed,
        dark: Boolean,
        height: Int,
        submission: dev.ide.ui.backend.UiStoreSubmission? = null,
    ) {
        val backend = StubBackend()
        val scene = ImageComposeScene(width = WIDTH, height = height, density = Density(2f)) {
            CodeAssistTheme(dark = dark) {
                CompositionLocalProvider(LocalAds provides fakeAdController(backend)) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        ExploreFeed(
                            feed = feed,
                            onOpenItem = {},
                            onInstallItem = {},
                            onOpenSearch = {},
                            bundled = bundled,
                            followedPublishers = emptySet(),
                            postedLabel = { "Published 3 days ago" },
                            isRecent = { true },
                            submission = submission,
                            notifyOnLaunch = true,
                        )
                    }
                }
            }
        }
        try {
            scene.render()
            for (frame in 1..30) scene.render(frame * 50_000_000L)
            val img = scene.render(2_400_000_000L)
            val png = img.encodeToData(EncodedImageFormat.PNG)!!.bytes
            File("$OUT_DIR/$name").apply { parentFile?.mkdirs() }.writeBytes(png)
            println("wrote snapshot: $OUT_DIR/$name (${png.size} bytes)")
        } finally {
            scene.close()
        }
    }

    private companion object {
        const val WIDTH = 824
        val OUT_DIR: String = File(System.getProperty("java.io.tmpdir"), "codeassist-snapshots").absolutePath
    }
}
