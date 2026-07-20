package dev.ide.ui.editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.UiAction
import dev.ide.ui.backend.UiActionKind
import dev.ide.ui.backend.UiSeverity
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.codeaction_dismiss
import dev.ide.ui.generated.resources.codeaction_preview_composable
import dev.ide.ui.generated.resources.codeaction_quick_fixes
import dev.ide.ui.generated.resources.codeaction_severity_error
import dev.ide.ui.generated.resources.codeaction_severity_hint
import dev.ide.ui.generated.resources.codeaction_severity_info
import dev.ide.ui.generated.resources.codeaction_severity_unused
import dev.ide.ui.generated.resources.codeaction_severity_warning
import dev.ide.ui.generated.resources.codeaction_show_context_actions
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * The editor code-action affordances (intentions + quick-fixes), styled like the completion popup:
 *  - [FloatingLightbulb] — the chip floated just above the caret when actions exist; tap to open.
 *  - [CodeActionsMenu] — the popup list. Operable by click and by keyboard (the editor drives ↑↓/⏎/Esc).
 *
 * Both are pure UI over the neutral [UiAction] DTO; all state + the `actionsAt`/`applyAction` round-trip
 * lives in [CodeEditor].
 */
/**
 * The lightbulb shown FLOATING just above the caret when quick-fixes are available — an elevated chip so it
 * reads as a tappable affordance hovering over the code. Tap to open the fix list.
 */
@Composable
fun FloatingLightbulb(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(Ca.radius.sm))
            .background(Ca.colors.surface2)
            .border(1.dp, Ca.colors.separator, RoundedCornerShape(Ca.radius.sm))
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(CaIcons.lightbulb, contentDescription = stringResource(Res.string.codeaction_show_context_actions), tint = Ca.colors.warning, modifier = Modifier.size(16.dp))
    }
}

/**
 * The gutter affordance shown beside a Compose `@Preview` function: a small accent-tinted glyph the user
 * taps to render that specific composable in the Preview surface. Positioned by [CodeEditor] in the gutter
 * at the function's line; the touch target is a touch larger than the icon for comfortable tapping.
 */
@Composable
fun PreviewGutterIcon(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(20.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(CaIcons.image, contentDescription = stringResource(Res.string.codeaction_preview_composable), tint = Ca.colors.accent, modifier = Modifier.size(15.dp))
    }
}

@Composable
fun CodeActionsMenu(
    actions: List<UiAction>,
    selectedIndex: Int,
    width: Dp,
    onPick: (Int) -> Unit,
    maxListHeight: Dp = 280.dp,
) {
    Column(
        Modifier
            .width(width)
            .background(Ca.colors.glassThick, RoundedCornerShape(Ca.radius.md))
            .border(1.dp, Ca.colors.separator, RoundedCornerShape(Ca.radius.md)),
    ) {
        val compactions = remember(actions) { importCompactions(actions) }
        LazyColumn(modifier = Modifier.heightIn(max = maxListHeight)) {
            itemsIndexed(actions) { index, action ->
                ActionRow(action, compactions[index], index == selectedIndex, onPick = { onPick(index) })
            }
        }
    }
}

