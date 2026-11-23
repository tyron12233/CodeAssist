package dev.ide.ui.editor

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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.ide.ui.components.WinterBackdrop
import dev.ide.ui.components.WinterCard
import dev.ide.ui.components.AuroraViolet
import dev.ide.ui.components.StarGold
import dev.ide.ui.components.WinterLightColors
import dev.ide.ui.components.FrostySnowman
import dev.ide.ui.components.SeasonalCardMode
import dev.ide.ui.components.SeasonalGlyph
import dev.ide.ui.components.SeasonalNavGlyph
import dev.ide.ui.components.drawFairyLight
import dev.ide.ui.components.drawFirework
import dev.ide.ui.components.drawSkyStar
import dev.ide.ui.components.drawSnowflake
import dev.ide.ui.components.drawStar
import dev.ide.ui.theme.CaAccent
import dev.ide.ui.theme.CodeAssistTheme
import dev.ide.ui.theme.LocalSeason
import dev.ide.ui.theme.Season
import dev.ide.ui.theme.colors.BuiltInColorSchemes
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import org.jetbrains.skia.EncodedImageFormat

/**
 * The winter art, off-screen: the Home card in its three modes over the Aurora theme, the nav glyphs
 * selected and not, the small drawings, the snowman, and the Home backdrop. Art is right or wrong by eye, so the
 * value here is the PNG; the assertion only catches a blank frame.
 */
class WinterSnapshot {

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun theWinterArtRenders() {
        for (dark in listOf(true, false)) {
            val scene = ImageComposeScene(width = 822, height = 1700, density = Density(2f)) {
                CodeAssistTheme(dark = dark, accent = CaAccent.Aurora, editorColorScheme = BuiltInColorSchemes.POLAR_NIGHT) {
                    CompositionLocalProvider(LocalSeason provides Season.Winter) {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                            WinterBackdrop()
                            Column(
                                Modifier.fillMaxSize().padding(20.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                for (mode in SeasonalCardMode.entries) WinterCard(mode, onPrimary = {}, onSecondary = {})
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                    val tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    for (glyph in listOf(SeasonalGlyph.Snowflake, SeasonalGlyph.Snowman, SeasonalGlyph.Star)) {
                                        for (selected in listOf(true, false)) SeasonalNavGlyph(glyph, selected, size = 40.dp, tint = tint)
                                    }
                                }
                                Canvas(Modifier.fillMaxWidth().height(90.dp)) {
                                    val h = size.height
                                    drawSnowflake(Offset(h * 0.5f, h * 0.5f), h * 0.4f, WinterLightColors[4], 10f)
                                    drawStar(Offset(h * 1.5f, h * 0.5f), h * 0.35f, StarGold, glow = 1f)
                                    drawFirework(Offset(h * 2.6f, h * 0.5f), h * 0.45f, 0.5f, StarGold, seed = 2)
                                    for (i in 0 until 4) drawSkyStar(Offset(h * (3.6f + i * 0.5f), h * 0.5f), h * 0.18f, WinterLightColors[i])
                                    for (i in 0 until 5) drawFairyLight(Offset(h * (5.8f + i * 0.35f), h * 0.5f), h * 0.06f, WinterLightColors[i])
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                                    FrostySnowman(96.dp)
                                    FrostySnowman(56.dp)
                                    // The editor's touches on the Polar Night scheme's paper: gutter lights, icicles
                                    // under an error squiggle, and snow mid-fall with its cap half settled.
                                    val paper = if (dark) Color(0xFF0E1424) else Color(0xFFF7F8FD)
                                    val border = if (dark) Color(0xFF2C3A32) else Color(0xFFD9D4C6)
                                    val error = if (dark) Color(0xFFFF6B6B) else Color(0xFFD32F2F)
                                    Canvas(Modifier.size(220.dp, 180.dp).background(paper)) {
                                        val lineH = 20.dp.toPx()
                                        val gutter = 36.dp.toPx()
                                        gutterLights(gutter, (0 until 9).filter { it % LIGHT_EVERY_LINES == 0 }.map { it to it * lineH + lineH / 2f }, lineH, border)
                                        val y = lineH * 3
                                        drawLine(error, Offset(gutter + 20f, y), Offset(gutter + 200f, y), strokeWidth = 3f)
                                        icicles(iceColor(error), gutter + 20f, gutter + 200f, y + 1f, lineH, 7)
                                        drawEditorSnow(editorSnowOf(kotlin.random.Random(2512)), 0.5f, Color.White.copy(alpha = 0.55f).let { if (dark) it else Color(0xFFA9BDB5).copy(alpha = 0.5f) })
                                        drawSnowCap(0.6f, if (dark) Color.White.copy(alpha = 0.4f) else Color(0xFFA9BDB5).copy(alpha = 0.3f))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            scene.render()
            val png = scene.render(16_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
            val name = "winter-${if (dark) "dark" else "light"}.png"
            File(OUT, name).writeBytes(png)
            assertTrue(png.size > 20_000, "$name should render more than a blank frame")
            scene.close()
        }
    }

    private companion object {
        val OUT = File(System.getProperty("java.io.tmpdir"), "codeassist-snapshots").apply { mkdirs() }
    }
}
