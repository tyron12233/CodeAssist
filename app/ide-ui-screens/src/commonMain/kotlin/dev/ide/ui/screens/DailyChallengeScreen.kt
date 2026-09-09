package dev.ide.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiChallengeDay
import dev.ide.ui.backend.UiChallengeHistoryEntry
import dev.ide.ui.backend.UiChallengeRank
import dev.ide.ui.icons.CaSymbols
import dev.ide.ui.theme.Ca
import dev.ide.ui.theme.Motion
import dev.ide.ui.theme.Symbol
import dev.ide.ui.theme.cardShape
import dev.ide.ui.theme.tileShape
import dev.ide.ui.theme.tonalPair

/**
 * The Challenges tab.
 *
 * Everything above the fold works signed out on purpose: the problem, the countdown and the board are
 * the argument for signing in, and hiding them behind an account would mean nobody ever sees it. Only
 * solving needs a session, and the call to action says so.
 */
@Composable
fun DailyChallengeScreen(
    backend: IdeBackend,
    epoch: Int,
    signedIn: Boolean,
    onSolve: () -> Unit,
    onSignIn: () -> Unit,
    onOpenBoard: () -> Unit,
    onOpenArchive: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = rememberChallengeTab(backend, epoch)
    val day = state.day

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 20.dp, end = 20.dp, top = 12.dp, bottom = 28.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { ChallengeHeader(day, state.profile.streak.current) }

        if (state.loading && day.problem == null) {
            item { LoadingCard() }
        } else if (!day.scheduled) {
            item { NothingScheduledCard(offline = state.offline) }
        } else {
            item {
                TodayHero(
                    day = day,
                    signedIn = signedIn,
                    onSolve = onSolve,
                    onSignIn = onSignIn,
                )
            }
            if (day.you.solved) item { YourResultCard(day) }
        }

        if (state.board.rows.isNotEmpty()) {
            item { ChallengeSectionHeader("Today's top", onAction = onOpenBoard, actionLabel = "Full board") }
            items(state.board.rows, key = { it.rank }) { row -> BoardRow(row) }
        }

        if (state.profile.signedIn) {
            item { StreakStrip(state) }
        }

        val history = state.profile.history.filter { it.date != day.date }
        if (history.isNotEmpty()) {
            item { ChallengeSectionHeader("Previous days") }
            item { ArchiveStrip(history, onOpenArchive) }
        }
    }
}

@Composable
private fun ChallengeHeader(day: UiChallengeDay, streak: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                "Daily Challenge",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                if (day.date.isBlank()) "One problem a day" else day.date,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        if (streak > 0) StreakFlame(streak)
    }
}

