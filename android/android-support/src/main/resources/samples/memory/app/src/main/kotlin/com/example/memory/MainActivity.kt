package com.example.memory

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private val ScreenTop = Color(0xFF4C1D95)
private val ScreenBottom = Color(0xFF7C3AED)
private val CardBack = Color(0xFF312E81)
private val CardFace = Color(0xFFF8FAFC)
private val CardMatched = Color(0xFFA7F3D0)
private val OnScreen = Color(0xFFF5F3FF)

private val MemoryColors = darkColorScheme(
    primary = Color(0xFFA78BFA),
    onPrimary = Color(0xFF1E1B4B),
    background = ScreenTop,
    surface = CardBack,
    onBackground = OnScreen,
    onSurface = OnScreen,
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = MemoryColors) {
                MemoryScreen()
            }
        }
    }
}

@Composable
fun MemoryScreen() {
    val game = remember { MemoryGameState() }
    var seconds by remember { mutableIntStateOf(0) }
    // Bumped on every new game so the timer effect restarts from zero.
    var generation by remember { mutableIntStateOf(0) }

    // Reveal a mismatched pair briefly, then flip it back down.
    LaunchedEffect(game.pendingMismatch) {
        if (game.pendingMismatch != null) {
            delay(750)
            game.hideMismatch()
        }
    }

    // Tick the elapsed-time counter once per second until the board is solved.
    LaunchedEffect(generation) {
        seconds = 0
        while (!game.isWon) {
            delay(1000)
            if (!game.isWon) seconds++
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(ScreenTop, ScreenBottom))),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("MEMORY MATCH", color = OnScreen, fontSize = 26.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
            Spacer(Modifier.height(16.dp))
            StatsRow(moves = game.moves, matched = game.matchedPairs, total = game.totalPairs, seconds = seconds)
            Spacer(Modifier.height(20.dp))
            Box(contentAlignment = Alignment.Center) {
                CardGrid(game)
                if (game.isWon) {
                    WinBanner(moves = game.moves, seconds = seconds)
                }
            }
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { game.newGame(); generation++ },
                colors = ButtonDefaults.buttonColors(
                    containerColor = OnScreen,
                    contentColor = ScreenBottom,
                ),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text("NEW GAME", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun StatsRow(moves: Int, matched: Int, total: Int, seconds: Int) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Stat("MOVES", "$moves")
        Stat("PAIRS", "$matched / $total")
        Stat("TIME", formatTime(seconds))
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = OnScreen.copy(alpha = 0.7f), fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Text(value, color = OnScreen, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun CardGrid(game: MemoryGameState) {
    Column(
        modifier = Modifier.fillMaxWidth().aspectRatio(1f),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        for (row in 0 until 4) {
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                for (col in 0 until 4) {
                    val index = row * 4 + col
                    val card = game.cards[index]
                    CardView(
                        card = card,
                        onClick = { game.flip(index) },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
        }
    }
}

@Composable
private fun CardView(card: MemoryCard, onClick: () -> Unit, modifier: Modifier = Modifier) {
    // Rotate the card around its Y axis: 0 = face showing, 180 = back showing. The face content is drawn
    // while the rotation is under 90 degrees; past that the back "?" is shown, counter-rotated so it reads
    // the right way round.
    val rotation by animateFloatAsState(
        targetValue = if (card.faceUp || card.matched) 0f else 180f,
        animationSpec = tween(durationMillis = 400),
        label = "flip",
    )
    val faceUp = rotation < 90f
    Box(
        modifier = modifier
            .graphicsLayer {
                rotationY = rotation
                cameraDistance = 14f * density
            }
            .clip(RoundedCornerShape(18.dp))
            .background(if (!faceUp) CardBack else if (card.matched) CardMatched else CardFace)
            .clickable(enabled = !card.faceUp && !card.matched, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (faceUp) {
            Text(card.emoji, fontSize = 34.sp)
        } else {
            Text(
                "?",
                color = OnScreen.copy(alpha = 0.85f),
                fontSize = 30.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier.graphicsLayer { rotationY = 180f },
            )
        }
    }
}

@Composable
private fun WinBanner(moves: Int, seconds: Int) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0xF21E1B4B))
            .padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("🎉", fontSize = 44.sp)
        Text("You win!", color = OnScreen, fontSize = 26.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(4.dp))
        Text("$moves moves · ${formatTime(seconds)}", color = OnScreen.copy(alpha = 0.85f), fontSize = 15.sp)
    }
}

private fun formatTime(totalSeconds: Int): String {
    val minutes = totalSeconds / 60
    val secs = totalSeconds % 60
    return "$minutes:" + secs.toString().padStart(2, '0')
}

@Preview
@Composable
fun MemoryPreview() {
    MaterialTheme(colorScheme = MemoryColors) {
        MemoryScreen()
    }
}
