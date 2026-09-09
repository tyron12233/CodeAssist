package com.example.snake

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.abs

private val NeonGreen = Color(0xFF00E676)
private val NeonGreenDark = Color(0xFF00863C)
private val ScreenBackground = Color(0xFF0B1020)
private val BoardBackground = Color(0xFF121A33)
private val GridLine = Color(0xFF1B2547)
private val FoodColor = Color(0xFFFF5252)

private val SnakeColors = darkColorScheme(
    primary = NeonGreen,
    onPrimary = Color(0xFF00210B),
    background = ScreenBackground,
    surface = BoardBackground,
    onBackground = Color(0xFFE6ECFF),
    onSurface = Color(0xFFE6ECFF),
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = SnakeColors) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    SnakeScreen()
                }
            }
        }
    }
}

@Composable
fun SnakeScreen() {
    val game = remember { SnakeGameState() }

    // The game loop: while running, advance one cell per tick. The delay shrinks as the score grows, so the
    // snake speeds up. Keyed on `isRunning`, the effect stops cleanly when the game is paused or ends and
    // restarts when play resumes.
    LaunchedEffect(game.isRunning) {
        while (game.isRunning) {
            val speed = (170L - game.score / 10 * 6L).coerceAtLeast(70L)
            delay(speed)
            game.step()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("SNAKE", color = MaterialTheme.colorScheme.primary, fontSize = 34.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            ScorePill("SCORE", game.score)
            ScorePill("BEST", game.bestScore)
        }
        Spacer(Modifier.height(20.dp))
        Board(game, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(24.dp))
        PlayButton(game)
    }
}

@Composable
private fun ScorePill(label: String, value: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
        Text("$value", color = MaterialTheme.colorScheme.onBackground, fontSize = 26.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Board(game: SnakeGameState, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    var dx = 0f
                    var dy = 0f
                    detectDragGestures(
                        onDragStart = { dx = 0f; dy = 0f },
                        onDragEnd = {
                            if (abs(dx) > abs(dy)) {
                                game.turn(if (dx > 0) Direction.RIGHT else Direction.LEFT)
                            } else {
                                game.turn(if (dy > 0) Direction.DOWN else Direction.UP)
                            }
                            // A swipe also starts the game if it is idle (but not while it is over).
                            if (!game.isRunning && !game.isGameOver) game.start()
                        },
                    ) { change, dragAmount ->
                        change.consume()
                        dx += dragAmount.x
                        dy += dragAmount.y
                    }
                },
        ) {
            val n = SnakeGameState.GRID
            val cell = size.minDimension / n

            for (i in 1 until n) {
                val p = i * cell
                drawLine(GridLine, Offset(p, 0f), Offset(p, size.height), strokeWidth = 1f)
                drawLine(GridLine, Offset(0f, p), Offset(size.width, p), strokeWidth = 1f)
            }

            val pad = cell * 0.14f
            drawRoundRect(
                color = FoodColor,
                topLeft = Offset(game.food.x * cell + pad, game.food.y * cell + pad),
                size = Size(cell - 2 * pad, cell - 2 * pad),
                cornerRadius = CornerRadius(cell / 2f, cell / 2f),
            )

            val snake = game.snake
            snake.forEachIndexed { index, part ->
                val shade =
                    if (index == 0) NeonGreen
                    else lerp(NeonGreen, NeonGreenDark, index.toFloat() / snake.size.coerceAtLeast(2))
                drawRoundRect(
                    color = shade,
                    topLeft = Offset(part.x * cell + pad * 0.5f, part.y * cell + pad * 0.5f),
                    size = Size(cell - pad, cell - pad),
                    cornerRadius = CornerRadius(cell * 0.35f, cell * 0.35f),
                )
            }
        }

        if (!game.isRunning) {
            BoardOverlay(game)
        }
    }
}

@Composable
private fun BoardOverlay(game: SnakeGameState) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xCC0B1020)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (game.isGameOver) {
                Text("GAME OVER", color = FoodColor, fontSize = 30.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.height(4.dp))
                Text("Score ${game.score}", color = MaterialTheme.colorScheme.onSurface, fontSize = 18.sp)
            } else {
                Text("Swipe to play", color = MaterialTheme.colorScheme.onSurface, fontSize = 18.sp)
            }
        }
    }
}

@Composable
private fun PlayButton(game: SnakeGameState) {
    Button(
        onClick = {
            when {
                game.isGameOver -> game.start()
                game.isRunning -> game.pause()
                else -> game.start()
            }
        },
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        val label = when {
            game.isGameOver -> "PLAY AGAIN"
            game.isRunning -> "PAUSE"
            else -> "PLAY"
        }
        Text(label, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

@Preview
@Composable
fun SnakePreview() {
    MaterialTheme(colorScheme = SnakeColors) {
        Surface(color = SnakeColors.background) { SnakeScreen() }
    }
}
