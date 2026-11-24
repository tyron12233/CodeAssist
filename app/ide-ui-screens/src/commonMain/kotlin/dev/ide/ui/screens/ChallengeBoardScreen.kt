package dev.ide.ui.screens

import dev.ide.ui.distinctKeys
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import dev.ide.ui.backend.AdPlacement
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiChallengeBoard
import dev.ide.ui.components.AdSlot
import dev.ide.ui.components.CircularProgressIndicator
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.back
import dev.ide.ui.generated.resources.challenge_board_empty_body
import dev.ide.ui.generated.resources.challenge_board_empty_title
import dev.ide.ui.generated.resources.challenge_board_solutions_locked
import dev.ide.ui.generated.resources.challenge_board_solutions_open
import dev.ide.ui.generated.resources.challenge_everyone_else
import dev.ide.ui.generated.resources.challenge_leaderboard
import dev.ide.ui.generated.resources.challenge_today
import dev.ide.ui.icons.CaSymbols
import dev.ide.ui.itemsIndexedKeyed
import dev.ide.ui.theme.Ca
import dev.ide.ui.theme.Symbol
import dev.ide.ui.theme.tonalPair
import org.jetbrains.compose.resources.stringResource

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
    // The opened solution sits over the board rather than on the nav stack, so going back returns to the
    // same scroll position with the board already loaded.
    var opened by remember(date) { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    opened?.let { handle ->
        ChallengeSolutionScreen(
            backend = backend,
            // The board's own date, not "today": a board opened just before midnight UTC still means that day.
            date = board?.date?.takeIf { it.isNotBlank() } ?: date,
            handle = handle,
            onBack = { opened = null },
            modifier = modifier,
        )
        return
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
                Symbol(
                    CaSymbols.arrowBack,
                    contentDescription = stringResource(Res.string.back),
                    size = 22.dp,
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                Text(
                    stringResource(Res.string.challenge_leaderboard),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    if (date.isBlank()) stringResource(Res.string.challenge_today) else date,
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
                state = listState,
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) { distinctKeys {
                val open: ((String) -> Unit)? = if (loaded.solutionsOpen) { handle -> opened = handle } else null
                item("solutions") { SolutionsBanner(loaded.solutionsOpen) }
                loaded.you?.takeIf { row -> loaded.rows.none { it.isYou } }?.let { you ->
                    item { BoardRow(you, onClick = open?.let { { it(you.handle) } }) }
                    item {
                        Text(
                            stringResource(Res.string.challenge_everyone_else),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
                // Position-keyed, not rank-keyed. A tie is a legitimate leaderboard, and two rows sharing
                // a rank were a duplicate lazy-list key, which throws out of the measure pass.
                val head = loaded.rows.take(ROWS_BEFORE_AD)
                val tail = loaded.rows.drop(ROWS_BEFORE_AD)
                itemsIndexedKeyed(head, key = { i, r -> "h$i:${r.rank}" }) { _, row ->
                    BoardRow(row, onClick = open?.let { { it(row.handle) } })
                }
                // One break in the list, after the part of the board anyone came to read. A short board
                // puts it at the foot instead, which is where it belongs when there is nothing to break.
                item("ad") { AdSlot(AdPlacement.CHALLENGE, Modifier.padding(vertical = 4.dp)) }
                itemsIndexedKeyed(tail, key = { i, r -> "t$i:${r.rank}" }) { _, row ->
                    BoardRow(row, onClick = open?.let { { it(row.handle) } })
                }
            } }
        }
    }
}

/** How far down the board the single ad break sits: past the ranks the page is actually opened for. */
private const val ROWS_BEFORE_AD = 10

/** Says whether the rows open, and when they will if they do not yet. */
@Composable
private fun SolutionsBanner(open: Boolean) {
    val background = if (open) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val content = if (open) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Ca.radius.md)).background(background)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Symbol(if (open) CaSymbols.codeBlocks else CaSymbols.visibility, contentDescription = null, size = 18.dp, tint = content)
        Text(
            stringResource(if (open) Res.string.challenge_board_solutions_open else Res.string.challenge_board_solutions_locked),
            style = MaterialTheme.typography.bodySmall,
            color = content,
        )
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
            stringResource(Res.string.challenge_board_empty_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            stringResource(Res.string.challenge_board_empty_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}
