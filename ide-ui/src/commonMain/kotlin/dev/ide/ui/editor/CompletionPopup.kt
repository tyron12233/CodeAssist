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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.completion_back_to_suggestions
import dev.ide.ui.generated.resources.completion_no_suggestions
import dev.ide.ui.generated.resources.completion_show_documentation
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.components.entrancePop
import dev.ide.ui.theme.Ca
import org.jetbrains.compose.resources.stringResource

/**
 * The completion list (glass-thick): rows of [KindBadge] + a two-line column — the label (typed prefix bolded
 * in accent) on top, then a muted secondary line with the origin (package / declaring class) on the left and
 * the return/value type on the right. When the selected item carries javadoc, a doc panel sits to the right.
 * Operable by click and by keyboard (the host handles ↑↓/Tab/Enter/Esc).
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
    // Wide screens put the doc panel beside the list; narrow screens (no room) flip the popup to docs on demand.
    docsBeside: Boolean = true,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(selectedIndex) {
        if (selectedIndex in items.indices) listState.animateScrollToItem(selectedIndex)
    }
    val selected = items.getOrNull(selectedIndex)
    val doc = selected?.documentation?.takeIf { it.isNotBlank() }

    if (docsBeside) {
        // Wide: the list with a doc panel to its right (IntelliJ-style).
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
            CompletionListPanel(items, selectedIndex, prefix, width, maxListHeight, listState, onPick, onHover, onInfo = null)
            if (doc != null) DocPanel(selected, doc, maxListHeight, Modifier.width(320.dp))
        }
    } else {
        // Narrow: a side panel would squish, so flip the SAME popup between the list and full-width docs. The
        // selected row carries an ⓘ; tapping it shows the docs (a ‹ Back returns), and a new selection resets
        // to the list.
        var showingDocs by remember { mutableStateOf(false) }
        LaunchedEffect(selectedIndex) { showingDocs = false }
        if (showingDocs && doc != null) {
            DocPanel(selected, doc, maxListHeight, Modifier.width(width), onBack = { showingDocs = false })
        } else {
            CompletionListPanel(
                items, selectedIndex, prefix, width, maxListHeight, listState, onPick, onHover,
                onInfo = if (doc != null) ({ showingDocs = true }) else null,
            )
        }
    }
}

/** The list box (glass): rows in a [LazyColumn], or a placeholder when empty. When [onInfo] is non-null the
 *  SELECTED row shows an ⓘ that calls it (used on narrow screens to flip to the docs view). */
@Composable
private fun CompletionListPanel(
    items: List<UiCompletionItem>,
    selectedIndex: Int,
    prefix: String,
    width: Dp,
    maxListHeight: Dp,
    listState: LazyListState,
    onPick: (UiCompletionItem) -> Unit,
    onHover: (Int) -> Unit,
    onInfo: (() -> Unit)?,
) {
    Column(
        Modifier
            .width(width)
            .background(Ca.colors.glassThick, RoundedCornerShape(Ca.radius.md))
            .border(1.dp, Ca.colors.separator, RoundedCornerShape(Ca.radius.md)),
    ) {
        if (items.isEmpty()) {
            Text(
                stringResource(Res.string.completion_no_suggestions),
                style = Ca.type.codeSmall,
                color = Ca.colors.textTertiary,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            )
        } else {
            LazyColumn(state = listState, modifier = Modifier.heightIn(max = maxListHeight)) {
                itemsIndexed(items) { index, item ->
                    val sel = index == selectedIndex
                    CompletionRow(
                        item, prefix, sel,
                        onPick = { onPick(item) }, onHover = { onHover(index) },
                        onInfo = if (sel) onInfo else null,
                    )
                }
            }
        }
    }
}

/** The documentation panel: the selected item's signature (with a ‹ Back when [onBack] is set, i.e. the
 *  narrow flip view) over its scrollable javadoc. */
@Composable
private fun DocPanel(item: UiCompletionItem, doc: String, maxHeight: Dp, modifier: Modifier = Modifier, onBack: (() -> Unit)? = null) {
    Column(
        modifier
            .heightIn(max = maxHeight)
            .background(Ca.colors.glassThick, RoundedCornerShape(Ca.radius.md))
            .border(1.dp, Ca.colors.separator, RoundedCornerShape(Ca.radius.md)),
    ) {
        // Fixed header: optional Back + the signature, so they stay put while the doc body scrolls.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (onBack != null) {
                Icon(CaIcons.chevronLeft, stringResource(Res.string.completion_back_to_suggestions), Modifier.size(18.dp).clickable(onClick = onBack), tint = Ca.colors.textSecondary)
            }
            Text(
                item.label + (item.detail?.let { "  $it" } ?: ""),
                style = Ca.type.codeSmall,
                color = Ca.colors.accent,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            doc,
            style = Ca.type.footnote,
            color = Ca.colors.textSecondary,
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp).padding(bottom = 10.dp),
        )
    }
}

@Composable
private fun CompletionRow(
    item: UiCompletionItem,
    prefix: String,
    selected: Boolean,
    onPick: () -> Unit,
    onHover: () -> Unit,
    // Non-null on the selected row when docs are reachable via flip (narrow screens): shows a tappable ⓘ.
    onInfo: (() -> Unit)? = null,
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
        // Two stacked lines: the name on top, then a muted secondary line carrying the ORIGIN (a top-level
        // callable's / type's package, or a member's declaring class) on the LEFT and the return/value TYPE on
        // the RIGHT. IntelliJ keeps both on one line, but a narrow popup can't fit that, so they stack under the
        // name. The second line is dropped when neither is present (e.g. a bare local with no inferred type).
        Column(Modifier.weight(1f)) {
            Text(
                highlightMatch(item.label, prefix),
                style = labelStyle,
                color = Ca.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.container != null || item.detail != null) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Origin (package / declaring class) — flexes + ellipsizes so a long package can't shove
                    // the type off the row. Empty (but still weighted) when there's no origin, so the type
                    // stays right-aligned.
                    Text(
                        item.container ?: "",
                        style = detailStyle,
                        color = Ca.colors.textTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (item.detail != null) {
                        Text(
                            item.detail,
                            style = detailStyle,
                            color = Ca.colors.textTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.End,
                        )
                    }
                }
            }
        }
        // Docs affordance (narrow screens): its own click consumes the tap, so it opens the docs instead of
        // accepting the item.
        if (onInfo != null) {
            Spacer(Modifier.width(4.dp))
            Icon(CaIcons.info, stringResource(Res.string.completion_show_documentation), Modifier.size(18.dp).clickable(onClick = onInfo), tint = Ca.colors.textSecondary)
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
