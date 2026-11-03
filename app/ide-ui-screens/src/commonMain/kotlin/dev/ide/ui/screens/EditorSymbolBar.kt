package dev.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.ide.ui.backend.CustomizationActions
import dev.ide.ui.backend.UiSymbolKey
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.symbolbar_comment
import dev.ide.ui.generated.resources.symbolbar_customize
import dev.ide.ui.generated.resources.symbolbar_duplicate_line
import dev.ide.ui.generated.resources.symbolbar_hide_keyboard
import dev.ide.ui.generated.resources.symbolbar_more
import dev.ide.ui.generated.resources.symbolbar_move_line_down
import dev.ide.ui.generated.resources.symbolbar_move_line_up
import dev.ide.ui.generated.resources.symbolbar_next_problem
import dev.ide.ui.generated.resources.symbolbar_tab
import dev.ide.ui.generated.resources.symbolbar_trackpad
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ide
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The keyboard accessory bar, shown above the soft keyboard while typing on touch (the caller gates it on the IME
 * being visible).
 *
 * Tab leads; the symbols take the rest of the width as key caps, scrolling sideways when they overflow. Every
 * other editor action (comment, move and duplicate line, next problem, customize) waits behind the trailing ⋯ key,
 * which opens a row of them above the bar. The keys:
 * - **tap** types the symbol, through smart insert, so `{`/`(`/`"` auto-close as typed;
 * - **flick up** types its alternate ([SYMBOL_ALTERNATES], shown small in the cap's corner): `(` gives `)`;
 * - **hold, then drag** turns the bar into a trackpad that slides the caret ([onMoveCaret]): sideways by
 *   character, up and down by line.
 *
 * No key uses `clickable`, which would take focus from the editor and close the keyboard with it.
 */
@Composable
internal fun EditorSymbolBar(
    onSymbol: (String) -> Unit,
    onAction: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** Every key in order. Defaults to the generic language set so the bar renders standalone (preview). */
    symbols: List<UiSymbolKey> = languageSymbolKeys(""),
    /** Offer "next problem" behind ⋯ (only while the file has diagnostics). */
    showDiagnosticJump: Boolean = false,
    /** Opens the Symbols & Macros editor, from behind ⋯. Null leaves it out. */
    onCustomize: (() -> Unit)? = null,
    /**
     * Dismisses the soft keyboard. Null hides the key, which is right on a host whose system back already
     * does it (Android): there the key would be a redundant slot on a bar where every slot is scarce.
     */
    onHideKeyboard: (() -> Unit)? = null,
    /** Slides the caret [chars] along the text and [lines] down, for the hold-and-drag trackpad. Null disables it. */
    onMoveCaret: ((chars: Int, lines: Int) -> Unit)? = null,
    /** Opens with the ⋯ row showing, for a preview or snapshot. */
    initiallyExpanded: Boolean = false,
) {
    val separator = MaterialTheme.colorScheme.outlineVariant // captured for the draw lambda
    val tab = symbols.firstOrNull { it.action == CustomizationActions.TAB }
    val textKeys = symbols.filter { it.action == null }.sortedByDescending { it.pinned } // pinned ones first
    val folded = symbols.filter { it.action != null && it.action != CustomizationActions.TAB }
    var moreOpen by remember { mutableStateOf(initiallyExpanded) }
    var trackpad by remember { mutableStateOf(false) }
    val hasMore = folded.isNotEmpty() || showDiagnosticJump || onCustomize != null
    val haptics = LocalHapticFeedback.current
    val gestures = KeyGestures(
        onMoveCaret = onMoveCaret,
        onTrackpad = { on ->
            trackpad = on
            if (on) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        },
    )

    Box(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(BarHeight)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .drawBehind { drawLine(separator, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1f) }
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(KeyGap),
        ) {
            if (tab != null) {
                KeyCap(
                    label = stringResource(Res.string.symbolbar_tab), gestures = gestures,
                    onTap = { onAction(CustomizationActions.TAB) }, accent = true, icon = CaIcons.tab,
                )
            }
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(KeyGap),
            ) {
                for (key in textKeys) {
                    KeyCap(
                        label = key.label, gestures = gestures,
                        onTap = { onSymbol(key.insert) },
                        alternate = SYMBOL_ALTERNATES[key.insert],
                        onAlternate = { SYMBOL_ALTERNATES[key.insert]?.let(onSymbol) },
                    )
                }
            }
            if (hasMore) {
                KeyCap(
                    label = stringResource(Res.string.symbolbar_more), gestures = gestures,
                    onTap = { moreOpen = !moreOpen }, icon = CaIcons.ellipsis, active = moreOpen,
                )
            }
            if (onHideKeyboard != null) {
                KeyCap(
                    label = stringResource(Res.string.symbolbar_hide_keyboard), gestures = gestures,
                    onTap = onHideKeyboard, icon = CaIcons.keyboardHide,
                )
            }
        }
        // While the trackpad drives the caret the keys are not keys: say what the drag does instead.
        if (trackpad) {
            Box(
                Modifier.matchParentSize().background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(Res.string.symbolbar_trackpad),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (moreOpen && hasMore) {
            MoreKeysRow(
                folded = folded,
                showDiagnosticJump = showDiagnosticJump,
                onCustomize = onCustomize,
                onAction = onAction,
                onDismiss = { moreOpen = false },
            )
        }
    }
}

/** The actions behind ⋯, as a row of labelled keys floating just above the bar's right end. */
@Composable
private fun MoreKeysRow(
    folded: List<UiSymbolKey>,
    showDiagnosticJump: Boolean,
    onCustomize: (() -> Unit)?,
    onAction: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val gap = with(androidx.compose.ui.platform.LocalDensity.current) { 6.dp.roundToPx() }
    Popup(
        alignment = Alignment.TopEnd,
        offset = IntOffset(-gap, -gap),
        onDismissRequest = onDismiss,
        // Not focusable: the editor keeps focus and the keyboard stays up under it.
        properties = PopupProperties(focusable = false),
    ) {
        Box(Modifier.offsetAbove()) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                for (key in folded) {
                    val action = key.action ?: continue
                    MoreKey(foldedIcon(action), foldedLabel(action, key.label), glyph = if (action == CustomizationActions.COMMENT) "//" else null) {
                        onAction(action)
                    }
                }
                if (showDiagnosticJump) {
                    MoreKey(CaIcons.warning, stringResource(Res.string.symbolbar_next_problem)) { onAction(CustomizationActions.NEXT_PROBLEM) }
                }
                if (onCustomize != null) {
                    MoreKey(CaIcons.gear, stringResource(Res.string.symbolbar_customize)) { onDismiss(); onCustomize() }
                }
            }
        }
    }
}

