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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.ide.ui.theme.CaAccent
import dev.ide.ui.theme.CodeAssistTheme
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import org.jetbrains.skia.EncodedImageFormat

/**
 * The winter build and run moments frozen mid-flight: snow and the star, the frost, the gold shimmer, the
 * presents progress at a few fills and the fir glyph. Judged by eye; the assertion only catches a blank frame.
 */
class WinterBuildFxSnapshot {

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun theWinterBuildMomentsRender() {
        for (dark in listOf(true, false)) {
            val scene = ImageComposeScene(width = 822, height = 1400, density = Density(2f)) {
                CodeAssistTheme(dark = dark, accent = CaAccent.Aurora) {
                    Column(
                        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(Modifier.fillMaxWidth().height(260.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val frame = Modifier.weight(1f).height(260.dp).background(MaterialTheme.colorScheme.surfaceContainer)
                            Box(frame) { SnowAndStar(1, { 0.45f }) }
                            Box(frame) { FrostEdge(1, { 0.5f }) }
                            Box(frame) { GoldShimmer(1, { 0.35f }) }
                        }
                        for (p in listOf(0f, 0.35f, 0.7f, 1f)) StarlightProgress({ p }, Modifier.fillMaxWidth().height(12.dp))
                        StarlightProgress(null, Modifier.fillMaxWidth().height(8.dp), gap = 44.dp)
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            for (pop in listOf(null, 0.3f, 0.5f, 0.8f)) Canvas(Modifier.size(36.dp)) { drawSnowflakeGlyph(AuroraTeal, 12f, pop) }
                            for (pop in listOf(null, 0.5f)) Canvas(Modifier.size(20.dp)) { drawSnowflakeGlyph(AuroraTeal, 30f, pop) }
                        }
                    }
                }
            }
            scene.render()
            val png = scene.render(16_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
            val name = "winter-buildfx-${if (dark) "dark" else "light"}.png"
            File(OUT, name).writeBytes(png)
            assertTrue(png.size > 10_000, "$name should render more than a blank frame")
            scene.close()
        }
    }

    private companion object {
        val OUT = File(System.getProperty("java.io.tmpdir"), "codeassist-snapshots").apply { mkdirs() }
    }
}
