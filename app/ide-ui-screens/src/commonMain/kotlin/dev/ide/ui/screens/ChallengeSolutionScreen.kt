package dev.ide.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiChallengeSolution
import dev.ide.ui.backend.UiChallengeSolutionEntry
import dev.ide.ui.backend.UiGrowthPoint
import dev.ide.ui.backend.UiSolutionLock
import dev.ide.ui.components.CircularProgressIndicator
import dev.ide.ui.components.CodeSample
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.back
import dev.ide.ui.generated.resources.challenge_complexity_note
import dev.ide.ui.generated.resources.challenge_exponent
import dev.ide.ui.generated.resources.challenge_how_it_scaled
import dev.ide.ui.generated.resources.challenge_legend_reference
import dev.ide.ui.generated.resources.challenge_legend_yours
import dev.ide.ui.generated.resources.challenge_metric_allocated
import dev.ide.ui.generated.resources.challenge_metric_memory
import dev.ide.ui.generated.resources.challenge_metric_runtime
import dev.ide.ui.generated.resources.challenge_metric_time
import dev.ide.ui.generated.resources.challenge_offline_body
import dev.ide.ui.generated.resources.challenge_offline_title
import dev.ide.ui.generated.resources.challenge_rank
import dev.ide.ui.generated.resources.challenge_score
import dev.ide.ui.generated.resources.challenge_solution_attempts
import dev.ide.ui.generated.resources.challenge_solution_by
import dev.ide.ui.generated.resources.challenge_solution_compare
import dev.ide.ui.generated.resources.challenge_solution_counted_note
import dev.ide.ui.generated.resources.challenge_solution_legend_theirs
import dev.ide.ui.generated.resources.challenge_solution_locked_sign_in_body
import dev.ide.ui.generated.resources.challenge_solution_locked_sign_in_title
import dev.ide.ui.generated.resources.challenge_solution_locked_solve_body
import dev.ide.ui.generated.resources.challenge_solution_locked_solve_title
import dev.ide.ui.generated.resources.challenge_solution_meta
import dev.ide.ui.generated.resources.challenge_solution_not_found_body
import dev.ide.ui.generated.resources.challenge_solution_not_found_title
import dev.ide.ui.generated.resources.challenge_solution_solve_time
import dev.ide.ui.generated.resources.challenge_solution_their_code
import dev.ide.ui.generated.resources.challenge_solution_tab_code
import dev.ide.ui.generated.resources.challenge_solution_you
import dev.ide.ui.generated.resources.challenge_solution_your_code
import dev.ide.ui.generated.resources.challenge_today
import dev.ide.ui.icons.CaSymbols
import dev.ide.ui.platform.PlatformBackHandler
import dev.ide.ui.theme.Ca
import dev.ide.ui.theme.Symbol
import dev.ide.ui.theme.tonalPair
import org.jetbrains.compose.resources.stringResource

/**
 * One leaderboard entry opened up: the accepted code, what the judge measured about it, and, when the viewer
 * has solved the same day, their own solution beside it.
 *
 * Whether it may be seen is the server's decision (the day is over, or the viewer has solved it); this screen
 * only explains a refusal. What it shows is the submission the board ranks, never other attempts.
 */
@Composable
fun ChallengeSolutionScreen(
    backend: IdeBackend,
    date: String,
    handle: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** Opens on the side-by-side view when there is one; the snapshot tests use it to render that view. */
    startComparing: Boolean = false,
) {
    var loaded by remember(date, handle) { mutableStateOf<UiChallengeSolution?>(null) }
    LaunchedEffect(date, handle) {
        loaded = runCatching { backend.challenges.solution(date, handle) }
            .getOrDefault(UiChallengeSolution(date = date, lock = UiSolutionLock.Offline))
    }
    PlatformBackHandler(enabled = true, onBack = onBack)

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        val state = loaded
        val entry = state?.solution
        SolutionTopBar(
            title = stringResource(Res.string.challenge_solution_by, entry?.displayName ?: handle),
            subtitle = listOfNotNull(
                state?.title,
                if (date.isBlank()) stringResource(Res.string.challenge_today) else date,
            ).joinToString("  ·  "),
            onBack = onBack,
        )
        when {
            state == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
            }

            !state.open || entry == null -> Locked(state.lock)

            else -> SolutionBody(state, entry, startComparing)
        }
    }
}

