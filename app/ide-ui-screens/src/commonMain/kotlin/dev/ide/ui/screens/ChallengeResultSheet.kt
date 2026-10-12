package dev.ide.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.UiChallengeProblem
import dev.ide.ui.backend.UiChallengeVerdict
import dev.ide.ui.backend.UiGrowthPoint
import dev.ide.ui.components.BottomSheet
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.challenge_back_to_code
import dev.ide.ui.generated.resources.challenge_complexity_note
import dev.ide.ui.generated.resources.challenge_did_not_compile
import dev.ide.ui.generated.resources.challenge_done
import dev.ide.ui.generated.resources.challenge_exponent
import dev.ide.ui.generated.resources.challenge_hidden_tests_note
import dev.ide.ui.generated.resources.challenge_how_it_scaled
import dev.ide.ui.generated.resources.challenge_judged_on_server
import dev.ide.ui.generated.resources.challenge_keep_improving
import dev.ide.ui.generated.resources.challenge_legend_reference
import dev.ide.ui.generated.resources.challenge_legend_yours
import dev.ide.ui.generated.resources.challenge_matched_reference
import dev.ide.ui.generated.resources.challenge_metric_allocated
import dev.ide.ui.generated.resources.challenge_metric_memory
import dev.ide.ui.generated.resources.challenge_metric_runtime
import dev.ide.ui.generated.resources.challenge_metric_time
import dev.ide.ui.generated.resources.challenge_rank
import dev.ide.ui.generated.resources.challenge_rank_of
import dev.ide.ui.generated.resources.challenge_reference_faster
import dev.ide.ui.generated.resources.challenge_reference_value
import dev.ide.ui.generated.resources.challenge_score
import dev.ide.ui.generated.resources.challenge_tests_passed
import dev.ide.ui.generated.resources.challenge_verdict_accepted
import dev.ide.ui.generated.resources.challenge_verdict_memory_limit
import dev.ide.ui.generated.resources.challenge_verdict_not_judged
import dev.ide.ui.generated.resources.challenge_verdict_rejected
import dev.ide.ui.generated.resources.challenge_verdict_runtime_error
import dev.ide.ui.generated.resources.challenge_verdict_timeout
import dev.ide.ui.generated.resources.challenge_verdict_unknown
import dev.ide.ui.generated.resources.challenge_verdict_wrong_answer
import dev.ide.ui.icons.CaSymbols
import dev.ide.ui.theme.Ca
import dev.ide.ui.theme.Symbol
import dev.ide.ui.theme.tonalPair
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import org.jetbrains.compose.resources.stringResource

/**
 * The verdict.
 *
 * An accepted answer is not the end of the story here: the point of the feature is what the solution
 * cost, so the sheet leads with the measured complexity against the reference and draws the curve that
 * produced it. A rejected answer leads with the one thing that will help, which is the failing example
 * where there is one, and a count where the failure was hidden.
 */
@Composable
fun ChallengeResultSheet(
    verdict: UiChallengeVerdict?,
    problem: UiChallengeProblem,
    onDismiss: () -> Unit,
    onBackToTab: () -> Unit,
) {
    BottomSheet(visible = verdict != null, onDismiss = onDismiss, heightFraction = 0.82f) {
        val result = verdict ?: return@BottomSheet
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            VerdictHeader(result)

            if (result.accepted) {
                ComplexityPanel(result)
                if (result.growth.size >= 3) GrowthCard(result)
                ScorePanel(result)
            } else {
                result.message?.takeIf { it.isNotBlank() }?.let { FailureDetail(it, result) }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(Ca.radius.pill),
                    modifier = Modifier.weight(1f).height(48.dp),
                ) {
                    Text(
                        stringResource(
                            if (result.accepted) Res.string.challenge_keep_improving else Res.string.challenge_back_to_code,
                        ),
                    )
                }
                if (result.accepted) {
                    Button(
                        onClick = onBackToTab,
                        shape = RoundedCornerShape(Ca.radius.pill),
                        modifier = Modifier.weight(1f).height(48.dp),
                    ) { Text(stringResource(Res.string.challenge_done), fontWeight = FontWeight.Bold) }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun VerdictHeader(result: UiChallengeVerdict) {
    val accepted = result.accepted
    val pair = tonalPair(if (accepted) 1 else 2)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Ca.radius.lg)).background(pair.container).padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Symbol(
            if (accepted) CaSymbols.checkCircle else CaSymbols.error,
            contentDescription = null,
            size = 34.dp,
            tint = pair.onContainer,
            filled = true,
        )
        Column(Modifier.weight(1f)) {
            Text(
                headline(result),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = pair.onContainer,
            )
            Text(
                if (result.testsTotal > 0) {
                    stringResource(Res.string.challenge_tests_passed, result.testsPassed, result.testsTotal)
                } else {
                    stringResource(Res.string.challenge_judged_on_server)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = pair.onContainer.copy(alpha = 0.75f),
            )
        }
        val streak = result.streak ?: 0
        if (accepted && streak > 0) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Symbol(CaSymbols.localFireDepartment, contentDescription = null, size = 20.dp, tint = pair.onContainer, filled = true)
                Text(
                    streak.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = pair.onContainer,
                )
            }
        }
    }
}

