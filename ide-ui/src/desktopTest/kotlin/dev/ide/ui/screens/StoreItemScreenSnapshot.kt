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
import dev.ide.ui.backend.UiStoreItem
import dev.ide.ui.backend.UiStoreItemKind
import dev.ide.ui.theme.CodeAssistTheme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/**
 * Renders the redesigned [StoreItemScreen]: the tonal hero with its inverted icon tile and three-up
 * figures, the install CTA beside the rate button, the pill tabs, the screenshot strip's dark code
 * panels, the about block, the mono tech chips, the spec table and the ratings panel.
 *
 * Two fixtures on purpose. A **remote** item carries everything (rating, size, version, README,
 * changelog) so all four tabs appear; a **bundled template** carries almost none of it, which is the
 * case that must degrade to Overview alone rather than showing three empty tabs.
 */
class StoreItemScreenSnapshot {

    private val remote = UiStoreItem(
        id = "ktor-exposed",
        kind = UiStoreItemKind.Sample,
        title = "Ktor + Exposed Sample App",
        summary = "A REST service with migrations and tests",
        description = "A REST service with migrations, auth, integration tests and a Docker compose file for local Postgres.",
        category = "Server",
        iconId = "kotlin",
        tags = listOf("Ktor 3", "Exposed", "Postgres", "Flyway"),
        author = "Nordlys Labs",
        language = "Kotlin",
        rating = 4.5f,
        ratingCount = 612,
        installs = 19_000,
        version = "1.8.2",
        downloadBytes = 7_235_174,
        verified = true,
        readme = "Clone into your IDE, run the sync task and pick a run configuration.",
        changelog = "Koin replaces the hand-rolled service locator.\nShared view model test suite.",
        highlights = listOf("Database migrations wired up", "Integration tests included", "Docker compose for local Postgres"),
        screenshots = listOf("sample-snake", "sample-2048", "sample-memory"),
    )

    /** A bundled template: no rating, no version, no payload — Overview only. */
    private val bundled = UiStoreItem(
        id = "sample-calculator",
        kind = UiStoreItemKind.Template,
        title = "Calculator",
        summary = "A Java REPL that evaluates what you type",
        description = "Reads expressions from standard input and evaluates them with a recursive-descent parser. Compiles and runs with no SDK and no network.",
        category = "Java",
        iconId = "java",
        tags = listOf("parser", "console"),
        author = "CodeAssist",
        language = "Java",
        templateId = "sample-calculator",
        highlights = listOf("Recursive-descent expression parser", "Runs with no SDK or network"),
    )

    @Test
    fun renderRemoteDark() = snapshot("item-dark.png", remote, dark = true)

    @Test
    fun renderRemoteLight() = snapshot("item-light.png", remote, dark = false)

    @Test
    fun renderBundledTemplate() = snapshot("item-bundled.png", bundled, dark = true)

    @OptIn(ExperimentalComposeUiApi::class)
    private fun snapshot(name: String, item: UiStoreItem, dark: Boolean, height: Int = 2400) {
        val backend = StubBackend()
        val scene = ImageComposeScene(width = WIDTH, height = height, density = Density(2f)) {
            CodeAssistTheme(dark = dark) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    StoreItemScreen(
                        backend = backend,
                        item = item,
                        onBack = {},
                        onCreateFromTemplate = {},
                        isSaved = true,
                        onToggleSaved = {},
                        onShare = {},
                    )
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