/**
 * One action row. An "Import <fqn>" quick-fix is shown COMPACTED — `Import <first>…<Name>` with the imported
 * name emphasized so a candidate list scans by name, not by a wall of identical package prefixes — and a
 * long-press (touch) / long mouse-press pops a tooltip with the whole package. Other actions render verbatim.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ActionRow(action: UiAction, compact: CompactImport?, selected: Boolean, onPick: () -> Unit, height: Dp = 38.dp) {
    // Remembered unconditionally (a LazyColumn slot may flip between an import and a non-import action).
    val tooltipState = rememberTooltipState(isPersistent = true)
    val scope = rememberCoroutineScope()

    val row: @Composable (Modifier) -> Unit = { clickMod ->
        Row(
            Modifier
                .fillMaxWidth()
                .height(height)
                .background(if (selected) Ca.colors.accentSoft else Color.Transparent)
                .then(clickMod)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val isFix = action.kind == UiActionKind.QUICK_FIX
            Icon(
                if (isFix) CaIcons.gear else CaIcons.lightbulb,
                contentDescription = null,
                tint = if (isFix) Ca.colors.accent else Ca.colors.warning,
                modifier = Modifier.size(15.dp),
            )
            if (compact != null) {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = Ca.colors.textSecondary)) { append(compact.dimPrefix) }
                        withStyle(SpanStyle(color = Ca.colors.textPrimary, fontWeight = FontWeight.Medium)) { append(compact.name) }
                    },
                    style = Ca.type.code,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(
                    action.title,
                    style = Ca.type.code,
                    color = Ca.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    if (compact != null) {
        // combinedClickable cleanly splits tap (apply) from long-press (peek); the TooltipBox only renders the
        // popup (enableUserInput = false) and we drive it via show(), so the two gestures never fight.
        TooltipBox(
            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
            tooltip = { PlainTooltip { Text(compact.fqn, style = Ca.type.caption2) } },
            state = tooltipState,
            enableUserInput = false,
        ) {
            row(Modifier.combinedClickable(onClick = onPick, onLongClick = { scope.launch { tooltipState.show() } }))
        }
    } else {
        row(Modifier.clickable(onClick = onPick))
    }
}

/** The compacted display of an "Import <fqn>" action: a dim [dimPrefix] lead (`Import <first>…<differing pkg>.`),
 *  the emphasized imported [name] (class / callable), and the whole [fqn] shown on long-press. */
internal data class CompactImport(val dimPrefix: String, val name: String, val fqn: String)

private val IMPORT_FQN = Regex("[\\w$]+(?:\\.[\\w$]+)+")
private const val IMPORT_PREFIX = "Import "

/**
 * Group-aware compaction for the `Import <fqn>` actions in [actions], keyed by their index. Within each set of
 * same-named candidates the package segments that are COMMON to all of them collapse to `…`, while the segments
 * that DIFFER stay visible — so `androidx.compose.material.Icon` and `androidx.compose.material3.Icon` read as
 * `Import androidx…material.Icon` / `Import androidx…material3.Icon` (like IntelliJ disambiguating same-named
 * files), and a lone candidate collapses all the way to `Import androidx…IconKt`. The first segment is always
 * kept for context and the imported name is always shown. Non-import / too-shallow (< 3 segments) titles are
 * absent from the map and render verbatim.
 */
internal fun importCompactions(actions: List<UiAction>): Map<Int, CompactImport> {
    val parsed = actions.mapIndexedNotNull { i, a ->
        if (!a.title.startsWith(IMPORT_PREFIX)) return@mapIndexedNotNull null
        val fqn = a.title.substring(IMPORT_PREFIX.length)
        if (!IMPORT_FQN.matches(fqn)) return@mapIndexedNotNull null
        val segs = fqn.split('.')
        if (segs.size < 3) return@mapIndexedNotNull null
        Triple(i, segs, fqn)
    }
    if (parsed.isEmpty()) return emptyMap()

    val out = HashMap<Int, CompactImport>()
    // Diff within same-name groups: what distinguishes same-named candidates is their (middle) package.
    parsed.groupBy { it.second.last() }.forEach { (name, group) ->
        val commonMid = commonPrefixLen(group.map { it.second.subList(1, it.second.size - 1) })
        group.forEach { (idx, segs, fqn) ->
            val first = segs.first()
            val middle = segs.subList(1, segs.size - 1)
            val collapse = commonMid.coerceAtMost(middle.size)
            val remaining = middle.drop(collapse)
            val pkg = buildString {
                append(first)
                if (collapse > 0) {
                    append('…')
                    if (remaining.isNotEmpty()) { append(remaining.joinToString(".")); append('.') }
                } else {
                    if (middle.isNotEmpty()) { append('.'); append(middle.joinToString(".")) }
                    append('.')
                }
            }
            out[idx] = CompactImport(dimPrefix = "$IMPORT_PREFIX$pkg", name = name, fqn = fqn)
        }
    }
    return out
}