@Composable
private fun headline(result: UiChallengeVerdict): String = stringResource(
    when {
        result.error != null -> Res.string.challenge_verdict_not_judged
        result.verdict == "accepted" -> Res.string.challenge_verdict_accepted
        result.verdict == "wrong_answer" -> Res.string.challenge_verdict_wrong_answer
        result.verdict == "compile_error" -> Res.string.challenge_did_not_compile
        result.verdict == "runtime_error" -> Res.string.challenge_verdict_runtime_error
        result.verdict == "timeout" -> Res.string.challenge_verdict_timeout
        result.verdict == "memory_limit" -> Res.string.challenge_verdict_memory_limit
        result.verdict == "rejected" -> Res.string.challenge_verdict_rejected
        else -> Res.string.challenge_verdict_unknown
    },
)

/**
 * How the solution scaled against the reference.
 *
 * Both classes are shown side by side because a class on its own means little: matching the reference is
 * the achievement, and being a class behind it is the lesson.
 */
@Composable
private fun ComplexityPanel(result: UiChallengeVerdict) {
    val matched = result.timeClass != null && result.timeClass == result.optimalTime
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Ca.radius.lg))
            .background(MaterialTheme.colorScheme.surfaceContainerLow).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Symbol(CaSymbols.functions, contentDescription = null, size = 18.dp, tint = MaterialTheme.colorScheme.primary)
            Text(
                stringResource(if (matched) Res.string.challenge_matched_reference else Res.string.challenge_reference_faster),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            MetricTile(
                stringResource(Res.string.challenge_metric_time),
                result.timeClass ?: "-",
                result.optimalTime,
                0,
                Modifier.weight(1f),
            )
            MetricTile(
                stringResource(Res.string.challenge_metric_memory),
                result.spaceClass ?: "-",
                result.optimalSpace,
                1,
                Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            PlainTile(
                stringResource(Res.string.challenge_metric_runtime),
                formatRuntime(result.runtimeNs),
                Modifier.weight(1f),
            )
            PlainTile(
                stringResource(Res.string.challenge_metric_allocated),
                formatAllocated(result.allocBytes),
                Modifier.weight(1f),
            )
        }
        Text(
            stringResource(Res.string.challenge_complexity_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun MetricTile(label: String, value: String, reference: String?, index: Int, modifier: Modifier = Modifier) {
    val pair = tonalPair(index)
    Column(
        modifier.clip(RoundedCornerShape(Ca.radius.md)).background(pair.container).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = pair.onContainer.copy(alpha = 0.7f))
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = pair.onContainer,
        )
        if (reference != null && reference != value) {
            Text(
                stringResource(Res.string.challenge_reference_value, reference),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = pair.onContainer.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun PlainTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(Ca.radius.md))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ScorePanel(result: UiChallengeVerdict) {
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
                (result.score ?: 0).toString(),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = pair.onContainer,
            )
        }
        val rank = result.rank
        if (rank != null) {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    stringResource(Res.string.challenge_rank),
                    style = MaterialTheme.typography.labelMedium,
                    color = pair.onContainer.copy(alpha = 0.7f),
                )
                Text(
                    result.total?.let { stringResource(Res.string.challenge_rank_of, rank, it) } ?: "#$rank",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = pair.onContainer,
                )
            }
        }
    }
}