/** Lifts the popup's content by its own height, so a TopEnd-anchored row sits above the bar, not over it. */
private fun Modifier.offsetAbove(): Modifier = layout { measurable, constraints ->
    val p = measurable.measure(constraints)
    layout(p.width, p.height) { p.place(0, -p.height) }
}

@Composable
private fun foldedLabel(action: String, fallback: String): String = when (action) {
    CustomizationActions.COMMENT -> stringResource(Res.string.symbolbar_comment)
    CustomizationActions.MOVE_LINE_UP -> stringResource(Res.string.symbolbar_move_line_up)
    CustomizationActions.MOVE_LINE_DOWN -> stringResource(Res.string.symbolbar_move_line_down)
    CustomizationActions.DUPLICATE_LINE -> stringResource(Res.string.symbolbar_duplicate_line)
    CustomizationActions.NEXT_PROBLEM -> stringResource(Res.string.symbolbar_next_problem)
    else -> fallback
}

private fun foldedIcon(action: String): ImageVector? = when (action) {
    CustomizationActions.MOVE_LINE_UP -> CaIcons.chevronUp
    CustomizationActions.MOVE_LINE_DOWN -> CaIcons.chevronDown
    CustomizationActions.DUPLICATE_LINE -> CaIcons.copy
    CustomizationActions.NEXT_PROBLEM -> CaIcons.warning
    else -> null
}