@Composable
private fun SolutionTopBar(title: String, subtitle: String, onBack: () -> Unit) {
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
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun SolutionBody(state: UiChallengeSolution, entry: UiChallengeSolutionEntry, startComparing: Boolean) {
    // Comparing needs a solution of the viewer's own that is not this one.
    val mine = state.you?.takeIf { !entry.isYou }
    var comparing by remember(entry.handle) { mutableStateOf(startComparing) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        HeaderCard(entry)

        if (mine != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeChip(stringResource(Res.string.challenge_solution_tab_code), CaSymbols.codeBlocks, !comparing) {
                    comparing = false
                }
                ModeChip(stringResource(Res.string.challenge_solution_compare), CaSymbols.syncAlt, comparing) {
                    comparing = true
                }
            }
        }

        if (comparing && mine != null) {
            CompareTable(entry, mine)
            ScalingCard(entry, state.referenceGrowth, mine)
            CodeBlock(stringResource(Res.string.challenge_solution_their_code), entry)
            CodeBlock(stringResource(Res.string.challenge_solution_your_code), mine)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                MetricTile(stringResource(Res.string.challenge_metric_time), entry.timeClass, state.optimalTime, 0, Modifier.weight(1f))
                MetricTile(stringResource(Res.string.challenge_metric_memory), entry.spaceClass, state.optimalSpace, 1, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                PlainTile(stringResource(Res.string.challenge_metric_runtime), formatRuntime(entry.runtimeNs), Modifier.weight(1f))
                PlainTile(stringResource(Res.string.challenge_metric_allocated), formatAllocated(entry.allocBytes), Modifier.weight(1f))
            }
            ScalingCard(entry, state.referenceGrowth, null)
            CodeBlock(
                stringResource(if (entry.isYou) Res.string.challenge_solution_your_code else Res.string.challenge_solution_their_code),
                entry,
            )
        }

        Text(
            stringResource(if (state.counted) Res.string.challenge_solution_counted_note else Res.string.challenge_complexity_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun HeaderCard(entry: UiChallengeSolutionEntry) {
    val pair = tonalPair(0)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Ca.radius.lg)).background(pair.container).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(Res.string.challenge_score),
                style = MaterialTheme.typography.labelMedium,
                color = pair.onContainer.copy(alpha = 0.7f),
            )
            Text(
                entry.score.toString(),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = pair.onContainer,
            )
            Text(
                stringResource(
                    Res.string.challenge_solution_meta,
                    entry.language.name,
                    formatSolveTime(entry.solveMs),
                    entry.attempts,
                ),
                style = MaterialTheme.typography.labelMedium,
                color = pair.onContainer.copy(alpha = 0.7f),
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                stringResource(Res.string.challenge_rank),
                style = MaterialTheme.typography.labelMedium,
                color = pair.onContainer.copy(alpha = 0.7f),
            )
            Text(
                "#" + entry.rank,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = pair.onContainer,
            )
        }
    }
}

@Composable
private fun ModeChip(label: String, glyph: Char, selected: Boolean, onClick: () -> Unit) {
    val background = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow
    val content = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.clip(RoundedCornerShape(Ca.radius.pill)).background(background).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Symbol(glyph, contentDescription = null, size = 18.dp, tint = content)
        Text(label, style = MaterialTheme.typography.labelLarge, color = content)
    }
}

/**
 * The two solutions metric by metric. The better side of each row is emphasised; a tie emphasises neither,
 * so the table never claims a difference the numbers do not show.
 */
