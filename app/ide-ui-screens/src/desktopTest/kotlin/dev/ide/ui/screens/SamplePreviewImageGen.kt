@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.ide.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test

/**
 * Dev tool (not a CI test): renders the sample-game "screenshot" artwork to PNG files bundled as Compose
 * drawables (`composeResources/drawable/preview_*.png`), which the store shows on the Explore cards + detail.
 * Off-screen via [ImageComposeScene] (Skiko), so it needs no emulator. Guarded on a system property so it
 * never runs (or writes into the source tree) during a normal `desktopTest` / CI run:
 *
 *     JAVA_HOME=<IntelliJ JBR> ./gradlew :ide-ui:desktopTest \
 *         --tests dev.ide.ui.screens.SamplePreviewImageGen -Dgen.previews=true
 */
class SamplePreviewImageGen {

    @Test
    fun renderPreviewImages() {
        // Guarded so it never writes into the source tree during a normal desktopTest / CI run.
        if (System.getProperty("gen.previews") != "true") return

        val outDir = Paths.get("").toAbsolutePath().resolve("src/commonMain/composeResources/drawable")
        Files.createDirectories(outDir)

        val arts = listOf<Pair<String, @Composable () -> Unit>>(
            "preview_snake" to { SnakeShot() },
            "preview_tic_tac_toe" to { TicTacToeShot() },
            "preview_memory" to { MemoryShot() },
            "preview_2048" to { Game2048Shot() },
        )
        // 360x225 dp at 3x density -> 1080x675 px, a crisp 16:10 card image.
        val density = 3f
        val w = (360 * density).toInt()
        val h = (225 * density).toInt()
        for ((name, art) in arts) {
            val scene = ImageComposeScene(width = w, height = h, density = Density(density)) {
                MaterialTheme { Box(Modifier.fillMaxSize()) { art() } }
            }
            try {
                scene.render() // warm up (fonts fall back on the first frame)
                val image = scene.render(16_000_000L)
                val bytes = image.encodeToData(EncodedImageFormat.PNG)!!.bytes
                Files.write(outDir.resolve("$name.png"), bytes)
            } finally {
                scene.close()
            }
        }
    }
}

// ---- Screenshot artwork (fills the scene) ----

@Composable
private fun SnakeShot() {
    Box(Modifier.fillMaxSize().background(Color(0xFF0B1020)).padding(18.dp)) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("SNAKE", color = Color(0xFF00E676), fontSize = 22.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.weight(1f))
                ScorePill("SCORE", "240", Color(0xFF00E676))
                Spacer(Modifier.width(8.dp))
                ScorePill("BEST", "300", Color(0xFF69F0AE))
            }
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(16.dp)).background(Color(0xFF121A33)),
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val n = 12
                    val cell = size.minDimension / n
                    val ox = (size.width - cell * n) / 2f
                    for (i in 1 until n) {
                        drawLine(Color(0xFF1B2547), Offset(ox + i * cell, 0f), Offset(ox + i * cell, size.height), 1f)
                        drawLine(Color(0xFF1B2547), Offset(ox, i * cell), Offset(ox + cell * n, i * cell), 1f)
                    }
                    fun seg(cx: Int, cy: Int, color: Color) {
                        val pad = cell * 0.12f
                        drawRoundRect(
                            color,
                            Offset(ox + cx * cell + pad, cy * cell + pad),
                            Size(cell - 2 * pad, cell - 2 * pad),
                            CornerRadius(cell * 0.3f, cell * 0.3f),
                        )
                    }
                    val body = listOf(2 to 2, 3 to 2, 4 to 2, 5 to 2, 6 to 2, 6 to 3, 6 to 4, 5 to 4, 4 to 4, 3 to 4, 2 to 4)
                    body.forEach { (cx, cy) -> seg(cx, cy, Color(0xFF00E676)) }
                    seg(2, 4, Color(0xFF69F0AE)) // head
                    drawCircle(Color(0xFFFF5252), cell * 0.34f, Offset(ox + 9.5f * cell, 3.5f * cell))
                }
            }
        }
    }
}