/** One action behind ⋯: its icon (or a code glyph) over a short label. */
@Composable
private fun MoreKey(icon: ImageVector?, label: String, glyph: String? = null, onClick: () -> Unit) {
    Column(
        Modifier
            .width(68.dp)
            .clip(RoundedCornerShape(8.dp))
            .pointerInput(label) { awaitEachGesture { awaitFirstDown(); if (waitForUpOrCancel()) onClick() } }
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            if (icon != null) Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurface)
            else Text(glyph.orEmpty(), style = Ide.type.code, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        }
        Text(
            label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center, maxLines = 2, lineHeight = 12.sp, fontSize = 10.sp,
        )
    }
}

/** Waits for the pointer to lift; true for a clean release (not a cancel, not one consumed by a scroll). */
private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.waitForUpOrCancel(): Boolean {
    while (true) {
        val change = awaitPointerEvent().changes.firstOrNull() ?: return false
        if (change.isConsumed) return false
        if (!change.pressed) return true
    }
}

/** The bar-wide gesture wiring every key shares: the trackpad's caret callback and its on/off signal. */
private class KeyGestures(val onMoveCaret: ((Int, Int) -> Unit)?, val onTrackpad: (Boolean) -> Unit)

/** What a held key is doing, for its look: nothing, pressed, or flicked up to its alternate. */
private enum class KeyPress { NONE, DOWN, ALTERNATE }

/**
 * One key cap. Tap runs [onTap]; a flick up runs [onAlternate] when there is an [alternate]; a hold that then
 * drags drives the trackpad. A sideways drag that starts as one is left to the row's scroll.
 */
@Composable
private fun KeyCap(
    label: String,
    gestures: KeyGestures,
    onTap: () -> Unit,
    icon: ImageVector? = null,
    alternate: String? = null,
    onAlternate: () -> Unit = {},
    accent: Boolean = false,
    active: Boolean = false,
) {
    var press by remember { mutableStateOf(KeyPress.NONE) }
    val tap by rememberUpdatedState(onTap)
    val alt by rememberUpdatedState(onAlternate)
    val colors = MaterialTheme.colorScheme
    val face = when {
        press == KeyPress.ALTERNATE -> colors.primaryContainer
        press == KeyPress.DOWN || active -> colors.secondaryContainer
        else -> colors.surfaceContainerHighest
    }
    val ink = if (accent) colors.primary else colors.onSurface
    Box(
        Modifier
            .height(KeyHeight)
            .widthIn(min = KeyMinWidth)
            .clip(KeyShape)
            .background(face, KeyShape)
            .semantics { contentDescription = label }
            .pointerInput(alternate, gestures.onMoveCaret) {
                keyGestures(
                    hasAlternate = alternate != null,
                    trackpad = gestures.onMoveCaret,
                    onTrackpad = gestures.onTrackpad,
                    onPress = { press = it },
                    onTap = { tap() },
                    onAlternate = { alt() },
                )
            }
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            press == KeyPress.ALTERNATE && alternate != null ->
                Text(alternate, style = Ide.type.code, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = colors.onPrimaryContainer)
            icon != null -> Icon(icon, label, Modifier.size(19.dp), tint = ink)
            else -> Text(label, style = Ide.type.code, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = ink, maxLines = 1)
        }
        // The flick-up partner, small in the top corner, as a phone keyboard shows its long-press letters.
        if (alternate != null && press != KeyPress.ALTERNATE) {
            Text(
                alternate,
                Modifier.align(Alignment.TopEnd).padding(top = 2.dp),
                style = Ide.type.code, fontSize = 9.sp, lineHeight = 9.sp,
                color = colors.onSurfaceVariant.copy(alpha = 0.7f), maxLines = 1,
            )
        }
    }
}

/**
 * The key gesture: decide between a tap, a flick up, a hold-and-drag trackpad and a scroll, from how the finger
 * moves before it lifts or the long-press timeout passes.
 */
