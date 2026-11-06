package dev.ide.agent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ide.agent.ui.generated.resources.Res
import dev.ide.agent.ui.generated.resources.chat_close
import dev.ide.agent.ui.generated.resources.chat_done
import dev.ide.agent.ui.generated.resources.chat_usage_failed
import dev.ide.agent.ui.generated.resources.chat_usage_free_left
import dev.ide.agent.ui.generated.resources.chat_usage_free_out
import dev.ide.agent.ui.generated.resources.chat_usage_free_requests
import dev.ide.agent.ui.generated.resources.chat_usage_in_use
import dev.ide.agent.ui.generated.resources.chat_usage_loading
import dev.ide.agent.ui.generated.resources.chat_usage_no_limits
import dev.ide.agent.ui.generated.resources.chat_usage_refresh
import dev.ide.agent.ui.generated.resources.chat_usage_resets_dh
import dev.ide.agent.ui.generated.resources.chat_usage_resets_hm
import dev.ide.agent.ui.generated.resources.chat_usage_resets_m
import dev.ide.agent.ui.generated.resources.chat_usage_spend
import dev.ide.agent.ui.generated.resources.chat_usage_spend_left
import dev.ide.agent.ui.generated.resources.chat_usage_spent
import dev.ide.agent.ui.generated.resources.chat_usage_steps_note
import dev.ide.agent.ui.generated.resources.chat_usage_subtitle
import dev.ide.agent.ui.generated.resources.chat_usage_title
import dev.ide.agent.ui.generated.resources.free_ai_usage_title
import dev.ide.agent.ui.generated.resources.chat_usage_unreported
import dev.ide.agent.ui.generated.resources.chat_usage_used_of
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiAgentUsageLimits
import dev.ide.ui.backend.UiUsageMeter
import dev.ide.ui.backend.UiUsageMeterKind
import dev.ide.ui.components.CenteredDialog
import dev.ide.ui.components.Chip
import dev.ide.ui.components.IconButtonCa
import dev.ide.ui.components.PrimaryButton
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToLong

/** At this share of a limit the chat starts showing what is left, the way Claude warns before a limit. */
private const val WARN_FRACTION = 0.8f

/**
 * The usage dialog: one bar per limit the provider reports (free requests today, a spending cap), each with what
 * is used, what it is out of, and when it resets. Reached from the chat menu and from the warning strip.
 */
@Composable
internal fun AgentUsageSheet(backend: IdeBackend, onClose: () -> Unit) {
    val limits by backend.agent.usageLimits.collectAsState()
    // CodeAssist Free AI has no provider-reported limits: its numbers come from our own server.
    val onFreeAi = remember { backend.agent.config().selectedProvider == FREE_AI_PROVIDER }
    val refresh = {
        if (onFreeAi) {
            backend.agent.refreshFreeAi()
            backend.agent.refreshFreeAiUsage()
        } else {
            backend.agent.refreshUsageLimits()
        }
    }
    LaunchedEffect(Unit) { refresh() }
    val now = rememberMinuteClock()
    val scheme = MaterialTheme.colorScheme
    CenteredDialog(visible = true, onDismiss = onClose) {
        Column(
            Modifier.widthIn(max = 460.dp)
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(Ca.radius.xl))
                .background(scheme.surfaceContainerHigh)
                .border(1.dp, scheme.outlineVariant, RoundedCornerShape(Ca.radius.xl))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(if (onFreeAi) Res.string.free_ai_usage_title else Res.string.chat_usage_title),
                    color = scheme.onSurface, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                IconButtonCa(CaIcons.refresh, stringResource(Res.string.chat_usage_refresh), { refresh() }, iconSize = 16, boxSize = 30)
                IconButtonCa(CaIcons.close, stringResource(Res.string.chat_close), onClose, iconSize = 16, boxSize = 30)
            }
            if (onFreeAi) FreeAiUsageBody(backend, now) else UsageBody(limits, now)
            PrimaryButton(stringResource(Res.string.chat_done), onClose, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun UsageBody(limits: UiAgentUsageLimits?, now: Long) {
    val scheme = MaterialTheme.colorScheme
    val small = MaterialTheme.typography.bodySmall
    when {
        limits == null -> Text(stringResource(Res.string.chat_usage_loading), color = scheme.onSurfaceVariant, style = small)
        !limits.reported -> Text(stringResource(Res.string.chat_usage_unreported, limits.providerName), color = scheme.onSurfaceVariant, style = small)
        else -> Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(Res.string.chat_usage_subtitle, limits.providerName), color = scheme.onSurfaceVariant, style = small)
            // The meter that gates the model in use goes first, the way Claude leads with the current session.
            val ordered = limits.meters.sortedByDescending { it.kind == activeKind(limits) }
            ordered.forEach { meter -> MeterRow(meter, inUse = limits.meters.size > 1 && meter.kind == activeKind(limits), now = now) }
            val today = limits.spentTodayUsd
            val month = limits.spentMonthUsd
            val capped = limits.meters.any { it.kind == UiUsageMeterKind.SPEND }
            if (!capped && today != null) {
                Text(stringResource(Res.string.chat_usage_no_limits, usd(today)), color = scheme.onSurfaceVariant, style = small)
            }
            if (capped && today != null && month != null) {
                Text(stringResource(Res.string.chat_usage_spent, usd(today), usd(month)), color = scheme.onSurfaceVariant, style = small)
            }
            if (limits.meters.any { it.kind == UiUsageMeterKind.FREE_REQUESTS }) {
                Text(stringResource(Res.string.chat_usage_steps_note), color = scheme.onSurfaceVariant, style = small)
            }
            limits.error?.let { Text(stringResource(Res.string.chat_usage_failed, it), color = scheme.error, style = MaterialTheme.typography.labelSmall) }
        }
    }
}