@Composable
private fun FailureDetail(message: String, result: UiChallengeVerdict) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Ca.radius.md))
            .background(MaterialTheme.colorScheme.surfaceContainerLow).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (result.verdict == "compile_error") FontFamily.Monospace else FontFamily.Default,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (result.verdict == "wrong_answer" && result.testsPassed > 0) {
            Text(
                stringResource(Res.string.challenge_hidden_tests_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun GrowthCard(result: UiChallengeVerdict) {
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
            result.growthExponent?.let {
                Text(
                    stringResource(Res.string.challenge_exponent, oneDecimalOf(it)),
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
        GrowthChart(
            points = result.growth,
            line = MaterialTheme.colorScheme.primary,
            grid = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.fillMaxWidth().height(160.dp),
            reference = result.referenceGrowth,
            referenceColor = MaterialTheme.colorScheme.outline,
        )
        if (result.referenceGrowth.size >= 2) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Legend(stringResource(Res.string.challenge_legend_yours), MaterialTheme.colorScheme.primary)
                Legend(stringResource(Res.string.challenge_legend_reference), MaterialTheme.colorScheme.outline)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            result.growth.firstOrNull()?.let { Axis("n = " + it.n, formatRuntime(it.ns)) }
            result.growth.lastOrNull()?.let { Axis("n = " + it.n, formatRuntime(it.ns)) }
        }
    }
}

@Composable
private fun Legend(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(width = 14.dp, height = 3.dp).background(color))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun Axis(size: String, time: String) {
    Column {
        Text(size, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.outline)
        Text(time, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface)
    }
}

/**
 * The measured growth curve, drawn against the reference's.
 *
 * Both series share one pair of axes, which is the only way the picture says anything: normalised
 * separately, an O(n) curve and an O(n^2) curve are both a straight line filling the box, and the reader
 * learns nothing. Together, a solution in the reference's class runs parallel to it and a slower one
 * visibly pulls away.
 *
 * The axes are logarithmic, where a power law is a straight line and its slope is the exponent. On
 * linear axes everything that grows at all looks like the same hockey stick.
 */
@Composable
fun GrowthChart(
    points: List<UiGrowthPoint>,
    line: Color,
    grid: Color,
    modifier: Modifier = Modifier,
    reference: List<UiGrowthPoint> = emptyList(),
    referenceColor: Color = grid,
) {
    if (points.size < 2) return
    Canvas(modifier) {
        val mine = points.filter { it.n > 0 && it.ns > 0 }
        val theirs = reference.filter { it.n > 0 && it.ns > 0 }
        if (mine.size < 2) return@Canvas

        val all = mine + theirs
        val minX = ln(all.minOf { it.n }.toDouble())
        val maxX = ln(all.maxOf { it.n }.toDouble())
        val minY = ln(all.minOf { it.ns }.toDouble())
        val maxY = ln(all.maxOf { it.ns }.toDouble())
        val spanX = max(maxX - minX, 1e-6)
        val spanY = max(maxY - minY, 1e-6)

        val padding = 14f
        val w = size.width - padding * 2
        val h = size.height - padding * 2

        fun at(p: UiGrowthPoint): Offset = Offset(
            padding + ((ln(p.n.toDouble()) - minX) / spanX * w).toFloat(),
            padding + h - ((ln(p.ns.toDouble()) - minY) / spanY * h).toFloat(),
        )

        repeat(4) { row ->
            val y = padding + h * row / 3f
            drawLine(grid, Offset(padding, y), Offset(padding + w, y), strokeWidth = 1f)
        }

        if (theirs.size >= 2) {
            val path = Path().apply {
                moveTo(at(theirs[0]).x, at(theirs[0]).y)
                for (i in 1..theirs.lastIndex) lineTo(at(theirs[i]).x, at(theirs[i]).y)
            }
            drawPath(
                path,
                color = referenceColor,
                style = Stroke(width = 2.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(9f, 7f))),
            )
        }

        val path = Path().apply {
            moveTo(at(mine[0]).x, at(mine[0]).y)
            for (i in 1..mine.lastIndex) lineTo(at(mine[i]).x, at(mine[i]).y)
        }
        drawPath(path, color = line, style = Stroke(width = 3.5f))
        for (p in mine) drawCircle(line, radius = 4.5f, center = at(p))
    }
}

private fun oneDecimalOf(v: Double): String {
    val scaled = kotlin.math.round(v * 100).toLong()
    val whole = scaled / 100
    val frac = kotlin.math.abs(scaled % 100)
    return whole.toString() + "." + (if (frac < 10) "0" + frac else frac.toString())
}
