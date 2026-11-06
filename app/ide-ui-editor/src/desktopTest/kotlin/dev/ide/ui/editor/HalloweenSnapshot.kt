package dev.ide.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.ide.ui.components.HalloweenCard
import dev.ide.ui.components.HalloweenCardMode
import dev.ide.ui.components.drawBat
import dev.ide.ui.theme.CaAccent
import dev.ide.ui.theme.CodeAssistTheme
import dev.ide.ui.theme.colors.BuiltInColorSchemes
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import org.jetbrains.skia.EncodedImageFormat

/**
 * The Halloween art, off-screen: the Home card in its three modes over the Pumpkin theme, a row of bats mid-
 * flap, and every caret style on the Pumpkin Patch scheme's paper. Art is right or wrong by eye, so the value
 * here is the PNG; the assertion only catches a blank frame.
 */
class HalloweenSnapshot {

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun theHalloweenArtRenders() {
        for (dark in listOf(true, false)) {
            val scene = ImageComposeScene(width = 822, height = 1500, density = Density(2f)) {
                CodeAssistTheme(dark = dark, accent = CaAccent.Pumpkin, editorColorScheme = BuiltInColorSchemes.PUMPKIN) {
                    Column(
                        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        for (mode in HalloweenCardMode.entries) HalloweenCard(mode, onPrimary = {}, onSecondary = {})
                        Canvas(Modifier.fillMaxWidth().height(70.dp)) {
                            val ink = if (dark) Color(0xFF9A88AC) else Color(0xFF1E1424)
                            for (i in 0 until 5) {
                                drawBat(Offset(size.width * (i + 0.5f) / 5f, size.height / 2f), size.height, flap = -1f + i * 0.5f, color = ink)
                            }
                        }
                        val paper = if (dark) Color(0xFF1A1418) else Color(0xFFFFF7EE)
                        val caret = if (dark) Color(0xFFFF8C1A) else Color(0xFFD35F00)
                        Row(Modifier.fillMaxWidth().background(paper).padding(16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                            for (style in CaretStyle.entries) {
                                Canvas(Modifier.size(48.dp, 64.dp)) {
                                    drawCaret(
                                        caretContent = Offset(size.width / 2f - 10f, size.height * 0.35f),
                                        vOff = 0f, hOff = 0f, gutterWidth = 0f, codeTop = 0f,
                                        lineHeight = size.height * 0.5f,
                                        color = caret, style = style, charWidth = 20f, flameTime = 0.7f,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            scene.render()
            val png = scene.render(16_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
            val name = "halloween-${if (dark) "dark" else "light"}.png"
            File("build/snapshots/$name").apply { parentFile?.mkdirs() }.writeBytes(png)
            assertTrue(png.size > 20_000, "$name should render more than a blank frame")
            scene.close()
        }
    }
}
