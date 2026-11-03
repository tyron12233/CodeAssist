package dev.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import dev.ide.ui.StubBackend
import dev.ide.ui.ads.LocalAds
import dev.ide.ui.fakeAdController
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.LearnService
import dev.ide.ui.backend.UiLearnAccount
import dev.ide.ui.backend.UiLearnBadge
import dev.ide.ui.backend.UiLearnCatalog
import dev.ide.ui.backend.UiLearnPath
import dev.ide.ui.backend.UiLessonKind
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

    private class FakeLearn(private val account: UiLearnAccount? = null) : LearnService {
        override fun learnAvailable() = true
        override fun account() = account

        override suspend fun catalog() = UiLearnCatalog(
            tracks = listOf(
                track("kt1", "Kotlin fundamentals", "Kotlin", "kotlin", 14, listOf("Values and variables", "Null safety", "Data classes"), "Beginner"),
                track("co1", "Coroutines and Flow", "Kotlin", "kotlin", 12, listOf("suspend functions", "Flow basics", "Scopes and jobs"), "Advanced", listOf("kt1")),
                track("jv1", "Java collections deep dive", "Java", "java", 10, listOf("List and Set", "Map", "Streams"), "Intermediate"),
                track("an1", "Compose layouts from scratch", "Android", "layers", 16, listOf("Row and Column", "Modifiers", "Constraints"), "Intermediate"),
                track("gr1", "Gradle for humans", "Get started", "hammer", 8, listOf("Tasks", "Version catalogs"), "Advanced", listOf("an1")),
            ),
            paths = listOf(
                UiLearnPath("p1", "Android developer", "From your first line of Kotlin to Compose apps", "android", 0xFF3DDC84L, listOf("kt1", "jv1", "an1", "gr1")),
                UiLearnPath("p2", "Kotlin in depth", "The language end to end, up to coroutines", "kotlin", 0xFF7F52FFL, listOf("kt1", "co1")),
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

        private fun track(
            id: String, title: String, category: String, icon: String, lessons: Int, titles: List<String>,
            level: String = "", prerequisites: List<String> = emptyList(),
        ) =
            UiLearnTrack(
                id = id, title = title, subtitle = "Short description of $title", iconId = icon, category = category,
                level = level, prerequisites = prerequisites,
                lessons = List(lessons) { i ->
                    UiLessonSummary(
                        id = "$id-l$i",
                        title = titles.getOrElse(i) { "Lesson ${i + 1}" },
                        summary = "What this lesson covers", estMinutes = 8, stepCount = 1,
                        kind = when (i) {
                            lessons - 2 -> UiLessonKind.CHECKPOINT
                            lessons - 1 -> UiLessonKind.CAPSTONE
                            else -> UiLessonKind.LESSON
                        },
                    )
                },
            )
    }

    private class FakeBackend(account: UiLearnAccount? = null) : StubBackend() {
        override val learn: LearnService = FakeLearn(account)
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

    /** Signed in with a streak: the header chip shows the streak instead of the lesson count. */
    @Test
    fun renderStreak() {
        snapshot("learn-streak.png", FakeBackend(UiLearnAccount(signedIn = true, streak = 6, bestStreak = 9)), dark = true, height = 700)
    }

    /** Signed out with progress: the prompt to keep it on the account. */
    @Test
    fun renderSyncPrompt() {
        snapshot("learn-sync-prompt.png", FakeBackend(UiLearnAccount(signedIn = false, available = true)), dark = false, height = 900) { b ->
            LearnScreen(backend = b, onOpenTrack = {}, onResume = { _, _, _ -> }, onSignIn = {})
        }
    }

    /** The profile Learning card, with badges and with none. */
    @Test
    fun renderProfileCard() {
        val badges = listOf(
            UiLearnBadge("kt1", "Kotlin Basics", "kotlin", 0xFF7F52FFL),
            UiLearnBadge("jv1", "Java: Beyond Basics", "java", 0xFFF89820L),
            UiLearnBadge("an1", "Android Basics", "android", 0xFF3DDC84L),
        )
        snapshot("learn-profile-card.png", FakeBackend(), dark = true, height = 900) {
            androidx.compose.foundation.layout.Column {
                LearnProfileCard(streak = 6, bestStreak = 12, lessons = 23, badges = badges)
                androidx.compose.foundation.layout.Spacer(Modifier.padding(8.dp))
                LearnProfileCard(streak = 0, bestStreak = 0, lessons = 2, badges = emptyList())
            }
        }
    }

    /** The path map: one finished track, the current one tagged "You are here", the rest upcoming. */
    @Test
    fun renderPath() {
        snapshot("learn-path-dark.png", FakeBackend(), dark = true) { b ->
            LearnPathScreen(backend = b, pathId = "p1", onOpenTrack = {}, onBack = {})
        }
        snapshot("learn-path-light.png", FakeBackend(), dark = false) { b ->
            LearnPathScreen(backend = b, pathId = "p1", onOpenTrack = {}, onBack = {})
        }
    }

    /** A track whose last two lessons are a checkpoint and a capstone, and whose prerequisite is unfinished. */
    @Test
    fun renderTrackWithMilestones() {
        snapshot("learn-track-milestones.png", FakeBackend(), dark = true, height = 2600) { b ->
            LessonTrackScreen(backend = b, trackId = "gr1", onOpenLesson = {}, onBack = {})
        }
    }

    /** No catalog and no resume point: the screen must still lay out rather than crash on empty lists. */
    @Test
    fun renderEmpty() {
        snapshot("learn-empty.png", StubBackend(), dark = true)
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun snapshot(
        name: String,
        backend: IdeBackend,
        dark: Boolean,
        height: Int = HEIGHT,
        content: @androidx.compose.runtime.Composable (IdeBackend) -> Unit = { b ->
            LearnScreen(backend = b, onOpenTrack = {}, onResume = { _, _, _ -> })
        },
    ) {
        val scene = ImageComposeScene(width = WIDTH, height = height, density = Density(2f)) {
            CodeAssistTheme(dark = dark) {
                CompositionLocalProvider(LocalAds provides fakeAdController(backend)) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        content(backend)
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