@Composable
private fun CompareTable(theirs: UiChallengeSolutionEntry, mine: UiChallengeSolutionEntry) {
    data class Line(val label: String, val them: String, val you: String, val better: Int)

    fun lower(a: Long, b: Long): Int = when {
        a == b -> 0
        a < b -> -1
        else -> 1
    }

    val lines = listOf(
        Line(stringResource(Res.string.challenge_score), theirs.score.toString(), mine.score.toString(), -lower(theirs.score.toLong(), mine.score.toLong())),
        Line(stringResource(Res.string.challenge_rank), "#" + theirs.rank, "#" + mine.rank, lower(theirs.rank.toLong(), mine.rank.toLong())),
        Line(stringResource(Res.string.challenge_metric_time), theirs.timeClass, mine.timeClass, lower(theirs.timeSteps.toLong(), mine.timeSteps.toLong())),
        Line(stringResource(Res.string.challenge_metric_memory), theirs.spaceClass, mine.spaceClass, lower(theirs.spaceSteps.toLong(), mine.spaceSteps.toLong())),
        Line(stringResource(Res.string.challenge_metric_runtime), formatRuntime(theirs.runtimeNs), formatRuntime(mine.runtimeNs), lower(theirs.runtimeNs, mine.runtimeNs)),
        Line(stringResource(Res.string.challenge_metric_allocated), formatAllocated(theirs.allocBytes), formatAllocated(mine.allocBytes), lower(theirs.allocBytes, mine.allocBytes)),
        Line(stringResource(Res.string.challenge_solution_solve_time), formatSolveTime(theirs.solveMs), formatSolveTime(mine.solveMs), lower(theirs.solveMs, mine.solveMs)),
        Line(stringResource(Res.string.challenge_solution_attempts), theirs.attempts.toString(), mine.attempts.toString(), lower(theirs.attempts.toLong(), mine.attempts.toLong())),
    )

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Ca.radius.lg))
            .background(MaterialTheme.colorScheme.surfaceContainerLow).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.weight(1.2f))
            Text(
                theirs.displayName,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(Res.string.challenge_solution_you),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.tertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        }
        for (line in lines) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    line.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.weight(1.2f),
                )
                CompareCell(line.them, line.better < 0, MaterialTheme.colorScheme.primary, Modifier.weight(1f))
                CompareCell(line.you, line.better > 0, MaterialTheme.colorScheme.tertiary, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun CompareCell(value: String, better: Boolean, accent: Color, modifier: Modifier) {
    Text(
        value,
        style = MaterialTheme.typography.bodyMedium,
        fontFamily = FontFamily.Monospace,
        fontWeight = if (better) FontWeight.Bold else FontWeight.Normal,
        color = if (better) accent else MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        textAlign = TextAlign.End,
        modifier = modifier,
    )
}

@Composable
private fun ScalingCard(entry: UiChallengeSolutionEntry, reference: List<UiGrowthPoint>, mine: UiChallengeSolutionEntry?) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Ca.radius.lg))
            .background(MaterialTheme.colorScheme.surfaceContainerLow).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Symbol(CaSymbols.trendingUp, contentDescription = null, size = 18.dp, tint = MaterialTheme.colorScheme.primary)
            Text(
                stringResource(Res.string.challenge_how_it_scaled),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            entry.growthExponent?.let {
                Text(
                    stringResource(Res.string.challenge_exponent, oneDecimalOf(it)),
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
        GrowthChart(
            points = entry.growth,
            line = MaterialTheme.colorScheme.primary,
            grid = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.fillMaxWidth().height(160.dp),
            reference = reference,
            referenceColor = MaterialTheme.colorScheme.outline,
            comparison = mine?.growth.orEmpty(),
            comparisonColor = MaterialTheme.colorScheme.tertiary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Legend(
                if (entry.isYou) stringResource(Res.string.challenge_legend_yours)
                else stringResource(Res.string.challenge_solution_legend_theirs),
                MaterialTheme.colorScheme.primary,
            )
            if (mine != null && mine.growth.size >= 2) {
                Legend(stringResource(Res.string.challenge_legend_yours), MaterialTheme.colorScheme.tertiary)
            }
            if (reference.size >= 2) {
                Legend(stringResource(Res.string.challenge_legend_reference), MaterialTheme.colorScheme.outline)
            }
        }
    }
}

@Composable
private fun CodeBlock(label: String, entry: UiChallengeSolutionEntry) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Symbol(CaSymbols.codeBlocks, contentDescription = null, size = 18.dp, tint = MaterialTheme.colorScheme.primary)
            Text(
                label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            Text(
                entry.language.name,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        SelectionContainer {
            CodeSample(entry.source.trimEnd(), language = entry.language.wire)
        }
    }
}

@Composable
private fun Locked(lock: UiSolutionLock) {
    val (title, body) = when (lock) {
        UiSolutionLock.SignIn -> Res.string.challenge_solution_locked_sign_in_title to Res.string.challenge_solution_locked_sign_in_body
        UiSolutionLock.SolveFirst -> Res.string.challenge_solution_locked_solve_title to Res.string.challenge_solution_locked_solve_body
        UiSolutionLock.NotFound, UiSolutionLock.None -> Res.string.challenge_solution_not_found_title to Res.string.challenge_solution_not_found_body
        UiSolutionLock.Offline -> Res.string.challenge_offline_title to Res.string.challenge_offline_body
    }
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Symbol(CaSymbols.visibility, contentDescription = null, size = 40.dp, tint = MaterialTheme.colorScheme.outline)
        Text(
            stringResource(title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            stringResource(body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.Center,
        )
    }
}