@Composable
private fun ScorePill(label: String, value: String, color: Color) {
    Column(
        Modifier.clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.08f)).padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, color = color.copy(alpha = 0.8f), fontSize = 9.sp, fontWeight = FontWeight.Bold)
        Text(value, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun TicTacToeShot() {
    val marks = listOf(listOf("X", "O", ""), listOf("", "X", "O"), listOf("O", "", "X"))
    val win = setOf(0 to 0, 1 to 1, 2 to 2)
    Box(Modifier.fillMaxSize().background(Color(0xFF0F172A)).padding(18.dp)) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("TIC · TAC · TOE", color = Color(0xFFE2E8F0), fontSize = 18.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.weight(1f))
                ScorePill("X", "2", Color(0xFF22D3EE))
                Spacer(Modifier.width(8.dp))
                ScorePill("O", "1", Color(0xFFF472B6))
            }
            Spacer(Modifier.height(12.dp))
            Column(Modifier.fillMaxHeight().aspectRatio(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (r in 0 until 3) {
                    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (c in 0 until 3) {
                            val m = marks[r][c]
                            val won = (r to c) in win
                            Box(
                                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(12.dp))
                                    .background(if (won) Color(0xFF334155) else Color(0xFF1E293B)),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (m.isNotEmpty()) {
                                    Text(m, color = if (m == "X") Color(0xFF22D3EE) else Color(0xFFF472B6), fontSize = 40.sp, fontWeight = FontWeight.Black)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MemoryShot() {
    // face-down = null; otherwise an ARGB accent color for the (drawn) symbol.
    val cards: List<List<Long?>> = listOf(
        listOf(0xFF34D399, null, null, 0xFFF59E0B),
        listOf(null, 0xFF60A5FA, 0xFF60A5FA, null),
        listOf(0xFFF472B6, null, null, 0xFF34D399),
    )
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF4C1D95), Color(0xFF7C3AED)))).padding(18.dp)) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("MEMORY MATCH", color = Color(0xFFF5F3FF), fontSize = 18.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.weight(1f))
                Text("MOVES 6 · 0:24", color = Color(0xFFF5F3FF).copy(alpha = 0.85f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(12.dp))
            Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                cards.forEach { row ->
                    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { face ->
                            Box(
                                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(12.dp))
                                    .background(if (face == null) Color(0xFF312E81) else Color(0xFFF8FAFC)),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (face == null) {
                                    Text("?", color = Color.White.copy(alpha = 0.85f), fontSize = 22.sp, fontWeight = FontWeight.Black)
                                } else {
                                    Canvas(Modifier.fillMaxSize()) {
                                        drawCircle(Color(face), radius = size.minDimension * 0.26f)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Game2048Shot() {
    val grid = listOf(
        listOf(2, 4, 0, 2),
        listOf(0, 16, 8, 0),
        listOf(4, 2, 128, 32),
        listOf(0, 8, 4, 2),
    )
    Box(Modifier.fillMaxSize().background(Color(0xFFFAF8EF)).padding(18.dp)) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("2048", color = Color(0xFF776E65), fontSize = 26.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.weight(1f))
                ScoreBox("SCORE", "1240")
                Spacer(Modifier.width(8.dp))
                ScoreBox("BEST", "3120")
            }
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxHeight().aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(Color(0xFFBBADA0)).padding(8.dp)) {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    grid.forEach { row ->
                        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { v ->
                                Box(
                                    Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(6.dp)).background(tileColor2048(v)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (v != 0) {
                                        Text(
                                            "$v",
                                            color = if (v <= 4) Color(0xFF776E65) else Color(0xFFF9F6F2),
                                            fontSize = when {
                                                v < 10 -> 16.sp
                                                v < 100 -> 12.sp
                                                v < 1000 -> 9.sp
                                                else -> 7.sp
                                            },
                                            fontWeight = FontWeight.Black,
                                            maxLines = 1,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScoreBox(label: String, value: String) {
    Column(
        Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFFBBADA0)).padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, color = Color(0xFFEEE4DA), fontSize = 9.sp, fontWeight = FontWeight.Bold)
        Text(value, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Black)
    }
}

private fun tileColor2048(value: Int): Color = when (value) {
    0 -> Color(0xFFCDC1B4)
    2 -> Color(0xFFEEE4DA)
    4 -> Color(0xFFEDE0C8)
    8 -> Color(0xFFF2B179)
    16 -> Color(0xFFF59563)
    32 -> Color(0xFFF67C5F)
    64 -> Color(0xFFF65E3B)
    128 -> Color(0xFFEDCF72)
    else -> Color(0xFFEDC850)
}
