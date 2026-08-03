package dev.ide.ui.editor.preview

import dev.ide.ui.theme.Ide
import androidx.compose.material3.MaterialTheme
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.copy
import dev.ide.ui.generated.resources.preview_copy_problems
import dev.ide.ui.generated.resources.preview_device_compact
import dev.ide.ui.generated.resources.preview_device_phone
import dev.ide.ui.generated.resources.preview_device_tablet
import dev.ide.ui.generated.resources.preview_collapse_toolbar
import dev.ide.ui.generated.resources.preview_expand_toolbar
import dev.ide.ui.generated.resources.preview_fit
import dev.ide.ui.generated.resources.preview_lock
import dev.ide.ui.generated.resources.preview_night
import dev.ide.ui.generated.resources.preview_night_mode
import dev.ide.ui.generated.resources.preview_problems
import dev.ide.ui.generated.resources.preview_problems_count
import dev.ide.ui.generated.resources.preview_rotate
import dev.ide.ui.generated.resources.preview_unlock
import dev.ide.ui.generated.resources.preview_zoom_in
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.min
import kotlin.math.roundToInt

/** A previewed device profile (logical size in dp + density). Landscape swaps width/height. */
data class DeviceProfile(val label: String, val wdp: Int, val hdp: Int, val density: Float)

/** The device profiles offered by every preview surface (layout XML and Compose alike). */
val PREVIEW_DEVICES = listOf(
    DeviceProfile("Phone", 360, 800, 2f),
    DeviceProfile("Compact", 320, 568, 2f),
    DeviceProfile("Tablet", 600, 960, 2f),
)

/**
 * The shared chrome state for a preview surface: device / orientation / night plus the free pan-zoom
 * transform. Held per file (reset via [rememberPreviewSurfaceState]) so both the layout-XML and Compose
 * previews offer the same device selection, rotation, night toggle and pinch/zoom behaviour.
 */
@Stable
class PreviewSurfaceState {
    var deviceIndex by mutableStateOf(0)
    var landscape by mutableStateOf(false)
    var night by mutableStateOf(false)

    /** User zoom; `0` means "fit to window" (the surface computes the fit scale). */
    var userScale by mutableStateOf(0f)
    var offset by mutableStateOf(Offset.Zero)

    /**
     * "Lock the view" mode. While locked the surface stops intercepting drag/pinch, so pointer events fall
     * through to the previewed content — the user can scroll a list, drag a slider, or tap a button inside a
     * Compose `@Preview` instead of panning the canvas. Pan/zoom via the bar buttons still work; toggled from
     * the bottom bar. Held per file (reset with the rest of the surface state on a file switch).
     */
    var locked by mutableStateOf(false)

    val device: DeviceProfile get() = PREVIEW_DEVICES[deviceIndex]
    val wdp: Int get() = if (landscape) device.hdp else device.wdp
    val hdp: Int get() = if (landscape) device.wdp else device.hdp
    val widthPx: Int get() = (wdp * device.density).toInt()
    val heightPx: Int get() = (hdp * device.density).toInt()
}

@Composable
fun rememberPreviewSurfaceState(resetKey: Any?): PreviewSurfaceState = remember(resetKey) { PreviewSurfaceState() }

/** Localized display name for a built-in device profile. [PREVIEW_DEVICES] keeps its stable English labels
 *  (they double as device identifiers); a `@Preview(device=...)` override's own label falls through as-is. */
@Composable
private fun deviceLabel(label: String): String = when (label) {
    "Phone" -> stringResource(Res.string.preview_device_phone)
    "Compact" -> stringResource(Res.string.preview_device_compact)
    "Tablet" -> stringResource(Res.string.preview_device_tablet)
    else -> label
}

/**
 * The common preview surface both Preview views render into: a device card floating over a dotted (or
 * blueprint) ground with free pan + pinch/zoom (drag to scroll, pinch or the bottom bar to zoom, the fit
 * button to recentre). A top glass bar carries device / orientation / night plus [topBarExtras]; the bottom
 * bar holds zoom / fit / a lock toggle plus [bottomBarExtras]. Either bar collapses to a small edge handle
 * (so the render can use the full pane) and expands back on tap. The view-specific content is drawn inside
 * the card by [card] (given the device viewport in px and its density); free-floating panels go in [overlays].
 *
 * The bottom bar's **Lock** toggle ([PreviewSurfaceState.locked]) suspends pan/zoom so pointer events reach
 * the previewed content — the user scrolls/taps/drags inside a live Compose `@Preview` instead of moving the
 * canvas.
 *
 * In [split] mode (the editor+preview side-by-side/stacked view) the bars start collapsed (the pane is small
 * and shared with the editor) and the fit scale tracks WIDTH only, so dragging the split divider (which
 * changes the pane's height) no longer rescales the preview — see [split].
 */
