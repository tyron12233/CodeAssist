package com.example.game2048

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.max

private const val SLIDE_MS = 110

private val ScreenBackground = Color(0xFFFAF8EF)
private val BoardColor = Color(0xFFBBADA0)
private val EmptyCell = Color(0xFFCDC1B4)
private val TitleColor = Color(0xFF776E65)
private val AccentColor = Color(0xFF8F7A66)

private val Game2048Colors = lightColorScheme(
    primary = AccentColor,
    onPrimary = Color(0xFFFFFFFF),
    background = ScreenBackground,
    surface = ScreenBackground,
    onBackground = TitleColor,
    onSurface = TitleColor,
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = Game2048Colors) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Game2048Screen()
                }
            }
        }
    }
}

@Composable
fun Game2048Screen() {
    val game = remember { Game2048State() }

    // Settle the board once each move's slide animation has finished.
    LaunchedEffect(game.moveToken) {
        if (game.animating) {
            kotlinx.coroutines.delay(SLIDE_MS.toLong())
            game.endMove()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("2048", color = TitleColor, fontSize = 44.sp, fontWeight = FontWeight.Black)
                Text("Swipe to merge the tiles!", color = TitleColor.copy(alpha = 0.8f), fontSize = 13.sp)
            }
            ScoreBox("SCORE", game.score)
            Spacer(Modifier.width(8.dp))
            ScoreBox("BEST", game.best)
        }
        Spacer(Modifier.height(20.dp))
        Board(game, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
        if (game.hasWon) {
            Text("You reached 2048! Keep going.", color = AccentColor, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
        }
        Button(
            onClick = { game.newGame() },
            colors = ButtonDefaults.buttonColors(containerColor = AccentColor, contentColor = Color.White),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text("NEW GAME", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ScoreBox(label: String, value: Int) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(BoardColor)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, color = Color(0xFFEEE4DA), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Text("$value", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun Board(game: Game2048State, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(14.dp))
            .background(BoardColor)
            .pointerInput(Unit) {
                var dx = 0f
                var dy = 0f
                detectDragGestures(
                    onDragStart = { dx = 0f; dy = 0f },
                    onDragEnd = {
                        if (max(abs(dx), abs(dy)) >= 24f) {
                            if (abs(dx) > abs(dy)) {
                                game.beginMove(if (dx > 0) Direction.RIGHT else Direction.LEFT)
                            } else {
                                game.beginMove(if (dy > 0) Direction.DOWN else Direction.UP)
                            }
                        }
                    },
                ) { change, dragAmount ->
                    change.consume()
                    dx += dragAmount.x
                    dy += dragAmount.y
                }
            },
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(6.dp)) {
            val cell = maxWidth / Game2048State.SIZE
            // The static background grid.
            for (r in 0 until Game2048State.SIZE) {
                for (c in 0 until Game2048State.SIZE) {
                    Box(
                        Modifier.offset(cell * c, cell * r).size(cell).padding(3.dp)
                            .clip(RoundedCornerShape(8.dp)).background(EmptyCell),
                    )
                }
            }
            // The live tiles, keyed by id so each one animates from its old cell to its new one.
            for (tile in game.tiles) {
                key(tile.id) { TileView(tile, cell) }
            }
        }

        if (game.isGameOver) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color(0xCCFAF8EF)),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Game Over", color = TitleColor, fontSize = 32.sp, fontWeight = FontWeight.Black)
                    Text("Score ${game.score}", color = TitleColor, fontSize = 16.sp)
                }
            }
        }
    }
}

@Composable
private fun TileView(tile: Tile, cell: Dp) {
    val x by animateDpAsState(cell * tile.col, tween(SLIDE_MS), label = "x")
    val y by animateDpAsState(cell * tile.row, tween(SLIDE_MS), label = "y")
    // Pop the tile whenever its value appears or changes (spawn / merge); a plain slide leaves it unchanged.
    val scale = remember { Animatable(0.6f) }
    LaunchedEffect(tile.value) {
        scale.snapTo(0.6f)
        scale.animateTo(1f, tween(140))
    }
    Box(
        Modifier.offset(x, y).size(cell).padding(3.dp)
            .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
            .clip(RoundedCornerShape(8.dp)).background(tileColor(tile.value)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "${tile.value}",
            color = tileTextColor(tile.value),
            fontSize = when {
                tile.value < 100 -> 30.sp
                tile.value < 1000 -> 24.sp
                else -> 19.sp
            },
            fontWeight = FontWeight.Black,
        )
    }
}

private fun tileColor(value: Int): Color = when (value) {
    0 -> EmptyCell
    2 -> Color(0xFFEEE4DA)
    4 -> Color(0xFFEDE0C8)
    8 -> Color(0xFFF2B179)
    16 -> Color(0xFFF59563)
    32 -> Color(0xFFF67C5F)
    64 -> Color(0xFFF65E3B)
    128 -> Color(0xFFEDCF72)
    256 -> Color(0xFFEDCC61)
    512 -> Color(0xFFEDC850)
    1024 -> Color(0xFFEDC53F)
    else -> Color(0xFFEDC22E)
}

private fun tileTextColor(value: Int): Color = if (value <= 4) Color(0xFF776E65) else Color(0xFFF9F6F2)

@Preview
@Composable
fun Game2048Preview() {
    MaterialTheme(colorScheme = Game2048Colors) {
        Surface(color = Game2048Colors.background) { Game2048Screen() }
    }
}
