package dev.ide.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.IndexUiBuildStats
import dev.ide.ui.backend.IndexUiStatus
import dev.ide.ui.backend.IndexWorkItem
import dev.ide.ui.backend.IndexWorkState
import dev.ide.ui.backend.IndexerUiStat
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.close
import dev.ide.ui.generated.resources.index_artifacts
import dev.ide.ui.generated.resources.index_build_cache
import dev.ide.ui.generated.resources.index_build_phases
import dev.ide.ui.generated.resources.index_count_artifacts
import dev.ide.ui.generated.resources.index_count_files
import dev.ide.ui.generated.resources.index_current_file
import dev.ide.ui.generated.resources.index_idle_body
import dev.ide.ui.generated.resources.index_indexers
import dev.ide.ui.generated.resources.index_more
import dev.ide.ui.generated.resources.index_ms
import dev.ide.ui.generated.resources.index_reindex
import dev.ide.ui.generated.resources.index_title_building
import dev.ide.ui.generated.resources.index_title_idle
import dev.ide.ui.generated.resources.index_up_to_date
import dev.ide.ui.generated.resources.index_working
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca
import org.jetbrains.compose.resources.stringResource

/**
 * The index-status detail dialog, opened by tapping the top-bar [IndexStatusChip]. While the index is
 * building it shows the current phase, an overall progress bar with a unit count, and the worklist of
 * artifacts/files (with per-item state); when idle it confirms the index is up to date. A Re-index action
 * lets the user rebuild from here.
 */
@Composable
fun IndexStatusDialog(
    visible: Boolean,
    status: IndexUiStatus,
    onReindex: () -> Unit,
    onDismiss: () -> Unit,
) {
    CenteredDialog(visible = visible, onDismiss = onDismiss) {
        Column(
            Modifier
                .widthIn(max = 460.dp)
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .background(Ca.colors.glassThick, RoundedCornerShape(Ca.radius.xl))
                .border(1.dp, Ca.colors.glassEdge, RoundedCornerShape(Ca.radius.xl))
                .padding(20.dp),
        ) {
            Header(status, onDismiss)
            Spacer(Modifier.height(14.dp))
            if (status.building) BuildingBody(status) else IdleBody()
            // Per-indexer breakdown: which index cost the most (live while building, last build's when idle),
            // plus a summary of the phase split + cache reuse. Only the indexers that took real time (≥1ms).
            val breakdown = status.breakdown.filter { it.indexMs >= 1 }
            if (breakdown.isNotEmpty() || status.stats != null) {
                Spacer(Modifier.height(16.dp))
                IndexerBreakdown(breakdown, status.stats)
            }
            Spacer(Modifier.height(16.dp))
            ReindexButton { onReindex(); onDismiss() }
        }
    }
}

@Composable
private fun Header(status: IndexUiStatus, onDismiss: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            CaIcons.layers, null, Modifier.size(18.dp),
            tint = if (status.building) Ca.colors.accent else Ca.colors.success,
        )
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(if (status.building) Res.string.index_title_building else Res.string.index_title_idle),
                color = Ca.colors.textPrimary,
                style = Ca.type.subhead,
                fontWeight = FontWeight.SemiBold,
            )
            val sub = when {
                status.building && status.phase.isNotEmpty() -> status.phase
                status.building -> stringResource(Res.string.index_working)
                else -> stringResource(Res.string.index_up_to_date)
            }
            Text(sub, color = Ca.colors.textTertiary, style = Ca.type.caption)
        }
        Icon(
            CaIcons.close, stringResource(Res.string.close), tint = Ca.colors.textTertiary,
            modifier = Modifier
                .clip(RoundedCornerShape(Ca.radius.sm))
                .clickable(onClick = onDismiss)
                .padding(2.dp)
                .size(18.dp),
        )
    }
}

