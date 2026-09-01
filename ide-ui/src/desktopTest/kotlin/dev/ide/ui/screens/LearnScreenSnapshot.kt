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
import dev.ide.ui.backend.LearnService
import dev.ide.ui.backend.UiLearnCatalog
import dev.ide.ui.backend.UiLearnProgress
import dev.ide.ui.backend.UiLearnTrack
import dev.ide.ui.backend.UiLessonSummary
import dev.ide.ui.backend.UiResumePoint
import dev.ide.ui.theme.CodeAssistTheme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/**
 * Renders the redesigned [LearnScreen] off-screen to a PNG, in both themes, so the Material 3 Expressive
 * layout can be eyeballed without launching the app: the display-weight title with its completion chip, the
 * Continue card's progress ring and inverted resume button, the track pills, and the module cards with
 * their rotating tonal tiles, status badges and progress bars.
 *
 * Also a guard: a layout or a missing-glyph mistake in this screen fails here rather than on a device.
 */
class LearnScreenSnapshot {

    private class FakeLearn : LearnService {
        override fun learnAvailable() = true

        override suspend fun catalog() = UiLearnCatalog(
            tracks = listOf(
                track("kt1", "Kotlin fundamentals", "Kotlin", "kotlin", 14, listOf("Values and variables", "Null safety", "Data classes")),
                track("co1", "Coroutines and Flow", "Kotlin", "kotlin", 12, listOf("suspend functions", "Flow basics", "Scopes and jobs")),
                track("jv1", "Java collections deep dive", "Java", "java", 10, listOf("List and Set", "Map", "Streams")),
                track("an1", "Compose layouts from scratch", "Android", "layers", 16, listOf("Row and Column", "Modifiers", "Constraints")),
                track("gr1", "Gradle for humans", "Get started", "hammer", 8, listOf("Tasks", "Version catalogs")),
            ),
        )

        // 11 of kt1, 4 of co1, all 10 of jv1, 6 of an1, none of gr1 — one finished track, one untouched,
        // so all three module status badges appear in a single frame.
        override fun progress() = UiLearnProgress(
            completedByLesson = buildMap {
                repeat(11) { put("kt1-l$it", setOf("s0")) }
                repeat(4) { put("co1-l$it", setOf("s0")) }
                repeat(10) { put("jv1-l$it", setOf("s0")) }
                repeat(6) { put("an1-l$it", setOf("s0")) }
            },
        )

        override fun resume() = UiResumePoint(
            trackId = "co1", lessonId = "co1-l4", stepIndex = 1,
            trackTitle = "Coroutines and Flow", lessonTitle = "Structured concurrency",
            fractionComplete = 4f / 12f,
        )

        private fun track(id: String, title: String, category: String, icon: String, lessons: Int, titles: List<String>) =
            UiLearnTrack(
                id = id, title = title, subtitle = "", iconId = icon, category = category,
                lessons = List(lessons) { i ->
                    UiLessonSummary(
                        id = "$id-l$i",
                        title = titles.getOrElse(i) { "Lesson ${i + 1}" },
                        summary = "", estMinutes = 8, stepCount = 1,
                    )
                },
            )
    }

    private class FakeBackend : StubBackend() {
        override val learn: LearnService = FakeLearn()
    }

    @Test
    fun renderDark() {
        snapshot("learn-dark.png", FakeBackend(), dark = true)
    }

    @Test
    fun renderLight() {
        snapshot("learn-light.png", FakeBackend(), dark = false)
    }

    /**
     * A taller frame so the interleaved ad slot is in shot: its left and right edges must line up with the
     * module cards' 20 dp gutter, which is the whole point of padding it rather than letting it bleed.
     */
    @Test
    fun renderWithAdInFrame() {
        snapshot("learn-ads.png", FakeBackend(), dark = true, height = 2800)
    }

    /** No catalog and no resume point: the screen must still lay out rather than crash on empty lists. */
    @Test
    fun renderEmpty() {
        snapshot("learn-empty.png", StubBackend(), dark = true)
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun snapshot(name: String, backend: IdeBackend, dark: Boolean, height: Int = HEIGHT) {
        val scene = ImageComposeScene(width = WIDTH, height = height, density = Density(2f)) {
            CodeAssistTheme(dark = dark) {
                CompositionLocalProvider(LocalAds provides fakeAdController(backend)) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        LearnScreen(backend = backend, onOpenTrack = {}, onResume = { _, _, _ -> })
                    }
                }
            }
        }
        try {
            // The catalog arrives through produceState, so step the clock until the coroutine has run and
            // the progress ring's spring has settled.
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
        const val WIDTH = 824   // 412 dp at density 2
        const val HEIGHT = 1784 // 892 dp at density 2
        val OUT_DIR: String = File(System.getProperty("java.io.tmpdir"), "codeassist-snapshots").absolutePath
    }
}