@Composable
fun PreviewSurface(
    modifier: Modifier,
    state: PreviewSurfaceState,
    cardColor: Color,
    cardBorderColor: Color,
    blueprint: Boolean = false,
    onSurfaceTap: (() -> Unit)? = null,
    /** When set (a `@Preview(device=...)`/`widthDp`/`heightDp`), the card is sized to this profile instead of the
     *  user-selected one, and the device pill shows it as dictated by the annotation (non-cycling). */
    deviceOverride: DeviceProfile? = null,
    /** Size the card to its content (a Compose `@Preview` with no device/size declared wraps the composable),
     *  bounded by the selected device as a max. Ignored when [deviceOverride] dictates a fixed size. */
    wrapContent: Boolean = false,
    /** Rendered inside the Split view (editor + preview together). Starts the top/bottom chrome bars collapsed
     *  (the pane is small and shares space with the editor — the user expands them on demand) and makes the fit
     *  scale fit-to-WIDTH only, so the preview stays a deterministic size while the split divider is dragged
     *  vertically instead of rescaling on it. Pan + pinch-zoom stay available regardless. Full-screen Preview
     *  leaves this false (bars start expanded). */
    split: Boolean = false,
    topBarExtras: @Composable RowScope.(compact: Boolean) -> Unit = {},
    bottomBarExtras: @Composable RowScope.(compact: Boolean) -> Unit = {},
    overlays: @Composable BoxScope.() -> Unit = {},
    card: @Composable BoxScope.(widthPx: Int, heightPx: Int, density: Float) -> Unit,
) {
    val device = deviceOverride ?: state.device
    val wdp = if (state.landscape) device.hdp else device.wdp
    val hdp = if (state.landscape) device.wdp else device.hdp
    val widthPx = (wdp * device.density).toInt()
    val heightPx = (hdp * device.density).toInt()
    val dotColor = MaterialTheme.colorScheme.outlineVariant
    val tapHandler = rememberUpdatedState(onSurfaceTap)

    BoxWithConstraints(modifier.fillMaxSize().background(Ide.colors.editorBg).clipToBounds()) {
        // Below this width the chrome bars can't show full labels without squishing, so they collapse to
        // icon / dimension-only form and the view-specific extras follow suit (see [topBarExtras]).
        val compact = maxWidth < 480.dp
        val hostDensity = LocalDensity.current.density
        val devWdp = widthPx / hostDensity
        val devHdp = heightPx / hostDensity
        // Wrap mode (a Compose @Preview with no device/size) sizes the card to the composable, capped at the
        // device viewport as a max. The fit math needs a known size, so wrap renders at 1:1 (still pan/zoomable).
        val wrap = wrapContent && deviceOverride == null
        // Fit-to-window scale. Split mode fits to WIDTH only: the pane's height is what the user drags (the
        // split divider), and folding height into the fit would rescale the whole preview on every drag —
        // width-only keeps the scale deterministic as the pane is resized vertically (the card keeps its
        // device aspect ratio via requiredSize + uniform scale, and simply pans/clips past the pane edge).
        // Full-screen preview fits both axes so the whole device stays framed under the chrome bars.
        val widthFit = (maxWidth.value * 0.9f) / devWdp
        val fit = (if (split) widthFit else min(widthFit, (maxHeight.value * 0.86f) / devHdp)).coerceIn(0.1f, 4f)
        val fitState = rememberUpdatedState(if (wrap) 1f else fit)
        val scale = if (state.userScale <= 0f) (if (wrap) 1f else fit) else state.userScale

        // Re-fit (and recentre) whenever the device viewport changes — rotation or device switch.
        LaunchedEffect(widthPx, heightPx, wrap) { state.userScale = 0f; state.offset = Offset.Zero }

        // Each chrome bar collapses to a small edge handle so the render can use the full pane. They start
        // collapsed in split (the pane is small and shared with the editor — the old behaviour hid them
        // outright; now the user can expand them on demand) and expanded full-screen. Keyed on state (per
        // file) + split so a file switch or a split toggle re-applies the default.
        var topBarExpanded by remember(state, split) { mutableStateOf(!split) }
        var bottomBarExpanded by remember(state, split) { mutableStateOf(!split) }

        // The canvas surface: a dotted neutral ground, or a flat blueprint-blue ground for the wireframe.
        Box(
            Modifier.fillMaxSize()
                .drawBehind {
                    if (blueprint) drawRect(BlueprintGround)
                    else drawDotGrid(dotColor, 15.dp.toPx(), 1.dp.toPx())
                }
                // Pan/zoom is suspended in Lock mode (see [PreviewSurfaceState.locked]) so pointer events reach
                // the previewed content instead — the user interacts with the render rather than moving it.
                .then(
                    if (state.locked) Modifier
                    else Modifier.pointerInput(state) {
                        // Drive pan/zoom on the INITIAL pass so the surface wins over any interactive previewed
                        // content (a Compose @Preview can hold scrollables/buttons that would otherwise eat the
                        // drag/pinch). Only consume once the gesture actually moves, so a plain tap still falls
                        // through to the card (the XML hit-test) and the deselect handler below.
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.changes.none { it.pressed }) continue
                                val zoom = event.calculateZoom()
                                val pan = event.calculatePan()
                                if (zoom != 1f || pan != Offset.Zero) {
                                    val base = if (state.userScale <= 0f) fitState.value else state.userScale
                                    state.userScale = (base * zoom).coerceIn(0.2f, 5f)
                                    state.offset += pan
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                }
                            }
                        }
                    },
                )
                .pointerInput(Unit) { detectTapGestures(onTap = { tapHandler.value?.invoke() }) },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .graphicsLayer {
                        scaleX = scale; scaleY = scale; translationX = state.offset.x; translationY = state.offset.y
                    }
                    // Fixed mode → requiredSize (NOT size): keep the device's true aspect ratio even when the
                    // pane is smaller than the device's dp (`size` would square a 360×800 phone in a narrow
                    // pane); requiredSize overflows and the fit scale shrinks it back into the clipped surface.
                    // Wrap mode → size to the content, bounded by the device viewport as a max.
                    .then(
                        if (wrap) Modifier.wrapContentSize().widthIn(max = devWdp.dp).heightIn(max = devHdp.dp)
                        else Modifier.requiredSize(devWdp.dp, devHdp.dp),
                    )
                    .shadow(if (blueprint) 0.dp else 16.dp, RoundedCornerShape(Ca.radius.lg))
                    .clip(RoundedCornerShape(Ca.radius.lg))
                    .background(cardColor)
                    .border(1.dp, cardBorderColor, RoundedCornerShape(Ca.radius.lg)),
            ) {
                card(widthPx, heightPx, device.density)
            }
        }

        // Top bar: device / orientation / night + view-specific extras. On a narrow pane it collapses to a
        // compact icon/dimension-only form so the cluster shrinks with the surface instead of squishing. When
        // collapsed (default in split) the full bar slides away and a small expand handle tucks into the top-
        // right corner (out of the way of the content + the problem chip at top-left); pan/pinch-zoom work
        // either way. The bar and its handle crossfade so the transition is animated, not a hard swap.
        AnimatedVisibility(
            visible = topBarExpanded,
            modifier = Modifier.align(Alignment.TopCenter).padding(Ca.spacing.s3),
            enter = barEnter(fromTop = true), exit = barExit(fromTop = true),
        ) {
            GlassBar(Modifier) {
                // When a @Preview dictates the device the pill is non-cycling (the device is fixed by the annotation).
                PillButton({ if (deviceOverride == null) state.deviceIndex = (state.deviceIndex + 1) % PREVIEW_DEVICES.size }) {
                    Text(
                        if (compact) "$wdp×$hdp" else "${deviceLabel(device.label)} · $wdp×$hdp",
                        color = if (deviceOverride != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall, maxLines = 1,
                        modifier = Modifier.padding(horizontal = if (compact) Ca.spacing.s1 else Ca.spacing.s2),
                    )
                }
                Divider()
                PillButton({ state.landscape = !state.landscape }) {
                    Icon(CaIcons.refresh, stringResource(Res.string.preview_rotate), Modifier.size(15.dp), tint = if (state.landscape) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Divider()
                PillButton({ state.night = !state.night }) {
                    if (compact) {
                        Icon(CaIcons.moon, stringResource(Res.string.preview_night_mode), Modifier.size(15.dp), tint = if (state.night) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                    } else {
                        Text(stringResource(Res.string.preview_night), color = if (state.night) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = Ca.spacing.s1))
                    }
                }
                topBarExtras(compact)
                Divider()
                CollapseToggle(CaIcons.chevronUp) { topBarExpanded = false }
            }
        }
        AnimatedVisibility(
            visible = !topBarExpanded,
            modifier = Modifier.align(Alignment.TopEnd).padding(Ca.spacing.s3),
            enter = handleEnter, exit = handleExit,
        ) {
            ExpandHandle(Modifier, CaIcons.chevronDown) { topBarExpanded = true }
        }

        // Bottom bar: zoom / fit / lock + view-specific extras (same collapse behaviour as the top bar; its
        // handle tucks into the bottom-right corner).
        AnimatedVisibility(
            visible = bottomBarExpanded,
            modifier = Modifier.align(Alignment.BottomCenter).padding(Ca.spacing.s4),
            enter = barEnter(fromTop = false), exit = barExit(fromTop = false),
        ) {
            GlassBar(Modifier) {
                PillButton({ val b = if (state.userScale <= 0f) fit else state.userScale; state.userScale = (b / 1.25f).coerceIn(0.2f, 5f) }) { MinusGlyph() }
                Text("${(scale * 100f).roundToInt()}%", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(44.dp), textAlign = TextAlign.Center)
                PillButton({ val b = if (state.userScale <= 0f) fit else state.userScale; state.userScale = (b * 1.25f).coerceIn(0.2f, 5f) }) { Icon(CaIcons.plus, stringResource(Res.string.preview_zoom_in), Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurface) }
                Divider()
                PillButton({ state.userScale = 0f; state.offset = Offset.Zero }) { Icon(CaIcons.refresh, stringResource(Res.string.preview_fit), Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                Divider()
                // Lock the view: stop the surface eating drags so the user can interact with the previewed content.
                PillButton({ state.locked = !state.locked }) {
                    Icon(
                        if (state.locked) CaIcons.lock else CaIcons.lockOpen,
                        if (state.locked) stringResource(Res.string.preview_unlock) else stringResource(Res.string.preview_lock),
                        Modifier.size(15.dp),
                        tint = if (state.locked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                bottomBarExtras(compact)
                Divider()
                CollapseToggle(CaIcons.chevronDown) { bottomBarExpanded = false }
            }
        }
        AnimatedVisibility(
            visible = !bottomBarExpanded,
            modifier = Modifier.align(Alignment.BottomEnd).padding(Ca.spacing.s4),
            enter = handleEnter, exit = handleExit,
        ) {
            ExpandHandle(Modifier, CaIcons.chevronUp) { bottomBarExpanded = true }
        }

        overlays()
    }
}

/** Severity of a [PreviewIssue] — a warning (amber) or an error (red), driving the chip's icon + tint. */
enum class PreviewIssueLevel { WARNING, ERROR }

/**
 * A problem surfaced by a preview, shown in the shared [PreviewProblemChip]: a layout-inflation warning
 * (unknown tag, unresolved include) or a Compose interpret/render failure.
 */
data class PreviewIssue(val level: PreviewIssueLevel, val title: String, val message: String)

/**
 * The shared render-problem affordance both Preview views use: a small glass chip (severity icon + count)
 * at the surface corner that expands on tap to the list of issues. Renders nothing when [issues] is empty.
 */
@Composable
fun PreviewProblemChip(issues: List<PreviewIssue>, modifier: Modifier) {
    if (issues.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    val hasError = issues.any { it.level == PreviewIssueLevel.ERROR }
    val tint = if (hasError) MaterialTheme.colorScheme.error else Ide.colors.warning
    Column(modifier, horizontalAlignment = Alignment.Start) {
        GlassBar(Modifier) {
            PillButton({ open = !open }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (hasError) CaIcons.error else CaIcons.warning, stringResource(Res.string.preview_problems), Modifier.size(15.dp), tint = tint)
                    Text(" ${issues.size}", color = tint, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (open) {
            val clipboard = LocalClipboardManager.current
            // Plain-text form of every issue, for the Copy button (titles + messages, blank-line separated).
            val copyText = issues.joinToString("\n\n") { iss ->
                (if (iss.title.isNotEmpty()) iss.title + "\n" else "") + iss.message
            }
            Column(
                Modifier.padding(top = Ca.spacing.s2).widthIn(max = 360.dp).heightIn(max = 320.dp)
                    .clip(RoundedCornerShape(Ca.radius.md)).background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(Ca.radius.md))
                    .verticalScroll(rememberScrollState()).padding(Ca.spacing.s3),
                verticalArrangement = Arrangement.spacedBy(Ca.spacing.s2),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(pluralStringResource(Res.plurals.preview_problems_count, issues.size, issues.size), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    PillButton({ clipboard.setText(AnnotatedString(copyText)) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(CaIcons.copy, stringResource(Res.string.preview_copy_problems), Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(" " + stringResource(Res.string.copy), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                // SelectionContainer so the text can also be long-pressed/dragged to select + copy manually.
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(Ca.spacing.s2)) {
                        for (p in issues) Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            if (p.title.isNotEmpty()) {
                                Text(p.title, color = if (p.level == PreviewIssueLevel.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                            }
                            Text(p.message, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

/** Blueprint-mode ground + line colours (the LayoutLib wireframe idiom; design `#0f2a44` ground). */
internal val BlueprintGround = Color(0xFF0F2A44)
internal val BlueprintLine = Color(0xFF7FC7E8)

/** A radial dot grid (design canvas surface): small dots every [step] px. */
private fun DrawScope.drawDotGrid(color: Color, step: Float, radius: Float) {
    if (step <= 0f) return
    var y = 0f
    while (y <= size.height) {
        var x = 0f
        while (x <= size.width) {
            drawCircle(color, radius, Offset(x, y))
            x += step
        }
        y += step
    }
}

/** A horizontal glass pill container for control clusters. */
@Composable
internal fun GlassBar(modifier: Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.shadow(8.dp, RoundedCornerShape(Ca.radius.pill)).clip(RoundedCornerShape(Ca.radius.pill))
            .background(Ide.colors.glassReg).border(1.dp, Ide.colors.glassEdge, RoundedCornerShape(Ca.radius.pill))
            .padding(horizontal = Ca.spacing.s2, vertical = Ca.spacing.s1),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Ca.spacing.s1),
        content = content,
    )
}

@Composable
internal fun PillButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    // Stable gesture key (Unit) + always-current handler, so the tap detector isn't restarted every
    // recomposition (which could drop the tap — the cause of rotate/device taps not registering).
    val current by rememberUpdatedState(onClick)
    Box(
        Modifier.height(32.dp).widthIn(min = 32.dp).clip(RoundedCornerShape(Ca.radius.sm))
            .pointerInput(Unit) { detectTapGestures { current() } },
        contentAlignment = Alignment.Center,
        content = { content() },
    )
}

// Collapse/expand animation: the full bar fades + slides toward its edge; its corner handle fades + scales.
private const val CHROME_ANIM_MS = 180
private fun barEnter(fromTop: Boolean): EnterTransition =
    fadeIn(tween(CHROME_ANIM_MS)) + slideInVertically(tween(CHROME_ANIM_MS)) { h -> if (fromTop) -h else h }
private fun barExit(fromTop: Boolean): ExitTransition =
    fadeOut(tween(CHROME_ANIM_MS)) + slideOutVertically(tween(CHROME_ANIM_MS)) { h -> if (fromTop) -h else h }
private val handleEnter: EnterTransition = fadeIn(tween(CHROME_ANIM_MS)) + scaleIn(tween(CHROME_ANIM_MS), initialScale = 0.8f)
private val handleExit: ExitTransition = fadeOut(tween(CHROME_ANIM_MS)) + scaleOut(tween(CHROME_ANIM_MS), targetScale = 0.8f)

/** The chevron that collapses a chrome bar (sits at the bar's end). Muted so it reads as secondary chrome. */
@Composable
private fun CollapseToggle(icon: ImageVector, onClick: () -> Unit) {
    PillButton(onClick) {
        Icon(icon, stringResource(Res.string.preview_collapse_toolbar), Modifier.size(14.dp), tint = MaterialTheme.colorScheme.outline)
    }
}

/** The small glass handle a collapsed bar leaves behind — one chevron that expands the bar back open. */
@Composable
private fun ExpandHandle(modifier: Modifier, icon: ImageVector, onClick: () -> Unit) {
    GlassBar(modifier) {
        PillButton(onClick) {
            Icon(icon, stringResource(Res.string.preview_expand_toolbar), Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun MinusGlyph() {
    Box(Modifier.width(12.dp).height(2.dp).clip(RoundedCornerShape(1.dp)).background(MaterialTheme.colorScheme.onSurface))
}

@Composable
internal fun Divider() {
    Box(Modifier.width(1.dp).height(18.dp).background(MaterialTheme.colorScheme.outlineVariant))
}
