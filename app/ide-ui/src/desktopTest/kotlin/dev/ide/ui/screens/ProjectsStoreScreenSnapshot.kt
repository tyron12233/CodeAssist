package dev.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.CompositionLocalProvider
import dev.ide.ui.StubBackend
import dev.ide.ui.ads.LocalAds
import dev.ide.ui.fakeAdController
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.StoreService
import dev.ide.ui.backend.UiStoreCatalog
import dev.ide.ui.backend.UiStoreItem
import dev.ide.ui.backend.UiStoreItemKind
import dev.ide.ui.backend.UiStoreSection
import dev.ide.ui.theme.CodeAssistTheme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/**
 * Renders the redesigned Explore tab off-screen: the display title and search entry, the trending ticker,
 * the featured carousel with its rotated code motifs, the two-column category grid, and the section rows.
 *
 * The fake catalog deliberately carries ratings and install counts on some items and not others, so the
 * "no rating yet" path (which must draw nothing rather than a zero) is in the frame.
 */
class ProjectsStoreScreenSnapshot {

    private class FakeStore : StoreService {
        override fun storeAvailable() = true

        override suspend fun catalog(): UiStoreCatalog {
            val templates = listOf(
                item("kmp", "Compose Multiplatform Starter", "Shared Android, iOS and desktop UI", "Compose", "module.android", "Kotlin", 4.8f, 48_000, template = true, tags = listOf("Compose", "KMP"), previewKey = "sample-2048"),
                item("clean", "Android Clean Architecture", "Domain, data and presentation modules", "Android", "kotlin", "Kotlin", 4.6f, 31_000, template = true, tags = listOf("Hilt", "Room")),
                item("ktor", "Ktor + Exposed Service", "A REST service with migrations and tests", "Server", "dns", "Kotlin", 4.5f, 19_000, template = true, tags = listOf("Ktor", "Postgres")),
            )
            val samples = listOf(
                item("sample-calculator", "Calculator", "A Java REPL that evaluates what you type", "Java", "java", "Java", 4.9f, 96_000, tags = listOf("parser", "console")),
                item("sample-notes", "Notes", "A Kotlin command loop over a model/view split", "Kotlin", "kotlin", "Kotlin", -1f, 27_000, tags = listOf("console")),
                item("sample-snake", "Snake", "The classic game, on a Compose canvas", "Kotlin", "module.android", "Kotlin", 4.3f, 8_400, tags = listOf("game", "canvas"), previewKey = "sample-snake"),
            )
            return UiStoreCatalog(
                featured = templates,
                categories = listOf("Kotlin", "Java", "Android", "Compose", "Server", "Snippets"),
                sections = listOf(
                    UiStoreSection("templates", "Starter templates", "Spin up a new project from a curated scaffold", templates),
                    UiStoreSection("samples", "Sample projects", "Complete, documented example apps you can build and run", samples),
                ),
            )
        }

        private fun item(
            id: String, title: String, summary: String, category: String, icon: String,
            language: String, rating: Float, installs: Int, template: Boolean = false,
            tags: List<String> = emptyList(), previewKey: String? = null,
        ) = UiStoreItem(
            id = id,
            kind = if (template) UiStoreItemKind.Template else UiStoreItemKind.Sample,
            title = title, summary = summary, category = category, iconId = icon,
            language = language, author = "CodeAssist", rating = rating, installs = installs,
            featured = template, templateId = if (template) id else null, tags = tags,
            previewKey = previewKey,
        )
    }

    private class FakeBackend : StubBackend() {
        override val store: StoreService = FakeStore()
    }

    @Test
    fun renderDark() = snapshot("explore-dark.png", FakeBackend(), dark = true)

    @Test
    fun renderLight() = snapshot("explore-light.png", FakeBackend(), dark = false)

    /**
     * A taller frame so the shelves below the category grid are in shot: their headers' "See all" links,
     * the store rows, and the ad slot's gutter alignment against those rows.
     */
    @Test
    fun renderShelves() = snapshot("explore-shelves.png", FakeBackend(), dark = true, height = 3400)

    @OptIn(ExperimentalComposeUiApi::class)
    private fun snapshot(name: String, backend: IdeBackend, dark: Boolean, height: Int = HEIGHT) {
        val scene = ImageComposeScene(width = WIDTH, height = height, density = Density(2f)) {
            CodeAssistTheme(dark = dark) {
                CompositionLocalProvider(LocalAds provides fakeAdController(StubBackend())) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        ProjectsStoreScreen(backend = backend, onOpenItem = {})
                    }
                }
            }
        }
        try {
            scene.render()
            for (frame in 1..40) scene.render(frame * 50_000_000L)
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
        const val HEIGHT = 1784
        val OUT_DIR: String = File(System.getProperty("java.io.tmpdir"), "codeassist-snapshots").absolutePath
    }
}
