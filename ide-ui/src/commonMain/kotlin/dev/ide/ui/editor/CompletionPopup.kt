package dev.ide.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ide.ui.backend.UiCompletionItem
import dev.ide.ui.components.KindBadge
import dev.ide.ui.components.entrancePop
import dev.ide.ui.theme.Ca

/**
 * The completion list (glass-thick): rows of [KindBadge] + a two-line left column (the label, typed prefix
 * bolded in accent, over the detail/signature) + the right-aligned origin (package or declaring class). When
 * the selected item carries javadoc, a doc panel sits to the right. Operable by click and by keyboard (the
 * host handles ↑↓/Tab/Enter/Esc).
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
            // No top signature strip: each row now shows the full info (name + signature + origin), so the
            // strip was redundant. An empty result still gets a placeholder so the popup isn't a blank box.
            if (items.isEmpty()) {
                Text(
                    "No suggestions",
                    style = Ca.type.codeSmall,
                    color = Ca.colors.textTertiary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                )
            } else {
                LazyColumn(state = listState, modifier = Modifier.heightIn(max = maxListHeight)) {
                    itemsIndexed(items) { index, item ->
                        CompletionRow(item, prefix, index == selectedIndex, width, onPick = { onPick(item) }, onHover = { onHover(index) })
                    }
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
    // Row text styles: a notch smaller than the editor's code style, with tight line height so the two stacked
    // lines stay compact. Kept local so the editor's own Ca.type.code is untouched.
    val labelStyle = Ca.type.code.copy(fontSize = 12.sp, lineHeight = 15.sp)
    val detailStyle = Ca.type.codeSmall.copy(fontSize = 11.sp, lineHeight = 13.sp)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .background(if (selected) Ca.colors.accentSoft else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onPick)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        KindBadge(item.kind)
        // Left: two stacked lines — the name on top, its signature/type below. Right: the origin (a type's
        // package or a member's declaring class), dimmed and end-aligned. The name/detail column takes the
        // weight so a long origin can't squeeze it; the origin caps at ~40% of the row and ellipsizes.
        Column(Modifier.weight(1f)) {
            Text(
                highlightMatch(item.label, prefix),
                style = labelStyle,
                color = Ca.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.detail != null) {
                Text(
                    item.detail,
                    style = detailStyle,
                    color = Ca.colors.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (item.container != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                item.container,
                style = detailStyle,
                color = Ca.colors.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                modifier = Modifier.widthIn(max = rowWidth * 0.4f),
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
