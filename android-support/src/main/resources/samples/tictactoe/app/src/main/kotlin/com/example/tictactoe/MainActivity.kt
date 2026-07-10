package com.example.tictactoe

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val XColor = Color(0xFF22D3EE)
private val OColor = Color(0xFFF472B6)
private val ScreenBackground = Color(0xFF0F172A)
private val CellColor = Color(0xFF1E293B)
private val CellWinColor = Color(0xFF334155)
private val TextPrimary = Color(0xFFE2E8F0)

private val TicTacToeColors = darkColorScheme(
    primary = XColor,
    onPrimary = Color(0xFF042F2E),
    background = ScreenBackground,
    surface = CellColor,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = TicTacToeColors) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    TicTacToeScreen()
                }
            }
        }
    }
}

@Composable
fun TicTacToeScreen() {
    val game = remember { TicTacToeState() }
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "TIC · TAC · TOE",
            color = TextPrimary,
            fontSize = 26.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 2.sp,
        )
        Spacer(Modifier.height(20.dp))
        Scoreboard(game)
        Spacer(Modifier.height(20.dp))
        StatusText(game)
        Spacer(Modifier.height(16.dp))
        Grid(game)
        Spacer(Modifier.height(24.dp))
        Button(onClick = { game.newRound() }, shape = RoundedCornerShape(16.dp)) {
            Text("NEW ROUND", fontWeight = FontWeight.Bold)
        }
        TextButton(onClick = { game.resetScores() }) {
            Text("Reset scores", color = TextPrimary.copy(alpha = 0.7f))
        }
    }
}

@Composable
private fun Scoreboard(game: TicTacToeState) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        ScoreCard("PLAYER X", game.xWins, XColor, Modifier.weight(1f))
        ScoreCard("PLAYER O", game.oWins, OColor, Modifier.weight(1f))
    }
}

@Composable
private fun ScoreCard(label: String, score: Int, color: Color, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier.padding(vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(label, color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text("$score", color = TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Black)
        }
    }
}

@Composable
private fun StatusText(game: TicTacToeState) {
    val winner = game.winner
    val text = when {
        winner != null -> "Player ${winner.symbol} wins!"
        game.isDraw -> "It's a draw"
        else -> "Player ${game.current.symbol}'s turn"
    }
    val color = when {
        winner == Player.X -> XColor
        winner == Player.O -> OColor
        game.isDraw -> TextPrimary
        game.current == Player.X -> XColor
        else -> OColor
    }
    Text(text, color = color, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun Grid(game: TicTacToeState) {
    val winning = game.winningLine?.toSet() ?: emptySet()
    Column(
        modifier = Modifier.fillMaxWidth().aspectRatio(1f),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        for (row in 0 until 3) {
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                for (col in 0 until 3) {
                    val index = row * 3 + col
                    CellView(
                        player = game.cells[index],
                        highlighted = index in winning,
                        enabled = game.cells[index] == null && !game.isOver,
                        onClick = { game.play(index) },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
        }
    }
}

@Composable
private fun CellView(
    player: Player?,
    highlighted: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val background by animateColorAsState(
        targetValue = if (highlighted) CellWinColor else CellColor,
        animationSpec = tween(300),
        label = "cellBackground",
    )
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = background,
        enabled = enabled,
        onClick = onClick,
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (player != null) {
                // Pop the mark in with a quick scale-up when the cell is first filled.
                val scale = remember { Animatable(0.4f) }
                LaunchedEffect(player) { scale.animateTo(1f, animationSpec = tween(180)) }
                Text(
                    player.symbol,
                    color = if (player == Player.X) XColor else OColor,
                    fontSize = 56.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.scale(scale.value),
                )
            }
        }
    }
}

@Preview
@Composable
fun TicTacToePreview() {
    MaterialTheme(colorScheme = TicTacToeColors) {
        Surface(color = TicTacToeColors.background) { TicTacToeScreen() }
    }
}