/** One limit: its name, "N of M used" with the percentage, the bar, and when it resets. */
@Composable
private fun MeterRow(meter: UiUsageMeter, inUse: Boolean, now: Long) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(if (meter.kind == UiUsageMeterKind.FREE_REQUESTS) Res.string.chat_usage_free_requests else Res.string.chat_usage_spend),
                    color = scheme.onSurface, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (inUse) Chip(stringResource(Res.string.chat_usage_in_use), fill = scheme.secondaryContainer, textColor = scheme.onSecondaryContainer)
            }
            Text("${(meter.fraction * 100).toInt()}%", color = scheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
        }
        UsageBar(meter.fraction)
        Row {
            Text(
                stringResource(Res.string.chat_usage_used_of, amount(meter, meter.used), amount(meter, meter.limit)),
                color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            meter.resetsAtMs?.let { Text(resetsIn(it, now), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/**
 * A thin strip over the composer once the limit that gates the current model is [WARN_FRACTION] used: what is left
 * and when it comes back. Taps open the usage dialog. Nothing shows below the threshold, so it never nags.
 */
@Composable
internal fun UsageWarningStrip(limits: UiAgentUsageLimits?, onOpen: () -> Unit) {
    if (limits == null || !limits.reported) return
    val meter = limits.meters.firstOrNull { it.kind == activeKind(limits) } ?: return
    if (meter.fraction < WARN_FRACTION) return
    val now = rememberMinuteClock()
    val scheme = MaterialTheme.colorScheme
    val out = meter.remaining <= 0.0
    val label = when {
        meter.kind == UiUsageMeterKind.FREE_REQUESTS && out -> stringResource(Res.string.chat_usage_free_out)
        meter.kind == UiUsageMeterKind.FREE_REQUESTS -> stringResource(Res.string.chat_usage_free_left, meter.remaining.toInt())
        else -> stringResource(Res.string.chat_usage_spend_left, usd(meter.remaining))
    }
    Column(
        Modifier.fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 8.dp)
            .clip(RoundedCornerShape(Ca.radius.md))
            .background(if (out) scheme.errorContainer else scheme.surfaceContainerHigh)
            .clickable(onClick = onOpen)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val text = if (out) scheme.onErrorContainer else scheme.onSurface
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(if (out) CaIcons.warning else CaIcons.info, null, Modifier.size(14.dp), tint = text)
            Text(label, color = text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            meter.resetsAtMs?.let { Text(resetsIn(it, now), color = text, style = MaterialTheme.typography.labelSmall) }
        }
        UsageBar(meter.fraction, height = 4)
    }
}

@Composable
internal fun UsageBar(fraction: Float, height: Int = 8) {
    val scheme = MaterialTheme.colorScheme
    val fill: Color = when {
        fraction >= 1f -> scheme.error
        fraction >= WARN_FRACTION -> scheme.tertiary
        else -> scheme.primary
    }
    val shape = RoundedCornerShape(Ca.radius.pill)
    Box(Modifier.fillMaxWidth().height(height.dp).clip(shape).background(scheme.surfaceContainerHighest)) {
        if (fraction > 0f) Box(Modifier.fillMaxWidth(fraction.coerceAtLeast(0.02f)).fillMaxHeight().clip(shape).background(fill))
    }
}

/** The limit that gates the model in use: free requests on a free model, else the spending cap. */
private fun activeKind(limits: UiAgentUsageLimits): UiUsageMeterKind =
    if (limits.usingFreeModel || limits.meters.none { it.kind == UiUsageMeterKind.SPEND }) UiUsageMeterKind.FREE_REQUESTS else UiUsageMeterKind.SPEND

@Composable
internal fun resetsIn(atMs: Long, now: Long): String {
    val minutes = ((atMs - now) / 60_000).coerceAtLeast(1)
    return if (minutes >= 48 * 60) {
        stringResource(Res.string.chat_usage_resets_dh, (minutes / 1440).toInt(), (minutes % 1440 / 60).toInt())
    } else if (minutes >= 60) {
        stringResource(Res.string.chat_usage_resets_hm, (minutes / 60).toInt(), (minutes % 60).toInt())
    } else {
        stringResource(Res.string.chat_usage_resets_m, minutes.toInt())
    }
}

private fun amount(meter: UiUsageMeter, value: Double): String =
    if (meter.kind == UiUsageMeterKind.SPEND) usd(value) else value.toLong().toString()

/** Dollars to the cent; a non-zero amount under a cent shows as "<$0.01" rather than a misleading "$0.00". */
internal fun usd(value: Double): String {
    val cents = (value * 100).roundToLong()
    if (value > 0 && cents == 0L) return "<$0.01"
    return "$" + (cents / 100) + "." + (cents % 100).toString().padStart(2, '0')
}

/** The wall clock, re-read every minute so "Resets in" counts down while a view is open. */
@Composable
private fun rememberMinuteClock(): Long {
    var now by remember { mutableLongStateOf(epochMs()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L - now % 60_000L)
            now = epochMs()
        }
    }
    return now
}

private fun epochMs(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()