/** Length of the longest shared leading run across [lists] (0 when they diverge immediately or [lists] is empty). */
private fun commonPrefixLen(lists: List<List<String>>): Int {
    if (lists.isEmpty()) return 0
    val minLen = lists.minOf { it.size }
    var n = 0
    while (n < minLen && lists.all { it[n] == lists[0][n] }) n++
    return n
}

/**
 * A bottom-docked sheet for a single diagnostic: the **full** (selectable, scrollable) message — so a long
 * error is readable on a phone where the inline chip is truncated — plus its quick-fixes as large touch
 * targets. Tapping the scrim or the × dismisses it. Pure UI; the host fetches the [actions] for the
 * diagnostic's range and applies the picked one over the [dev.ide.ui.backend.IdeBackend.applyAction] round-trip.
 */
@Composable
fun DiagnosticSheet(
    severity: UiSeverity,
    unused: Boolean,
    message: String,
    actions: List<UiAction>,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val color = when (severity) {
        UiSeverity.Error -> Ca.colors.error
        UiSeverity.Warning -> if (unused) Ca.colors.textTertiary else Ca.colors.warning
        UiSeverity.Info -> Ca.colors.info
        UiSeverity.Hint -> Ca.colors.textTertiary
    }
    val icon = when (severity) {
        UiSeverity.Error -> CaIcons.error
        UiSeverity.Warning -> CaIcons.warning
        UiSeverity.Info, UiSeverity.Hint -> CaIcons.info
    }
    val label = when (severity) {
        UiSeverity.Error -> stringResource(Res.string.codeaction_severity_error)
        UiSeverity.Warning -> if (unused) stringResource(Res.string.codeaction_severity_unused) else stringResource(Res.string.codeaction_severity_warning)
        UiSeverity.Info -> stringResource(Res.string.codeaction_severity_info)
        UiSeverity.Hint -> stringResource(Res.string.codeaction_severity_hint)
    }
    val sheetShape = RoundedCornerShape(topStart = Ca.radius.sheet, topEnd = Ca.radius.sheet)
    // scrim over the editor pane (tap to dismiss); panel docked at the bottom for thumb reach
    Box(
        Modifier
            .fillMaxSize()
            .background(Ca.colors.scrim)
            .pointerInput(Unit) { detectTapGestures { onDismiss() } },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(Ca.colors.glassThick, sheetShape)
                .border(1.dp, Ca.colors.separator, sheetShape)
                .pointerInput(Unit) { detectTapGestures { } } // swallow taps so the panel itself doesn't dismiss
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(icon, null, Modifier.size(18.dp), tint = color)
                Text(label, color = color, style = Ca.type.caption, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(0.dp).weight(1f))
                Box(
                    Modifier.size(30.dp).clip(CircleShape).clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) { Icon(CaIcons.close, stringResource(Res.string.codeaction_dismiss), Modifier.size(16.dp), tint = Ca.colors.textSecondary) }
            }
            Spacer(Modifier.height(10.dp))
            SelectionContainer {
                Text(
                    message,
                    color = Ca.colors.textPrimary,
                    style = Ca.type.code,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp).verticalScroll(rememberScrollState()),
                )
            }
            if (actions.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(Ca.colors.separator))
                Spacer(Modifier.height(6.dp))
                Text(stringResource(Res.string.codeaction_quick_fixes), color = Ca.colors.textTertiary, style = Ca.type.caption2, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                val compactions = remember(actions) { importCompactions(actions) }
                actions.forEachIndexed { i, a -> ActionRow(a, compactions[i], selected = false, onPick = { onPick(i) }, height = 48.dp) }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
