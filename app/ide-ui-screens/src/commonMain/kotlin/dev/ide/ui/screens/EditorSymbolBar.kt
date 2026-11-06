package dev.ide.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.PopupPositionProvider
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import dev.ide.ui.generated.resources.symbolbar_bookmark
import dev.ide.ui.generated.resources.symbolbar_clipboard
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
 * - **hold, then drag** turns the bar into a trackpad driving a free, floating cursor ([trackpad]) that the
 *   caret snaps to.
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
    /** What the hold-and-drag trackpad drives (the editor's floating cursor). Null disables it. */
    trackpad: SymbolBarTrackpad? = null,
    /** Opens with the ⋯ row showing, for a preview or snapshot. */
    initiallyExpanded: Boolean = false,
) {
    val separator = MaterialTheme.colorScheme.outlineVariant // captured for the draw lambda
    val tab = symbols.firstOrNull { it.action == CustomizationActions.TAB }
    val textKeys = symbols.filter { it.action == null }.sortedByDescending { it.pinned } // pinned ones first
    val folded = symbols.filter { it.action != null && it.action != CustomizationActions.TAB }
    var moreOpen by remember { mutableStateOf(initiallyExpanded) }
    // When the ⋯ row last closed by an outside tap: that same tap landing on ⋯ must not reopen it at once.
    var dismissedAt by remember { mutableStateOf<TimeSource.Monotonic.ValueTimeMark?>(null) }
    var trackpadOn by remember { mutableStateOf(false) }
    val hasMore = folded.isNotEmpty() || showDiagnosticJump || onCustomize != null
    val haptics = LocalHapticFeedback.current
    val gestures = KeyGestures(
        trackpad = trackpad,
        onTrackpad = { on ->
            trackpadOn = on
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
                val turn by animateFloatAsState(if (moreOpen) 90f else 0f, tween(MoreMillis), label = "moreTurn")
                KeyCap(
                    label = stringResource(Res.string.symbolbar_more), gestures = gestures,
                    onTap = {
                        val justDismissed = dismissedAt?.let { it.elapsedNow() < 300.milliseconds } == true
                        if (!justDismissed) moreOpen = !moreOpen
                    },
                    icon = CaIcons.ellipsis, active = moreOpen, iconRotation = { turn },
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
        AnimatedVisibility(
            visible = trackpadOn,
            modifier = Modifier.matchParentSize(),
            enter = fadeIn(tween(120)),
            exit = fadeOut(tween(180)),
        ) {
            Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(Res.string.symbolbar_trackpad),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // The popup outlives `moreOpen` by its exit animation, so it closes the way it opened.
        val moreState = remember { MutableTransitionState(initiallyExpanded) }
        moreState.targetState = moreOpen && hasMore
        if (moreState.currentState || moreState.targetState) {
            MoreKeysRow(
                visibleState = moreState,
                folded = folded,
                showDiagnosticJump = showDiagnosticJump,
                onCustomize = onCustomize,
                onAction = onAction,
                onDismiss = {
                    if (moreOpen) dismissedAt = TimeSource.Monotonic.markNow()
                    moreOpen = false
                },
            )
        }
    }
}

/** The actions behind ⋯, as a row of labelled keys floating just above the bar's right end. */
@Composable
private fun MoreKeysRow(
    visibleState: MutableTransitionState<Boolean>,
    folded: List<UiSymbolKey>,
    showDiagnosticJump: Boolean,
    onCustomize: (() -> Unit)?,
    onAction: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val gap = with(LocalDensity.current) { 6.dp.roundToPx() }
    val position = remember(gap) { AboveAnchorEnd(gap) }
    Popup(
        popupPositionProvider = position,
        onDismissRequest = onDismiss,
        // Not focusable: the editor keeps focus and the keyboard stays up under it.
        properties = PopupProperties(focusable = false),
    ) {
        // Grows out of the ⋯ key (the row's bottom-right corner) and sinks back into it.
        val origin = TransformOrigin(1f, 1f)
        AnimatedVisibility(
            visibleState = visibleState,
            enter = fadeIn(tween(MoreMillis)) + scaleIn(tween(MoreMillis, easing = FastOutSlowInEasing), initialScale = 0.8f, transformOrigin = origin) +
                slideInVertically(tween(MoreMillis, easing = FastOutSlowInEasing)) { it / 3 },
            exit = fadeOut(tween(MoreMillis * 2 / 3)) + scaleOut(tween(MoreMillis * 2 / 3), targetScale = 0.85f, transformOrigin = origin),
        ) {
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
                        // A key that opens something of its own (the paste history) closes this row, which would
                        // cover it; the line actions keep it open for a repeat.
                        if (action == CustomizationActions.PASTE_HISTORY) onDismiss()
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

/**
 * Places a popup just above its anchor (the bar), right edges aligned, [gap] px clear of both. Positioned by the
 * popup window itself: drawing the content above an anchored window instead gets cut by that window on Android.
 */
private class AboveAnchorEnd(private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize,
    ): IntOffset {
        val x = if (layoutDirection == LayoutDirection.Ltr) anchorBounds.right - popupContentSize.width - gap else anchorBounds.left + gap
        val y = anchorBounds.top - popupContentSize.height - gap
        return IntOffset(x.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)), y.coerceAtLeast(0))
    }
}

@Composable
private fun foldedLabel(action: String, fallback: String): String = when (action) {
    CustomizationActions.COMMENT -> stringResource(Res.string.symbolbar_comment)
    CustomizationActions.MOVE_LINE_UP -> stringResource(Res.string.symbolbar_move_line_up)
    CustomizationActions.MOVE_LINE_DOWN -> stringResource(Res.string.symbolbar_move_line_down)
    CustomizationActions.DUPLICATE_LINE -> stringResource(Res.string.symbolbar_duplicate_line)
    CustomizationActions.NEXT_PROBLEM -> stringResource(Res.string.symbolbar_next_problem)
    CustomizationActions.PASTE_HISTORY -> stringResource(Res.string.symbolbar_clipboard)
    CustomizationActions.TOGGLE_BOOKMARK -> stringResource(Res.string.symbolbar_bookmark)
    else -> fallback
}

private fun foldedIcon(action: String): ImageVector? = when (action) {
    CustomizationActions.MOVE_LINE_UP -> CaIcons.chevronUp
    CustomizationActions.MOVE_LINE_DOWN -> CaIcons.chevronDown
    CustomizationActions.DUPLICATE_LINE -> CaIcons.copy
    CustomizationActions.NEXT_PROBLEM -> CaIcons.warning
    CustomizationActions.PASTE_HISTORY -> CaIcons.clock
    CustomizationActions.TOGGLE_BOOKMARK -> CaIcons.pin
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

/**
 * What the bar's trackpad drives: a floating cursor that [begin]s at the caret, [move]s by fractional columns and
 * lines as the finger drags, and [end]s on release.
 */
internal interface SymbolBarTrackpad {
    fun begin()
    fun move(columns: Float, lines: Float)
    fun end()
}

/** The bar-wide gesture wiring every key shares: the trackpad and its on/off signal. */
private class KeyGestures(val trackpad: SymbolBarTrackpad?, val onTrackpad: (Boolean) -> Unit)

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
    /** Degrees to turn the icon by, read at draw time (the ⋯ key turns as its row opens). */
    iconRotation: () -> Float = { 0f },
) {
    var press by remember { mutableStateOf(KeyPress.NONE) }
    val tap by rememberUpdatedState(onTap)
    val alt by rememberUpdatedState(onAlternate)
    val colors = MaterialTheme.colorScheme
    val face by animateColorAsState(
        when {
            press == KeyPress.ALTERNATE -> colors.primaryContainer
            press == KeyPress.DOWN || active -> colors.secondaryContainer
            else -> colors.surfaceContainerHighest
        },
        tween(90),
        label = "keyFace",
    )
    // Pressed caps sink a little, with a spring so a quick tap still shows it and bounces back.
    val squish by animateFloatAsState(
        if (press == KeyPress.NONE) 1f else 0.9f,
        spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow + 200f),
        label = "keySquish",
    )
    // 0 at rest, 1 flicked: the corner glyph flies to the middle and grows while the key's own drops away.
    val flick by animateFloatAsState(
        if (press == KeyPress.ALTERNATE) 1f else 0f,
        spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMedium),
        label = "keyFlick",
    )
    val ink = if (accent) colors.primary else colors.onSurface
    Box(
        Modifier
            .height(KeyHeight)
            .widthIn(min = KeyMinWidth)
            .graphicsLayer { scaleX = squish; scaleY = squish }
            .clip(KeyShape)
            .background(face, KeyShape)
            .semantics { contentDescription = label }
            .pointerInput(alternate, gestures.trackpad) {
                keyGestures(
                    hasAlternate = alternate != null,
                    trackpad = gestures.trackpad,
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
            icon != null -> Icon(icon, label, Modifier.size(19.dp).graphicsLayer { rotationZ = iconRotation() }, tint = ink)
            else -> Text(
                label,
                Modifier.graphicsLayer { alpha = 1f - flick; translationY = flick * 10.dp.toPx(); scaleX = 1f - 0.3f * flick; scaleY = 1f - 0.3f * flick },
                style = Ide.type.code, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = ink, maxLines = 1,
            )
        }
        // The flick-up partner: small in the top corner at rest, as a phone keyboard shows its long-press letters;
        // flicked, it flies to the middle at full size. Drawn at full size and scaled, so nothing re-lays out.
        if (alternate != null) {
            val restScale = 9f / 17f
            Text(
                alternate,
                Modifier.graphicsLayer {
                    val s = restScale + (1f - restScale) * flick
                    scaleX = s; scaleY = s
                    translationX = (1f - flick) * CornerDx.toPx()
                    translationY = (1f - flick) * CornerDy.toPx()
                },
                style = Ide.type.code, fontSize = 17.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                color = lerp(colors.onSurfaceVariant.copy(alpha = 0.7f), colors.onPrimaryContainer, flick),
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
    trackpad: SymbolBarTrackpad?,
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
                    // The finger drives a free cursor continuously; the caret snaps to the text under it.
                    onTrackpad(true)
                    trackpad.begin()
                    try {
                        val perColumn = TrackpadStepX.toPx()
                        val perLine = TrackpadStepY.toPx()
                        var last = start
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            change.consume()
                            val d = change.position - last
                            last = change.position
                            if (d.x != 0f || d.y != 0f) trackpad.move(d.x / perColumn, d.y / perLine)
                            if (!change.pressed) break
                        }
                    } finally {
                        trackpad.end()
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

/** Where the alternate glyph rests, from the cap's centre: its top-right corner. */
private val CornerDx = 11.dp
private val CornerDy = (-11).dp

/** How long the ⋯ row takes to open (it closes in two thirds of that). */
private const val MoreMillis = 180

/** How far up a key must be flicked to type its alternate. */
private val FlickUp = 14.dp

/** Finger travel per column sideways, and per line up or down, for the floating cursor. */
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
