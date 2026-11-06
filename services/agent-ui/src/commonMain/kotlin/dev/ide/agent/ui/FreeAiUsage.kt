package dev.ide.agent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ide.agent.ui.generated.resources.Res
import dev.ide.agent.ui.generated.resources.chat_usage_loading
import dev.ide.agent.ui.generated.resources.chat_usage_used_of
import dev.ide.agent.ui.generated.resources.free_ai_usage_ads
import dev.ide.agent.ui.generated.resources.free_ai_usage_ago_d
import dev.ide.agent.ui.generated.resources.free_ai_usage_ago_h
import dev.ide.agent.ui.generated.resources.free_ai_usage_ago_m
import dev.ide.agent.ui.generated.resources.free_ai_usage_ago_now
import dev.ide.agent.ui.generated.resources.free_ai_usage_cache_note
import dev.ide.agent.ui.generated.resources.free_ai_usage_cache_read
import dev.ide.agent.ui.generated.resources.free_ai_usage_cache_write
import dev.ide.agent.ui.generated.resources.free_ai_usage_daily_cap
import dev.ide.agent.ui.generated.resources.free_ai_usage_free_today
import dev.ide.agent.ui.generated.resources.free_ai_usage_none
import dev.ide.agent.ui.generated.resources.free_ai_usage_points
import dev.ide.agent.ui.generated.resources.free_ai_usage_pts
import dev.ide.agent.ui.generated.resources.free_ai_usage_recent
import dev.ide.agent.ui.generated.resources.free_ai_usage_searches
import dev.ide.agent.ui.generated.resources.free_ai_usage_tokens
import dev.ide.agent.ui.generated.resources.free_ai_usage_total
import dev.ide.agent.ui.generated.resources.free_ai_usage_week
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiFreeAiStep
import org.jetbrains.compose.resources.stringResource

/**
 * The Usage sheet's body on CodeAssist Free AI: the balance, today's allowance and daily limit as bars, ads, the last
 * 7 days by model, and the most recent steps with what each cost. Every number comes from our own server.
 */
@Composable
internal fun FreeAiUsageBody(backend: IdeBackend, now: Long) {
    val state by backend.agent.freeAi.collectAsState()
    val usage = state.usage
    val scheme = MaterialTheme.colorScheme
    val small = MaterialTheme.typography.bodySmall
    if (usage == null) {
        Text(state.error ?: stringResource(Res.string.chat_usage_loading), color = scheme.onSurfaceVariant, style = small)
        return
    }
    Column(
        Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            stringResource(Res.string.free_ai_usage_points, formatPoints(usage.points)),
            color = scheme.onSurface, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
        )
        val freeUsed = (usage.dailyFreePoints - usage.freePointsLeft).coerceAtLeast(0.0)
        PointsMeter(
            stringResource(Res.string.free_ai_usage_free_today), freeUsed, usage.dailyFreePoints.toDouble(), usage.resetsAtMs, now,
        )
        PointsMeter(
            stringResource(Res.string.free_ai_usage_daily_cap), usage.spentToday, usage.dailyCapPoints.toDouble(), usage.resetsAtMs, now,
        )
        Text(
            stringResource(Res.string.free_ai_usage_ads, usage.adsToday, usage.adDailyCap, usage.adPoints),
            color = scheme.onSurfaceVariant, style = small,
        )
        WatchAdButton(backend, state, compact = false)

        HorizontalDivider(color = scheme.outlineVariant)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionTitle(stringResource(Res.string.free_ai_usage_week))
            Text(
                stringResource(Res.string.free_ai_usage_total, formatPoints(usage.spentWeek), usage.stepsWeek),
                color = scheme.onSurface, style = MaterialTheme.typography.bodyMedium,
            )
            usage.byModelWeek.forEach { (model, points, steps) ->
                Row {
                    Text(modelLabel(model), color = scheme.onSurfaceVariant, style = small, modifier = Modifier.weight(1f))
                    Text(stringResource(Res.string.free_ai_usage_total, formatPoints(points), steps), color = scheme.onSurfaceVariant, style = small)
                }
            }
        }

        HorizontalDivider(color = scheme.outlineVariant)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionTitle(stringResource(Res.string.free_ai_usage_recent))
            if (usage.recent.isEmpty()) {
                Text(stringResource(Res.string.free_ai_usage_none), color = scheme.onSurfaceVariant, style = small)
            }
            usage.recent.forEach { StepRow(it, now) }
            Text(stringResource(Res.string.free_ai_usage_cache_note), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun SectionTitle(text: String) =
    Text(text, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)

@Composable
private fun PointsMeter(label: String, used: Double, limit: Double, resetsAtMs: Long, now: Long) {
    val scheme = MaterialTheme.colorScheme
    val fraction = if (limit <= 0) 1f else (used / limit).toFloat().coerceIn(0f, 1f)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = scheme.onSurface, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("${(fraction * 100).toInt()}%", color = scheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
        }
        UsageBar(fraction)
        Row {
            Text(
                stringResource(Res.string.chat_usage_used_of, formatPoints(used), formatPoints(limit)),
                color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
            )
            Text(resetsIn(resetsAtMs, now), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun StepRow(step: UiFreeAiStep, now: Long) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${modelLabel(step.model)} · ${ago(step.atMs, now)}",
                color = scheme.onSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(Res.string.free_ai_usage_pts, formatPoints(step.points)),
                color = scheme.onSurface, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
            )
        }
        val parts = buildList {
            add(stringResource(Res.string.free_ai_usage_tokens, tokens(step.input), tokens(step.output)))
            if (step.cacheWrite > 0) add(stringResource(Res.string.free_ai_usage_cache_write, tokens(step.cacheWrite)))
            if (step.cacheRead > 0) add(stringResource(Res.string.free_ai_usage_cache_read, tokens(step.cacheRead)))
            if (step.webSearches > 0) add(stringResource(Res.string.free_ai_usage_searches, step.webSearches))
        }
        Text(parts.joinToString(" · "), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun ago(atMs: Long, now: Long): String {
    val minutes = ((now - atMs) / 60_000).coerceAtLeast(0)
    return when {
        minutes < 1 -> stringResource(Res.string.free_ai_usage_ago_now)
        minutes < 60 -> stringResource(Res.string.free_ai_usage_ago_m, minutes.toInt())
        minutes < 48 * 60 -> stringResource(Res.string.free_ai_usage_ago_h, (minutes / 60).toInt())
        else -> stringResource(Res.string.free_ai_usage_ago_d, (minutes / 1440).toInt())
    }
}

/** "claude-haiku-5-5" -> "Haiku 5.5". */
internal fun modelLabel(id: String): String {
    val parts = id.removePrefix("claude-").split('-')
    if (parts.isEmpty() || parts[0].isEmpty()) return id
    val name = parts[0].replaceFirstChar { it.uppercase() }
    return if (parts.size > 1) "$name ${parts.drop(1).joinToString(".")}" else name
}

/** 19616 -> "19.6K", 840 -> "840". */
private fun tokens(n: Int): String =
    if (n < 1000) n.toString() else formatPoints(n / 1000.0) + "K"
