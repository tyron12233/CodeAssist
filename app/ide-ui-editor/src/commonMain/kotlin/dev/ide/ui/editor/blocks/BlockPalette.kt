package dev.ide.ui.editor.blocks

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.block_add_variable
import dev.ide.ui.generated.resources.block_cat_calls
import dev.ide.ui.generated.resources.block_cat_compose
import dev.ide.ui.generated.resources.block_cat_control
import dev.ide.ui.generated.resources.block_cat_logic
import dev.ide.ui.generated.resources.block_cat_math
import dev.ide.ui.generated.resources.block_cat_scope
import dev.ide.ui.generated.resources.block_cat_search
import dev.ide.ui.generated.resources.block_cat_text
import dev.ide.ui.generated.resources.block_cat_variables
import dev.ide.ui.generated.resources.block_no_matches
import dev.ide.ui.generated.resources.block_palette_hint
import dev.ide.ui.generated.resources.block_palette_loading
import dev.ide.ui.generated.resources.block_palette_no_variables
import dev.ide.ui.generated.resources.block_search_placeholder
import dev.ide.ui.generated.resources.block_searching_index
import dev.ide.ui.generated.resources.clear
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ide
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** A palette entry ready to show: its template and laid-out preview (both null while it projects). */
class PaletteEntry(val template: PaletteTemplate, val body: LBody?, val value: LBlock?) {
    val ready: Boolean get() = body != null || value != null
}

/** How a dragged run or value connects, read off its preview. */
fun dragShapeOf(body: LBody?, value: LBlock?): DragShape = DragShape(
    isValue = value != null,
    height = body?.h ?: value?.h ?: 0f,
    terminal = body?.blocks?.lastOrNull()?.terminal == true,
    mouth = body?.blocks?.singleOrNull()?.let(::emptyMouthOf),
    shape = value?.shape ?: ValueShape.Unknown,
)

private fun labelOf(c: PaletteCategory): StringResource = when (c) {
    PaletteCategory.Control -> Res.string.block_cat_control
    PaletteCategory.Logic -> Res.string.block_cat_logic
    PaletteCategory.Math -> Res.string.block_cat_math
    PaletteCategory.Text -> Res.string.block_cat_text
    PaletteCategory.Variables -> Res.string.block_cat_variables
    PaletteCategory.Calls -> Res.string.block_cat_calls
    PaletteCategory.Compose -> Res.string.block_cat_compose
    PaletteCategory.Scope -> Res.string.block_cat_scope
}

/**
 * The block palette: a rail of categories on the left and the selected category's blocks beside it, drawn
 * exactly as they will look on the page. Long-press a block (or press and drag it with a mouse) to lift it
 * onto the canvas through [drag]. Typing in the search field shows [searchResults] instead (index-backed
 * calls and types plus matching templates); [searching] is true while that query runs.
 */
