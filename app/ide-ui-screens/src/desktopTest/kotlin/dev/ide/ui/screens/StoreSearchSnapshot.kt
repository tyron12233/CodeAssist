package dev.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import dev.ide.ui.StubBackend
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.StoreService
import dev.ide.ui.backend.UiStoreCategory
import dev.ide.ui.backend.UiStoreItem
import dev.ide.ui.backend.UiStoreItemKind
import dev.ide.ui.backend.UiStoreSearchPage
import dev.ide.ui.theme.CodeAssistTheme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The store's search, which is also its browse-all route.
 *
 * Renders the first page against a fake catalogue of 90 projects, and separately asserts what the screen's
 * state actually asks the backend for: an empty query is a real request (that is what makes browsing the
 * whole catalogue possible at all), and scrolling asks for the next page from where the last one ended.
 */
class StoreSearchSnapshot {

    private class FakeStore(private val total: Int = 90) : StoreService {
        /** Every (query, category, offset) this was asked for, in order. */
        val calls = mutableListOf<Triple<String, String?, Int>>()

        override fun storeAvailable() = true

        override suspend fun searchCategories(): List<UiStoreCategory> = listOf(
            UiStoreCategory("kotlin", "Kotlin", 41),
            UiStoreCategory("java", "Java", 22),
            UiStoreCategory("android", "Android scaffolds", 18),
            UiStoreCategory("compose", "Multiplatform", 9),
        )

        override suspend fun searchPage(
            query: String,
            category: String?,
            offset: Int,
            limit: Int,
        ): UiStoreSearchPage {
            calls += Triple(query, category, offset)
            val matching = (0 until total).filter { query.isBlank() || "project $it".contains(query) }
            val page = matching.drop(offset).take(limit)
            return UiStoreSearchPage(
                items = page.map { item(it) },
                hasMore = offset + page.size < matching.size,
            )
        }

        private fun item(i: Int) = UiStoreItem(
            id = "project-$i",
            kind = UiStoreItemKind.Community,
            title = "Project $i",
            summary = "One of the many projects published to the store",
            category = "Kotlin",
            iconId = "kotlin",
            language = "Kotlin",
            author = "publisher$i",
            rating = if (i % 3 == 0) 4.4f else -1f,
            ratingCount = if (i % 3 == 0) 12 else 0,
            installs = 1_000 - i * 7,
            downloadBytes = 2_400_000L + i,
            available = true,
        )
    }

    private class FakeBackend(val fake: FakeStore = FakeStore()) : StubBackend() {
        override val store: StoreService = fake
    }

    @Test
    fun renderDark() = snapshot("store-search-dark.png", FakeBackend(), dark = true)

    @Test
    fun renderLight() = snapshot("store-search-light.png", FakeBackend(), dark = false)

    /**
     * Opening the search asks the store for a page even with nothing typed.
     *
     * This is the browse-all route: the curated feed is capped per shelf, so an empty query that returned
     * nothing would leave the catalogue behind it unreachable, which is exactly what the app used to do.
     */
    @Test
    fun anEmptyQueryIsStillARequest() {
        val backend = FakeBackend()
        renderTo(backend, dark = true, height = 1600, name = null)
        assertTrue(backend.fake.calls.isNotEmpty(), "the screen must ask before anything is typed")
        assertEquals(Triple("", null, 0), backend.fake.calls.first())
    }

    /** A scroll to the end asks for the next page, from where the last one ended rather than from zero. */
    @Test
    fun scrollingAsksForTheNextPage() {
        val backend = FakeBackend()
        // Tall enough that the first page does not fill it, so the list reaches its prefetch threshold.
        renderTo(backend, dark = true, height = 4000, name = null)
        val offsets = backend.fake.calls.map { it.third }
        assertTrue(offsets.size > 1, "a list scrolled to its end must ask for more, got $offsets")
        assertEquals(0, offsets[0])
        assertTrue(offsets[1] > 0, "the second request continues rather than repeating the first page")
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun snapshot(name: String, backend: IdeBackend, dark: Boolean) =
        renderTo(backend, dark, HEIGHT, name)

    @OptIn(ExperimentalComposeUiApi::class)
    private fun renderTo(backend: IdeBackend, dark: Boolean, height: Int, name: String?) {
        val scene = ImageComposeScene(width = WIDTH, height = height, density = Density(2f)) {
            CodeAssistTheme(dark = dark) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    StoreSearchScreen(backend = backend, onOpenItem = {}, onClose = {})
                }
            }
        }
        try {
            scene.render()
            for (frame in 1..40) scene.render(frame * 50_000_000L)
            val img = scene.render(2_400_000_000L)
            if (name != null) {
                val png = img.encodeToData(EncodedImageFormat.PNG)!!.bytes
                File("$OUT_DIR/$name").apply { parentFile?.mkdirs() }.writeBytes(png)
                println("wrote snapshot: $OUT_DIR/$name (${png.size} bytes)")
            }
        } finally {
            scene.close()
        }
    }

    private companion object {
        const val WIDTH = 824
        const val HEIGHT = 1784
        val OUT_DIR: String = File(System.getProperty("java.io.tmpdir"), "codeassist-snapshots").absolutePath
    }
}
