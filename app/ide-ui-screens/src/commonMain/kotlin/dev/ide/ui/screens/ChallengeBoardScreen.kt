package dev.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiChallengeBoard
import dev.ide.ui.icons.CaSymbols
import dev.ide.ui.theme.Ca
import dev.ide.ui.theme.Symbol
import dev.ide.ui.theme.tonalPair

/**
 * The full leaderboard for one day.
 *
 * The viewer's own row is pinned above the list as well as highlighted within it, because the answer to
 * "where am I" should not require scrolling to find out.
 */
@Composable
fun ChallengeBoardScreen(
    backend: IdeBackend,
    date: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var board by remember(date) { mutableStateOf<UiChallengeBoard?>(null) }
    LaunchedEffect(date) {
        board = runCatching { backend.challenges.board(date, limit = 200) }.getOrDefault(UiChallengeBoard())
    }

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(Ca.radius.pill)).clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Symbol(CaSymbols.arrowBack, contentDescription = "Back", size = 22.dp, tint = MaterialTheme.colorScheme.onSurface)
            }
            Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                Text(
                    "Leaderboard",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    if (date.isBlank()) "Today" else date,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }

        val loaded = board
        when {
            loaded == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
            }

            loaded.rows.isEmpty() -> EmptyBoard()

            else -> LazyColumn(
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                loaded.you?.takeIf { row -> loaded.rows.none { it.isYou } }?.let { you ->
                    item { BoardRow(you) }
                    item {
                        Text(
                            "Everyone else",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
                items(loaded.rows, key = { it.rank }) { row -> BoardRow(row) }
            }
        }
    }
}

@Composable
private fun EmptyBoard() {
    val pair = tonalPair(0)
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Symbol(CaSymbols.workspacePremium, contentDescription = null, size = 40.dp, tint = pair.container)
        Text(
            "Nobody has solved it yet",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            "First one on the board sets the pace.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}