@Composable
internal fun BlockPalette(
    categories: List<PaletteCategory>,
    entriesFor: (PaletteCategory) -> List<PaletteEntry>,
    query: String,
    onQuery: (String) -> Unit,
    searchResults: List<PaletteEntry>,
    searching: Boolean,
    onAddVariable: () -> Unit,
    drag: BlockDrag,
    ink: BlockInk,
    g: BlockGeometry,
    modifier: Modifier = Modifier,
    /** The receiver the Scope tab lists functions for (`AnnotatedString.Builder`), when there is one. */
    scopeTitle: String? = null,
) {
    var selected by rememberSaveable { mutableStateOf(PaletteCategory.Control.name) }
    val current = categories.firstOrNull { it.name == selected } ?: categories.first()
    Row(modifier.background(MaterialTheme.colorScheme.surface)) {
        Column(
            Modifier.width(64.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceContainer).padding(vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            for (c in categories) {
                val on = query.isBlank() && c == current
                Column(
                    Modifier.fillMaxWidth().clickable { selected = c.name; onQuery("") }
                        .background(if (on) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer)
                        .padding(vertical = 7.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.size(18.dp).clip(CircleShape).background(ink.of(c.cat)))
                    Spacer(Modifier.height(3.dp))
                    Text(
                        stringResource(labelOf(c)), fontSize = 10.sp, maxLines = 1, textAlign = TextAlign.Center,
                        color = if (on) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PaletteSearch(query, onQuery)
            val searchingNow = query.isNotBlank()
            val entries = if (searchingNow) searchResults else entriesFor(current)
            Text(
                when {
                    searchingNow -> stringResource(Res.string.block_cat_search)
                    current == PaletteCategory.Scope && scopeTitle != null -> scopeTitle
                    else -> stringResource(labelOf(current))
                },
                style = if (current == PaletteCategory.Scope && !searchingNow) Ide.type.code else MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (!searchingNow && current == PaletteCategory.Variables) {
                Row(
                    Modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primaryContainer)
                        .clickable(onClick = onAddVariable).padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(CaIcons.plus, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(stringResource(Res.string.block_add_variable), color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.labelLarge)
                }
            }
            when {
                searchingNow && searching && entries.isEmpty() -> Hint(stringResource(Res.string.block_searching_index))
                searchingNow && entries.isEmpty() -> Hint(stringResource(Res.string.block_no_matches))
                current == PaletteCategory.Variables && entries.isEmpty() -> Hint(stringResource(Res.string.block_palette_no_variables))
            }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(entries, key = { it.template.text + it.template.isValue }) { e -> PaletteBlock(e, drag, ink, g) }
                item { Hint(stringResource(Res.string.block_palette_hint)) }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
}

@Composable
private fun PaletteSearch(query: String, onQuery: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Icon(CaIcons.search, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.outline)
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text(stringResource(Res.string.block_search_placeholder), color = MaterialTheme.colorScheme.outline, style = Ide.type.codeSmall, maxLines = 1)
            BasicTextField(
                query, onQuery, singleLine = true,
                textStyle = Ide.type.codeSmall.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) Icon(CaIcons.close, stringResource(Res.string.clear), Modifier.size(13.dp).clickable { onQuery("") }, tint = MaterialTheme.colorScheme.outline)
    }
}

/** One palette block, drawn by the canvas painter, that lifts onto the page on long-press (or mouse drag). */
@Composable
private fun PaletteBlock(entry: PaletteEntry, drag: BlockDrag, ink: BlockInk, g: BlockGeometry) {
    if (!entry.ready) {
        Text(stringResource(Res.string.block_palette_loading), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        return
    }
    val d = LocalDensity.current
    val w = entry.body?.w ?: entry.value?.w ?: 0f
    val h = (entry.body?.h ?: entry.value?.h ?: 0f) + g.notchDepth + 2f * g.density
    var origin by remember { mutableStateOf(Offset.Zero) }
    val currentEntry by rememberUpdatedState(entry)
    val ps = PaintState(ink, g)
    Canvas(
        Modifier.size(with(d) { (w + 4f * g.density).toDp() }, with(d) { h.toDp() })
            .onGloballyPositioned { origin = it.positionInRoot() }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val mouse = down.type == PointerType.Mouse
                    val slop = viewConfiguration.touchSlop
                    // Touch lifts on a long-press (a plain swipe scrolls the list); a mouse lifts on movement.
                    val lift = if (mouse) {
                        var moved = false
                        while (true) {
                            val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!c.pressed) break
                            if ((c.position - down.position).getDistance() > slop) { moved = true; break }
                        }
                        moved
                    } else {
                        withTimeoutOrNull((viewConfiguration.longPressTimeoutMillis * 0.6).toLong()) {
                            while (true) {
                                val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                if (!c.pressed || (c.position - down.position).getDistance() > slop) break
                            }
                            false
                        } ?: true
                    }
                    if (!lift) return@awaitEachGesture
                    val e = currentEntry
                    val active = ActiveDrag(DragPayload.Template(e.template), e.body, e.value, dragShapeOf(e.body, e.value), down.position)
                    drag.begin(active, origin + down.position)
                    try {
                        while (true) {
                            val ev = awaitPointerEvent()
                            val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!c.pressed) break
                            drag.move(origin + c.position)
                            c.consume()
                        }
                        drag.release()
                    } finally {
                        if (drag.active === active) drag.cancel()
                    }
                }
            },
    ) {
        drawFloating(entry.body, entry.value, Offset(2f * g.density, 0f), ps)
    }
}
