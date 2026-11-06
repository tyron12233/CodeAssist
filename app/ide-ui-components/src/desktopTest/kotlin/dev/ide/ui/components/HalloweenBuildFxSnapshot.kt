package dev.ide.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.RunStatus
import dev.ide.ui.theme.CaAccent
import dev.ide.ui.theme.CodeAssistTheme
import dev.ide.ui.theme.LocalSeason
import dev.ide.ui.theme.Season
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import org.jetbrains.skia.EncodedImageFormat

/**
 * The Halloween build and run moments, frozen mid-animation: the Boo ghost halfway through, the candy burst a
 * third of the way in, the pumpkin progress bar (filled to 40%, and the dock's chase parted round the notch),
 * and the cauldron glyph bubbling and puffing. Art is judged by eye, so the PNGs are the point; the assertion
 * only catches a blank frame.
 */
class HalloweenBuildFxSnapshot {

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun theBuildAndRunMomentsRender() {
        for (dark in listOf(true, false)) {
            val scene = ImageComposeScene(width = 822, height = 1400, density = Density(2f)) {
                CodeAssistTheme(dark = dark, accent = CaAccent.Pumpkin) {
                    CompositionLocalProvider(LocalSeason provides Season.Halloween) {
                        Column(
                            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            // A stand-in console, with the ghost rising from its bottom edge.
                            Box(Modifier.fillMaxWidth().height(200.dp).background(MaterialTheme.colorScheme.surfaceContainer)) {
                                Text("e: Unresolved reference 'pumpkin'", Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error)
                                BooGhost({ 0.45f }, Modifier.align(Alignment.BottomCenter))
                            }
                            Box(Modifier.fillMaxWidth().height(260.dp).background(MaterialTheme.colorScheme.surfaceContainerLow)) {
                                Text("Hello, world!", Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSurface)
                                CandyRain(seed = 1, progress = { 0.35f })
                            }
                            PumpkinProgress(progress = { 0.4f }, modifier = Modifier.fillMaxWidth().height(12.dp))
                            Box(Modifier.fillMaxWidth().height(60.dp).background(MaterialTheme.colorScheme.surfaceContainer)) {
                                PumpkinProgress(
                                    progress = null,
                                    modifier = Modifier.fillMaxWidth().height(8.dp).align(Alignment.TopCenter).padding(horizontal = 12.dp),
                                    gap = 44.dp,
                                )
                            }
                            val pot = cauldronIron(dark)
                            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                                Canvas(Modifier.size(64.dp)) { drawCauldronGlyph(pot, bubble = 1.2f, puff = null) }
                                Canvas(Modifier.size(64.dp)) { drawCauldronGlyph(pot, bubble = 1.2f, puff = 0.35f) }
                                // The real glyph at its real size, mid-build.
                                SeasonalBuildGlyph(RunStatus.Running, size = 20.dp) {}
                            }
                        }
                    }
                }
            }
            scene.render()
            val png = scene.render(700_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
            val name = "halloween-build-fx-${if (dark) "dark" else "light"}.png"
            File("build/snapshots/$name").apply { parentFile?.mkdirs() }.writeBytes(png)
            assertTrue(png.size > 20_000, "$name should render more than a blank frame")
            scene.close()
        }
    }
}
