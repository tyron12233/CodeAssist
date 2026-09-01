package dev.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.UiStoreReview
import dev.ide.ui.components.RatingSummary
import dev.ide.ui.components.ReviewCard
import dev.ide.ui.theme.CodeAssistTheme
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import org.jetbrains.skia.EncodedImageFormat

/**
 * The reviews panel's pieces, rendered off-screen.
 *
 * The histogram is the part worth looking at: it scales to the most common rating rather than the total, so
 * a regression there is easy to miss in code and obvious in a picture. The cards cover the three shapes
 * that differ — someone else's review, the reader's own (tinted, no vote button), and one with a publisher
 * reply.
 */
class ReviewsSnapshot {

    private val others = UiStoreReview(
        authorId = "u-nordlys",
        authorName = "Nordlys Labs",
        verified = true,
        stars = 5,
        review = "Saved me a weekend. The DI wiring alone was worth it, and the shared view models actually " +
            "have tests.",
        helpful = 12,
        votedByMe = false,
        itemVersion = "1.2.0",
        postedAtMs = System.currentTimeMillis() - 3 * 3_600_000,
    )

    private val withReply = UiStoreReview(
        authorId = "u-anon",
        authorName = null,
        stars = 3,
        review = "Good bones, but the sample networking layer needs work.",
        helpful = 2,
        votedByMe = true,
        itemVersion = "1.1.0",
        postedAtMs = System.currentTimeMillis() - 5 * 86_400_000L,
        reply = "Fair — the networking sample is being replaced in 1.3.",
    )

    private val mine = UiStoreReview(
        authorId = "u-me",
        stars = 4,
        review = "Works, and the docs are honest about what is missing.",
        helpful = 0,
        itemVersion = "1.2.0",
        postedAtMs = System.currentTimeMillis() - 20 * 60_000,
        mine = true,
    )

    @Test
    fun renderDark() = render("reviews-dark.png", dark = true)

    @Test
    fun renderLight() = render("reviews-light.png", dark = false)

    @OptIn(ExperimentalComposeUiApi::class)
    private fun render(name: String, dark: Boolean) {
        val scene = ImageComposeScene(width = 860, height = 1500, density = Density(2f)) {
            CodeAssistTheme(dark = dark) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    Column(Modifier.padding(20.dp)) {
                        RatingSummary(
                            average = 4.3f,
                            count = 128,
                            // A real-looking spread: mostly fives, a tail of ones.
                            distribution = mapOf(5 to 78, 4 to 26, 3 to 12, 2 to 5, 1 to 7),
                        )
                        Spacer(Modifier.height(20.dp))
                        ReviewCard(mine, relativeTime = "20 min ago", onVote = null)
                        Spacer(Modifier.height(12.dp))
                        ReviewCard(others, relativeTime = "3 h ago", onVote = {})
                        Spacer(Modifier.height(12.dp))
                        ReviewCard(withReply, relativeTime = "5 d ago", onVote = {})
                    }
                }
            }
        }
        try {
            scene.render()
            val img = scene.render(1_000_000_000L)
            val png = img.encodeToData(EncodedImageFormat.PNG)!!.bytes
            File("$OUT_DIR/$name").apply { parentFile?.mkdirs() }.writeBytes(png)
            println("wrote snapshot: $OUT_DIR/$name (${png.size} bytes)")
            assertTrue(png.size > 5_000, "the panel should render more than a blank frame")
        } finally {
            scene.close()
        }
    }

    private companion object {
        val OUT_DIR: String = File(System.getProperty("java.io.tmpdir"), "codeassist-snapshots").absolutePath
    }
}
