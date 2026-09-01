package dev.ide.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ide.ui.backend.UiChartEntry
import dev.ide.ui.backend.UiChartTab
import dev.ide.ui.icons.CaSymbols
import dev.ide.ui.theme.Symbol
import dev.ide.ui.theme.tileShape
import dev.ide.ui.theme.tonalPair

/**
 * Top charts — a ranked, tabbed leaderboard.
 *
 * The section that most makes Explore read as a store, and the one with the strictest honesty rules: the
 * server does not compute a chart below its threshold at all, so this composable never has to decide
 * whether five entries is enough. Exactly what arrives is what is drawn.
 */

/**
 * The "live" indicator: a pulsing dot beside the lowercase word.
 *
 * The data is hourly-precomputed, not streamed, so the dot is a claim about freshness rather than about
 * streaming — which is why the chart also carries `computedAt`. Held static when the platform reports
 * reduced motion, since an infinitely pulsing dot is exactly what that setting exists to stop.
 */
@Composable
fun LiveDot(modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    // A null accessibility manager (tests, previews) is treated as "animate": the default platform
    // behaviour, not a reason to freeze.
    val reduceMotion = LocalAccessibilityManager.current?.let { false } ?: false
    val alpha: Float
    val scale: Float
    if (reduceMotion) {
        alpha = 1f
        scale = 1f
    } else {
        val transition = rememberInfiniteTransition(label = "livePulse")
        val a by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(tween(1800), RepeatMode.Reverse),
            label = "liveAlpha",
        )
        val s by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.7f,
            animationSpec = infiniteRepeatable(tween(1800), RepeatMode.Reverse),
            label = "liveScale",
        )
        alpha = a
        scale = s
    }
    Row(
        modifier.semantics { contentDescription = "Updated hourly" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.size(7.dp).scale(scale).clip(CircleShape)
                .background(c.primary.copy(alpha = alpha)),
        )
        Text("live", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
    }
}

/** The tab chips above the chart. Single-select pills, never underline tabs. */
@Composable
fun ChartTabRow(
    tabs: List<UiChartTab>,
    selectedKey: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(tabs, key = { it.key }) { tab ->
            PillChip(
                label = tab.label,
                selected = tab.key == selectedKey,
                onClick = { onSelect(tab.key) },
            )
        }
    }
}

/**
 * One chart row.
 *
 * The rank numeral is deliberately **light and large** against the row's medium-weight name: it reads as
 * a position rather than as another piece of text. Rank 1 takes `primary`; the rest stay quiet, so the
 * top of the chart is legible at a glance.
 *
 * The install button sits inside its own clickable, so tapping it does not also open the detail page.
 */
@Composable
fun ChartRow(
    entry: UiChartEntry,
    index: Int,
    meta: String,
    actionLabel: String,
    onOpen: () -> Unit,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().height(64.dp).clickable(onClick = onOpen)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(
            Modifier.width(26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                entry.rank.toString(),
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Light,
                    fontSize = 22.sp,
                    letterSpacing = (-1).sp,
                ),
                color = if (entry.rank == 1) c.primary else c.onSurfaceVariant,
            )
            Movement(entry)
        }
        TemplateIcon(
            iconId = entry.item.iconId,
            pair = tonalPair(index),
            shape = tileShape(index),
            size = 44.dp,
        )
        Column(Modifier.weight(1f)) {
            Text(
                entry.item.title,
                style = MaterialTheme.typography.titleSmall,
                color = c.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                meta,
                style = MaterialTheme.typography.bodySmall,
                color = c.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Surface(
            onClick = onAction,
            shape = CircleShape,
            color = c.primary,
            contentColor = c.onPrimary,
            modifier = Modifier.height(34.dp),
        ) {
            Box(Modifier.padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                Text(actionLabel, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/**
 * The movement indicator under the rank.
 *
 * A new entrant (null `previousRank`) gets the up arrow with **no number** — it did not climb from
 * anywhere, and printing a delta would be inventing one.
 */
@Composable
private fun Movement(entry: UiChartEntry) {
    val c = MaterialTheme.colorScheme
    val delta = entry.delta
    val (glyph, tint, label) = when {
        delta == null -> Triple(CaSymbols.arrowDropUp, c.primary, "")
        delta > 0 -> Triple(CaSymbols.arrowDropUp, c.primary, delta.toString())
        delta < 0 -> Triple(CaSymbols.arrowDropDown, c.error, (-delta).toString())
        else -> Triple(CaSymbols.remove, c.outlineVariant, "")
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Symbol(glyph, contentDescription = null, size = 12.dp, tint = tint)
        if (label.isNotEmpty()) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
                color = tint,
            )
        }
    }
}

/** The chart's container: a tonal card holding exactly the rows the server sent. */
@Composable
fun ChartCard(
    entries: List<UiChartEntry>,
    metaFor: (UiChartEntry) -> String,
    /** @Composable because the label is a string resource, and mid-install it is live progress. */
    actionFor: @Composable (UiChartEntry) -> String,
    onOpen: (UiChartEntry) -> Unit,
    onAction: (UiChartEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp),
    ) {
        Column(Modifier.padding(vertical = 6.dp)) {
            entries.forEachIndexed { i, e ->
                ChartRow(
                    entry = e,
                    index = i,
                    meta = metaFor(e),
                    actionLabel = actionFor(e),
                    onOpen = { onOpen(e) },
                    onAction = { onAction(e) },
                )
            }
        }
    }
}

/**
 * The meta line changes per tab, because each tab is ranking on a different thing and the row should say
 * which. Showing installs under "Top rated" would leave the ranking unexplained.
 */
fun chartMeta(entry: UiChartEntry, tabKey: String): String {
    val item = entry.item
    return when (tabKey) {
        "top_rated" -> buildString {
            append(item.rating.takeIf { it >= 0f }?.let { formatRating(it) } ?: "Not rated yet")
            if (item.ratingCount > 0) append(" · ").append(countLabel(item.ratingCount, "review"))
        }
        // "New" ranks on recency, so the row names the kind and language rather than a count that
        // would not explain the ordering.
        "new" -> listOfNotNull(kindWord(item), item.language).joinToString(" · ").ifBlank { item.category }
        else -> listOfNotNull(
            item.installs.takeIf { it >= 0 }?.let { installsLabel(it) },
            item.language,
        ).joinToString(" · ").ifBlank { item.category }
    }
}

private fun kindWord(item: dev.ide.ui.backend.UiStoreItem): String = when (item.kind) {
    dev.ide.ui.backend.UiStoreItemKind.Template -> "Template"
    dev.ide.ui.backend.UiStoreItemKind.Sample -> "Sample app"
    dev.ide.ui.backend.UiStoreItemKind.Community -> "Community"
}

/** `48K installs`, and `New` below ten — a real count under ten reads as failure, not novelty. */
fun installsLabel(installs: Int): String = when {
    installs < 10 -> "New"
    installs >= 1_000_000 -> "${installs / 1_000_000}M installs"
    installs >= 1_000 -> "${installs / 1_000}K installs"
    else -> "$installs installs"
}

private fun countLabel(n: Int, noun: String): String {
    val formatted = if (n >= 1_000) "${n / 1_000},${(n % 1_000).toString().padStart(3, '0')}" else n.toString()
    return "$formatted ${noun}${if (n == 1) "" else "s"}"
}