/** The streak, shown as a count beside a flame. Hidden at zero rather than shown as a zero. */
@Composable
private fun StreakFlame(streak: Int) {
    val pair = tonalPair(2)
    Row(
        Modifier.clip(RoundedCornerShape(Ca.radius.pill)).background(pair.container)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Symbol(CaSymbols.localFireDepartment, contentDescription = null, size = 18.dp, tint = pair.onContainer, filled = true)
        Text(
            streak.toString(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = pair.onContainer,
        )
    }
}

/**
 * Today's problem.
 *
 * The countdown is the only live element: it is what makes the tab feel like it is running rather than
 * showing a page. Everything else is settled the moment the day loads.
 */
@Composable
private fun TodayHero(
    day: UiChallengeDay,
    signedIn: Boolean,
    onSolve: () -> Unit,
    onSignIn: () -> Unit,
) {
    val problem = day.problem ?: return
    val pair = tonalPair(0)
    val now = rememberSecondsTicker()
    val remaining = if (day.nextDropAtMs > 0) day.nextDropAtMs - System.currentTimeMillis() else 0L

    Column(
        Modifier.fillMaxWidth().clip(cardShape(0)).background(pair.container).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DifficultyPill(problem.difficulty, pair.onContainer)
            Spacer(Modifier.weight(1f))
            if (remaining > 0) {
                Symbol(CaSymbols.schedule, contentDescription = null, size = 16.dp, tint = pair.onContainer.copy(alpha = 0.7f))
                Text(
                    formatCountdown(remaining),
                    style = MaterialTheme.typography.labelLarge,
                    fontFamily = FontFamily.Monospace,
                    color = pair.onContainer.copy(alpha = 0.7f),
                )
            }
        }

        Text(
            problem.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = pair.onContainer,
        )

        if (problem.tags.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                problem.tags.take(3).forEach { tag ->
                    Text(
                        tag,
                        style = MaterialTheme.typography.labelMedium,
                        color = pair.onContainer.copy(alpha = 0.7f),
                        modifier = Modifier.clip(RoundedCornerShape(Ca.radius.pill))
                            .border(1.dp, pair.onContainer.copy(alpha = 0.25f), RoundedCornerShape(Ca.radius.pill))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            HeroStat(day.solvedCount.toString(), "solved today", pair.onContainer)
            HeroStat(problem.featuredLanguage.name, "featured", pair.onContainer)
            if (day.you.attempts > 0) HeroStat(day.you.attempts.toString(), "attempts", pair.onContainer)
        }

        val solved = day.you.solved
        Button(
            onClick = if (signedIn) onSolve else onSignIn,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(Ca.radius.pill),
            colors = ButtonDefaults.buttonColors(
                containerColor = pair.onContainer,
                contentColor = pair.container,
            ),
        ) {
            Symbol(
                if (solved) CaSymbols.checkCircle else CaSymbols.bolt,
                contentDescription = null,
                size = 20.dp,
                tint = pair.container,
                filled = true,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                when {
                    !signedIn -> "Sign in to solve"
                    solved -> "Solved. Open it again"
                    day.you.attempts > 0 -> "Keep going"
                    else -> "Solve today's challenge"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }

        if (!signedIn) {
            Text(
                "Browsing is open to everyone. An account is only needed to submit and to appear on the board.",
                style = MaterialTheme.typography.bodySmall,
                color = pair.onContainer.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun HeroStat(value: String, label: String, color: Color) {
    Column {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = color)
        Text(label, style = MaterialTheme.typography.labelSmall, color = color.copy(alpha = 0.7f))
    }
}

@Composable
private fun DifficultyPill(difficulty: String, onContainer: Color) {
    Text(
        difficulty.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = onContainer,
        modifier = Modifier.clip(RoundedCornerShape(Ca.radius.pill))
            .background(onContainer.copy(alpha = 0.16f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** The viewer's own standing result, shown only once they have one. */
@Composable
private fun YourResultCard(day: UiChallengeDay) {
    val pair = tonalPair(1)
    Row(
        Modifier.fillMaxWidth().clip(cardShape(1)).background(pair.container).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Symbol(CaSymbols.checkCircle, contentDescription = null, size = 28.dp, tint = pair.onContainer, filled = true)
        Column(Modifier.weight(1f)) {
            Text(
                "Solved in " + formatSolveTime(day.you.solveMs),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = pair.onContainer,
            )
            Text(
                listOfNotNull(
                    day.you.timeClass?.let { "time " + it },
                    day.you.spaceClass?.let { "memory " + it },
                    formatRuntime(day.you.runtimeNs).takeIf { it != "-" },
                ).joinToString("  ·  "),
                style = MaterialTheme.typography.bodySmall,
                color = pair.onContainer.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun ChallengeSectionHeader(title: String, onAction: (() -> Unit)? = null, actionLabel: String = "") {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (onAction != null) {
            Text(
                actionLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onAction).padding(4.dp),
            )
        }
    }
}

/**
 * One leaderboard row.
 *
 * The complexity class sits next to the runtime because that is the comparison the feature is about: a
 * slower run in a better class is the result worth understanding.
 */
@Composable
fun BoardRow(row: UiChallengeRank, modifier: Modifier = Modifier) {
    val highlight = row.isYou
    val background =
        if (highlight) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow
    val onBackground =
        if (highlight) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface

    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(Ca.radius.md)).background(background)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RankBadge(row.rank, onBackground)
        Column(Modifier.weight(1f)) {
            Text(
                row.displayName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (highlight) FontWeight.Bold else FontWeight.Medium,
                color = onBackground,
                maxLines = 1,
            )
            Text(
                row.timeClass + "  ·  " + formatRuntime(row.runtimeNs) + "  ·  " + row.language.name,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = onBackground.copy(alpha = 0.7f),
                maxLines = 1,
            )
        }
        Text(
            row.score.toString(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = onBackground,
        )
    }
}

/** The top three get the medal tint; everyone else gets their number. */
@Composable
private fun RankBadge(rank: Int, fallback: Color) {
    val medal = when (rank) {
        1 -> Color(0xFFFFC107)
        2 -> Color(0xFFB0BEC5)
        3 -> Color(0xFFCD7F32)
        else -> null
    }
    Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
        if (medal != null) {
            Box(Modifier.size(32.dp).clip(CircleShape).background(medal.copy(alpha = 0.22f)))
            Symbol(CaSymbols.workspacePremium, contentDescription = null, size = 20.dp, tint = medal, filled = true)
        } else {
            Text(
                rank.toString(),
                style = MaterialTheme.typography.labelLarge,
                fontFamily = FontFamily.Monospace,
                color = fallback.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun StreakStrip(state: ChallengeTabState) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        StatTile("Streak", state.profile.streak.current.toString(), CaSymbols.localFireDepartment, 0, Modifier.weight(1f))
        StatTile("Longest", state.profile.streak.longest.toString(), CaSymbols.trendingUp, 1, Modifier.weight(1f))
        StatTile("Solved", state.profile.streak.totalSolved.toString(), CaSymbols.checkCircle, 2, Modifier.weight(1f))
    }
}

@Composable
private fun StatTile(label: String, value: String, glyph: Char, index: Int, modifier: Modifier = Modifier) {
    val pair = tonalPair(index)
    Column(
        modifier.clip(tileShape(index)).background(pair.container).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Symbol(glyph, contentDescription = null, size = 18.dp, tint = pair.onContainer, filled = true)
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = pair.onContainer)
        Text(label, style = MaterialTheme.typography.labelSmall, color = pair.onContainer.copy(alpha = 0.7f))
    }
}

/** Past days, so an unsolved one is still reachable as practice. */
@Composable
private fun ArchiveStrip(history: List<UiChallengeHistoryEntry>, onOpen: (String) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(history, key = { it.date }) { entry ->
            val pair = tonalPair(if (entry.solved) 1 else 0)
            val faded = if (entry.solved) 1f else 0.55f
            Column(
                Modifier.width(150.dp).clip(RoundedCornerShape(Ca.radius.lg))
                    .background(pair.container.copy(alpha = faded))
                    .clickable { onOpen(entry.date) }
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        entry.date.takeLast(5),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = pair.onContainer.copy(alpha = 0.7f),
                        modifier = Modifier.weight(1f),
                    )
                    if (entry.solved) {
                        Symbol(CaSymbols.check, contentDescription = "solved", size = 16.dp, tint = pair.onContainer)
                    }
                }
                Text(
                    entry.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = pair.onContainer,
                    maxLines = 2,
                )
                Text(
                    if (entry.solved) entry.timeClass.orEmpty() else entry.difficulty,
                    style = MaterialTheme.typography.labelSmall,
                    color = pair.onContainer.copy(alpha = 0.7f),
                )
            }
        }
    }
}

@Composable
private fun LoadingCard() {
    Box(
        Modifier.fillMaxWidth().height(180.dp).clip(cardShape(0))
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
    }
}

/**
 * Shown when there is genuinely nothing to solve.
 *
 * Distinguishes "we could not reach the server" from "no problem is scheduled", because they need
 * different things from the reader.
 */
@Composable
private fun NothingScheduledCard(offline: Boolean) {
    Column(
        Modifier.fillMaxWidth().clip(cardShape(0)).background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Symbol(
            if (offline) CaSymbols.cloudDownload else CaSymbols.schedule,
            contentDescription = null,
            size = 32.dp,
            tint = MaterialTheme.colorScheme.outline,
        )
        Text(
            if (offline) "Cannot reach the challenge server" else "No challenge today",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            if (offline) "Check your connection and pull to refresh." else "The next one arrives at midnight UTC.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.Center,
        )
    }
}
