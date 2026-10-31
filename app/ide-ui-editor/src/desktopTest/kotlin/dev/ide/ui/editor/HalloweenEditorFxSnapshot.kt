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
import androidx.compose.material3.Text
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import org.jetbrains.skia.EncodedImageFormat

/**
 * The editor's Halloween effects frozen mid-animation, on a dark and a light editor background: embers rising
 * off a candle caret, a half-spun and a finished cobweb, the wandering ghost, and slime drips under an error
 * squiggle. Art is judged by eye, so the PNG is the point; the assertion only catches a blank frame.
 */
class HalloweenEditorFxSnapshot {

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun theEditorEffectsRender() {
        for (dark in listOf(true, false)) {
            val bg = if (dark) Color(0xFF1A1418) else Color(0xFFFFF7EE)
            val ink = if (dark) Color(0xFFE8DCCF) else Color(0xFF2E2226)
            val error = if (dark) Color(0xFFFF6B6B) else Color(0xFFD32F2F)
            val outline = if (dark) Color(0xFF978C92) else Color(0xFF84746A)
            val scene = ImageComposeScene(width = 760, height = 900, density = Density(2f)) {
                Column(
                    Modifier.fillMaxSize().background(bg).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        // Embers: struck over the last second, so the frame catches them at every age.
                        Canvas(Modifier.size(100.dp, 110.dp)) {
                            val field = EmberField()
                            val lh = 40f
                            val cx = size.width / 2f
                            val cy = size.height * 0.6f
                            for (i in 0 until 8) field.spawn(cx, cy - lh * 0.15f, now = i * 0.12f)
                            drawCaret(Offset(cx, cy), 0f, 0f, 0f, 0f, lh, Color(0xFFFF8C1A), CaretStyle.Candle, 20f, flameTime = 1f)
                            with(field) { draw(now = 0.96f, vOff = 0f, hOff = 0f, lineHeight = lh) }
                        }
                        Canvas(Modifier.size(112.dp).background(bg)) { drawCobweb(0.65f, outline.copy(alpha = 0.45f)) }
                        Canvas(Modifier.size(112.dp).background(bg)) { drawCobweb(1f, outline.copy(alpha = 0.45f)) }
                    }
                    Box(Modifier.fillMaxWidth().height(120.dp)) {
                        Text("val greeting = \"hello\"", style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = ink))
                        Canvas(Modifier.fillMaxSize()) {
                            val body = if (dark) Color(0xFFF2ECF8) else Color(0xFF6E6380)
                            val face = if (dark) Color(0xFF2A1E33) else Color(0xFFF7F2FA)
                            drawWanderingGhost(0.5f, if (dark) 0.5f else 0.3f, body, face)
                        }
                    }
                    // An error squiggle under a word, then the drips, as the code layer draws them.
                    Box(Modifier.fillMaxWidth().height(60.dp)) {
                        Text("return undefinedThing(count)", style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = ink))
                        Canvas(Modifier.fillMaxSize()) {
                            val x0 = 7 * 8.4f * density
                            val x1 = 21 * 8.4f * density
                            val lh = 20.dp.toPx()
                            val y = lh - 2f
                            val path = Path().apply {
                                moveTo(x0, y)
                                var x = x0; var up = true
                                while (x < x1) { x += 2.2f; lineTo(x, if (up) y - 1.6f else y + 1.6f); up = !up }
                            }
                            drawPath(path, error, style = Stroke(1.4f))
                            slimeDrips(slimeColor(error), x0, x1, lh - 1f, lh, seed = 12 * 31 + 7)
                        }
                    }
                }
            }
            scene.render()
            val png = scene.render(16_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
            val name = "halloween-editor-${if (dark) "dark" else "light"}.png"
            File("build/snapshots/$name").apply { parentFile?.mkdirs() }.writeBytes(png)
            assertTrue(png.size > 10_000, "$name should render more than a blank frame")
            scene.close()
        }
    }
}
