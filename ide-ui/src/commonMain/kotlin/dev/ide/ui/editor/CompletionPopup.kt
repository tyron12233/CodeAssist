package dev.ide.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.UiCompletionItem
import dev.ide.ui.components.Chip
import dev.ide.ui.components.KindBadge
import dev.ide.ui.components.entrancePop
import dev.ide.ui.theme.Ca

/**
 * The completion list (glass-thick): a top doc strip with the selected item's signature + an "⇥ tab"
 * accept hint, then 42dp rows of [KindBadge] + label (typed prefix bolded in accent) + right-aligned
 * detail. Operable by click and by keyboard (the host handles ↑↓/Tab/Enter/Esc).
 */
@Composable
fun CompletionList(
    items: List<UiCompletionItem>,
    selectedIndex: Int,
    prefix: String,
    width: Dp,
    onPick: (UiCompletionItem) -> Unit,
    onHover: (Int) -> Unit,
    maxListHeight: Dp = 296.dp,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(selectedIndex) {
        if (selectedIndex in items.indices) listState.animateScrollToItem(selectedIndex)
    }
    val selected = items.getOrNull(selectedIndex)

    // The list, and — when the selected item carries javadoc — a doc panel to its right (IntelliJ-style).
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
        Column(
            Modifier
                .width(width)
                .background(Ca.colors.glassThick, RoundedCornerShape(Ca.radius.md))
                .border(1.dp, Ca.colors.separator, RoundedCornerShape(Ca.radius.md)),
        ) {
            // doc strip
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Ca.colors.accent.copy(alpha = 0.07f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = selected?.let { it.label + (it.detail?.let { d -> "  $d" } ?: "") } ?: "No suggestions",
                    color = Ca.colors.accent,
                    style = Ca.type.codeSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Chip("⇥ tab", fill = Ca.colors.surface3, textColor = Ca.colors.textSecondary)
            }

            LazyColumn(state = listState, modifier = Modifier.heightIn(max = maxListHeight)) {
                itemsIndexed(items) { index, item ->
                    CompletionRow(item, prefix, index == selectedIndex, width, onPick = { onPick(item) }, onHover = { onHover(index) })
                }
            }
        }

        val doc = selected?.documentation?.takeIf { it.isNotBlank() }
        if (doc != null) DocPanel(selected, doc, maxListHeight)
    }
}

/** The documentation side panel: the selected item's signature + its javadoc, scrollable. */
@Composable
private fun DocPanel(item: UiCompletionItem, doc: String, maxHeight: Dp) {
    Column(
        Modifier
            .width(320.dp)
            .heightIn(max = maxHeight)
            .background(Ca.colors.glassThick, RoundedCornerShape(Ca.radius.md))
            .border(1.dp, Ca.colors.separator, RoundedCornerShape(Ca.radius.md))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            item.label + (item.detail?.let { "  $it" } ?: ""),
            style = Ca.type.codeSmall,
            color = Ca.colors.accent,
        )
        Spacer(Modifier.height(8.dp))
        Text(doc, style = Ca.type.footnote, color = Ca.colors.textSecondary)
    }
}

@Composable
private fun CompletionRow(
    item: UiCompletionItem,
    prefix: String,
    selected: Boolean,
    rowWidth: Dp,
    onPick: () -> Unit,
    onHover: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(42.dp)
            .background(if (selected) Ca.colors.accentSoft else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onPick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        KindBadge(item.kind)
        // The label (the name) keeps priority via weight(1f); the detail is capped to ~half the row and
        // ellipsizes, so a long `(params): Ret` signature can never squeeze the name out of view.
        Text(
            highlightMatch(item.label, prefix),
            style = Ca.type.code,
            color = Ca.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (item.detail != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                item.detail,
                style = Ca.type.codeSmall,
                color = Ca.colors.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = rowWidth * 0.5f),
            )
        }
    }
}

/**
 * Bold + accent-color the exact characters of [label] that [prefix] matched — the same positions the local
 * filter ([matchPositions]) used to keep the item, so a fuzzy match shows *which* letters caught. A prefix
 * match bolds the leading run; a camel/subsequence match (`lw` → **l**ayout_**w**idth, `nf` → **n**ew**F**ile)
 * bolds each matched letter, adjacent ones merged into one span so the rendered runs stay legible.
 */
@Composable
private fun highlightMatch(label: String, prefix: String): AnnotatedString = buildAnnotatedString {
    append(label)
    if (prefix.isEmpty()) return@buildAnnotatedString
    val pos = matchPositions(label, prefix) ?: return@buildAnnotatedString
    val style = SpanStyle(color = Ca.colors.accent, fontWeight = FontWeight.Bold)
    var k = 0
    while (k < pos.size) {
        val runStart = pos[k]
        var runEnd = runStart
        while (k + 1 < pos.size && pos[k + 1] == runEnd + 1) { runEnd = pos[k + 1]; k++ }
        addStyle(style, runStart, runEnd + 1)
        k++
    }
}