@Composable
private fun BuildingBody(status: IndexUiStatus) {
    val barShape = RoundedCornerShape(Ca.radius.pill)
    if (status.fraction in 0.0..1.0) {
        LinearProgressIndicator(
            progress = { status.fraction.toFloat() },
            modifier = Modifier.fillMaxWidth().height(4.dp).clip(barShape),
            color = Ca.colors.accent, trackColor = Ca.colors.surface2,
        )
    } else {
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth().height(4.dp).clip(barShape),
            color = Ca.colors.accent, trackColor = Ca.colors.surface2,
        )
    }
    if (status.total > 0) {
        Spacer(Modifier.height(8.dp))
        val count = if (status.phase == "Project source")
            stringResource(Res.string.index_count_files, status.processed, status.total)
        else stringResource(Res.string.index_count_artifacts, status.processed, status.total)
        Text(count, color = Ca.colors.textTertiary, style = Ca.type.caption)
    }
    if (status.items.isNotEmpty()) {
        Spacer(Modifier.height(14.dp))
        val header =
            if (status.phase == "Project source") stringResource(Res.string.index_current_file) else stringResource(
                Res.string.index_artifacts
            )
        Text(
            header,
            color = Ca.colors.textTertiary,
            style = Ca.type.caption2,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(6.dp))
        val listState = rememberLazyListState()
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            items(status.items) { item -> WorkItemRow(item) }
        }
    }
}

@Composable
private fun IdleBody() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(CaIcons.check, null, Modifier.size(15.dp), tint = Ca.colors.success)
        Text(
            stringResource(Res.string.index_idle_body),
            color = Ca.colors.textSecondary, style = Ca.type.footnote,
        )
    }
}

@Composable
private fun WorkItemRow(item: IndexWorkItem) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (item.state) {
            IndexWorkState.DONE -> Icon(
                CaIcons.check,
                null,
                Modifier.size(14.dp),
                tint = Ca.colors.success
            )

            IndexWorkState.ACTIVE -> CircularProgressIndicator(
                Modifier.size(12.dp),
                color = Ca.colors.accent,
                strokeWidth = 2.dp
            )

            IndexWorkState.PENDING -> Icon(
                CaIcons.dot,
                null,
                Modifier.size(14.dp),
                tint = Ca.colors.textTertiary.copy(alpha = 0.5f)
            )
        }
        Text(
            item.label,
            color = if (item.state == IndexWorkState.PENDING) Ca.colors.textTertiary else Ca.colors.textSecondary,
            style = Ca.type.caption, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** The per-indexer time breakdown: a mini bar chart of each index's cost (slowest first) plus a one-line
 *  summary of the build's phase split + cache reuse. Answers "which index is taking the time". */
@Composable
private fun IndexerBreakdown(breakdown: List<IndexerUiStat>, stats: IndexUiBuildStats?) {
    stats?.let { s ->
        Text(
            stringResource(Res.string.index_build_cache, s.artifacts, s.artifactsReused),
            color = Ca.colors.textTertiary, style = Ca.type.caption,
        )
        if (s.libMs > 0 || s.sourceMs > 0) {
            Text(
                stringResource(Res.string.index_build_phases, s.libMs.toInt(), s.sourceMs.toInt()),
                color = Ca.colors.textTertiary, style = Ca.type.caption,
            )
        }
    }
    if (breakdown.isNotEmpty()) {
        if (stats != null) Spacer(Modifier.height(12.dp))
        Text(
            stringResource(Res.string.index_indexers),
            color = Ca.colors.textTertiary, style = Ca.type.caption2, fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        val maxMs = breakdown.maxOf { it.indexMs }.coerceAtLeast(1L)
        val shown = breakdown.take(12)
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            shown.forEach { IndexerRow(it, maxMs) }
        }
        if (breakdown.size > shown.size) {
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(Res.string.index_more, breakdown.size - shown.size),
                color = Ca.colors.textTertiary, style = Ca.type.caption2,
            )
        }
    }
}

/** One indexer row: the index id, its time, and a proportional bar (relative to the slowest index). */
@Composable
private fun IndexerRow(stat: IndexerUiStat, maxMs: Long) {
    val shape = RoundedCornerShape(Ca.radius.sm)
    val fraction = (stat.indexMs.toFloat() / maxMs.toFloat()).coerceIn(0.04f, 1f)
    Box(
        Modifier.fillMaxWidth().height(22.dp).clip(shape).background(Ca.colors.surface2),
    ) {
        Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().clip(shape).background(Ca.colors.accentSoft))
        Row(
            Modifier.fillMaxSize().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stat.id,
                color = Ca.colors.textSecondary, style = Ca.type.caption,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(Res.string.index_ms, stat.indexMs.toInt()),
                color = Ca.colors.textPrimary, style = Ca.type.caption2, fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun ReindexButton(onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Ca.radius.control))
            .background(Ca.colors.accentSoft)
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(CaIcons.refresh, null, Modifier.size(15.dp), tint = Ca.colors.accent)
        Text(
            stringResource(Res.string.index_reindex),
            color = Ca.colors.accent,
            style = Ca.type.footnote,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
    }
}
