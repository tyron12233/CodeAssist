package dev.ide.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.ide.ui.theme.CodeAssistTheme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The trending row used to be a looping marquee nobody could tap. These pin the two things it now
 * promises: a chip opens its own term, and the "Trending" label in front of the chips is not a button.
 */
@OptIn(ExperimentalComposeUiApi::class)
class TrendingSearchesTest {

    private val terms = listOf("kotlin", "plugin", "utility", "ai", "productivity")

    @Test
    fun theFirstTappableThingIsTheFirstTerm() {
        val tapped = mutableListOf<String>()
        val scene = scene(dark = true) { tapped += it }
        try {
            scene.render()
            // Sweep left to right along the row's centre line and stop at the first tap that lands.
            var x = 1f
            while (tapped.isEmpty() && x < WIDTH) {
                val at = Offset(x, HEIGHT / 2f)
                scene.sendPointerEvent(PointerEventType.Press, at)
                scene.sendPointerEvent(PointerEventType.Release, at)
                scene.render()
                x += 6f
            }
            assertEquals(listOf("kotlin"), tapped)
        } finally {
            scene.close()
        }
    }

    @Test fun snapshotDark() = snapshot("trending-searches-dark.png", dark = true)
    @Test fun snapshotLight() = snapshot("trending-searches-light.png", dark = false)

    private fun snapshot(name: String, dark: Boolean) {
        val scene = scene(dark) {}
        try {
            scene.render()
            val png = scene.render(500_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
            val out = File(File(System.getProperty("java.io.tmpdir"), "codeassist-snapshots"), name)
            out.parentFile.mkdirs()
            out.writeBytes(png)
            println("wrote snapshot: $out")
        } finally {
            scene.close()
        }
    }

    private fun scene(dark: Boolean, onTerm: (String) -> Unit) =
        ImageComposeScene(width = WIDTH, height = HEIGHT, density = Density(2f)) {
            CodeAssistTheme(dark = dark) {
                Box(
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    TrendingSearches(terms, onTerm, Modifier.padding(vertical = 8.dp))
                }
            }
        }

    private companion object {
        const val WIDTH = 822
        const val HEIGHT = 140
    }
}
