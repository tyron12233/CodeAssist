package dev.ide.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.UiStoreReview
import dev.ide.ui.theme.CodeAssistTheme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/**
 * A real review from the store, before and after Translate, with a link in the publisher's reply: the
 * three things this card gained at once (the toggle, the translated text, tappable links).
 */
class ReviewTranslateSnapshot {

    private val review = UiStoreReview(
        authorId = "2afb1df6-38b3-4a39-800d-4dc44f2a3373",
        authorName = "Jess",
        stars = 5,
        review = "pease how do I use the plugin?",
        helpful = 2,
        reply = "1. Download project\n2. Build it\n3. Install it. Guide: https://github.com/tyron12233/CodeAssist/wiki/Plugins",
    )

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun originalAndTranslated() {
        val scene = ImageComposeScene(width = 822, height = 1300, density = Density(2f)) {
            CodeAssistTheme(dark = true) {
                Column(
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    ReviewCard(
                        review = review,
                        relativeTime = "2 days ago",
                        onVote = {},
                        translate = { TranslateToggle(showingTranslation = false, busy = false, error = null, onToggle = {}) },
                    )
                    ReviewCard(
                        review = review,
                        relativeTime = "2 days ago",
                        onVote = {},
                        reviewText = "por favor, ¿cómo uso el plugin?",
                        replyText = "1. Descarga el proyecto\n2. Compílalo\n3. Instálalo. Guía: https://github.com/tyron12233/CodeAssist/wiki/Plugins",
                        translate = { TranslateToggle(showingTranslation = true, busy = false, error = null, onToggle = {}) },
                    )
                }
            }
        }
        try {
            scene.render()
            val png = scene.render(500_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
            val out = File(File(System.getProperty("java.io.tmpdir"), "codeassist-snapshots"), "review-translate.png")
            out.parentFile.mkdirs()
            out.writeBytes(png)
            println("wrote snapshot: $out")
        } finally {
            scene.close()
        }
    }
}