private suspend fun PointerInputScope.keyGestures(
    hasAlternate: Boolean,
    trackpad: ((Int, Int) -> Unit)?,
    onTrackpad: (Boolean) -> Unit,
    onPress: (KeyPress) -> Unit,
    onTap: () -> Unit,
    onAlternate: () -> Unit,
) = awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = false)
    onPress(KeyPress.DOWN)
    val slop = viewConfiguration.touchSlop
    val flick = FlickUp.toPx()
    val start = down.position
    try {
        // Until the hold timeout: a lift is a tap, a flick up the alternate, a sideways drag the row's scroll.
        val early = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            while (true) {
                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull Early.CANCEL
                val d = change.position - start
                if (!change.pressed) return@withTimeoutOrNull Early.TAP
                if (hasAlternate && -d.y > flick && -d.y > abs(d.x)) {
                    change.consume()
                    return@withTimeoutOrNull Early.FLICK
                }
                if (abs(d.x) > slop || change.isConsumed) return@withTimeoutOrNull Early.CANCEL
            }
            @Suppress("UNREACHABLE_CODE") Early.CANCEL
        }
        when (early) {
            Early.TAP -> onTap()
            Early.CANCEL -> Unit
            Early.FLICK -> {
                onPress(KeyPress.ALTERNATE)
                // Still above the key on release types the alternate; dragged back down cancels.
                var up = true
                while (true) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    change.consume()
                    up = start.y - change.position.y > flick / 2
                    onPress(if (up) KeyPress.ALTERNATE else KeyPress.DOWN)
                    if (!change.pressed) break
                }
                if (up) onAlternate()
            }
            null -> { // held past the timeout without moving
                if (trackpad == null) {
                    if (waitForUpOrCancel()) onTap()
                } else {
                    onTrackpad(true)
                    val stepX = TrackpadStepX.toPx()
                    val stepY = TrackpadStepY.toPx()
                    var carriedX = 0f
                    var carriedY = 0f
                    var last = start
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        change.consume()
                        carriedX += change.position.x - last.x
                        carriedY += change.position.y - last.y
                        last = change.position
                        val chars = (carriedX / stepX).toInt()
                        val lines = (carriedY / stepY).toInt()
                        if (chars != 0 || lines != 0) {
                            trackpad(chars, lines)
                            carriedX -= chars * stepX
                            carriedY -= lines * stepY
                        }
                        if (!change.pressed) break
                    }
                }
            }
        }
    } finally {
        onTrackpad(false)
        onPress(KeyPress.NONE)
    }
}

private enum class Early { TAP, FLICK, CANCEL }

private val BarHeight = 46.dp
private val KeyHeight = 36.dp
private val KeyMinWidth = 38.dp
private val KeyGap = 4.dp
private val KeyShape = RoundedCornerShape(8.dp)

/** How far up a key must be flicked to type its alternate. */
private val FlickUp = 14.dp

/** Trackpad travel per character sideways, and per line up or down. */
private val TrackpadStepX = 9.dp
private val TrackpadStepY = 22.dp

/** The shipped default symbol-bar keys before the per-language sets (mirrors `ide-core`'s
 *  `DefaultCustomizations.SYMBOLS`). A user set equal to it is treated as not customized ([effectiveSymbolKeys]). */
internal val DEFAULT_SYMBOL_KEYS: List<UiSymbolKey> = buildList {
    add(UiSymbolKey("Tab", "", pinned = true, action = CustomizationActions.TAB))
    add(UiSymbolKey("//", "", pinned = true, action = CustomizationActions.COMMENT))
    add(UiSymbolKey("Move line up", "", pinned = true, action = CustomizationActions.MOVE_LINE_UP))
    add(UiSymbolKey("Move line down", "", pinned = true, action = CustomizationActions.MOVE_LINE_DOWN))
    add(UiSymbolKey("Duplicate line", "", pinned = true, action = CustomizationActions.DUPLICATE_LINE))
    addAll(
        listOf(
            "{", "}", "(", ")", ";", "=", ".", ",", "\"", "'", ":", "<", ">", "/", "*",
            "[", "]", "+", "-", "&", "|", "!", "?", "@", "#", "_", "%", "\\",
        ).map { UiSymbolKey(it, it) },
    )
}
